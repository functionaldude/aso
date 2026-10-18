"""Exercise the client upgrade only inside keycloak-lifecycle-test.sh's disposable project."""
import copy
import json
import os
import selectors
import socket
import socketserver
import threading
from pathlib import Path
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request

base = os.environ.get('ASO_MIGRATION_TEST_BASE', '')
url = urllib.parse.urlsplit(base)
if url.scheme != 'http' or url.hostname != '127.0.0.1' or not url.port or url.port == 8180 or Path('.env').exists():
    raise SystemExit('Migration test requires an explicit disposable loopback port and no .env.')


# The CI runner itself runs in Docker. Forward a local admin endpoint to its Docker host,
# keeping the real upgrade helper's loopback-only contract and the owner's services untouched.
target_host = url.hostname
if Path('/.dockerenv').exists():
    target_host = os.environ.get('TESTCONTAINERS_HOST_OVERRIDE') or urllib.parse.urlsplit(os.environ.get('DOCKER_HOST', '')).hostname
    if not target_host:
        routes = [line.split() for line in Path('/proc/net/route').read_text().splitlines()[1:]]
        gateway = next(route[2] for route in routes if route[1] == '00000000')
        target_host = socket.inet_ntoa(bytes.fromhex(gateway)[::-1])


class AdminForwarder(socketserver.BaseRequestHandler):
    def handle(self):
        with socket.create_connection((target_host, url.port), timeout=15) as upstream:
            with selectors.DefaultSelector() as selector:
                selector.register(self.request, selectors.EVENT_READ, upstream)
                selector.register(upstream, selectors.EVENT_READ, self.request)
                while True:
                    ready = selector.select(timeout=15)
                    if not ready:
                        return
                    for key, _ in ready:
                        data = key.fileobj.recv(65536)
                        if not data:
                            return
                        key.data.sendall(data)


forwarder = socketserver.ThreadingTCPServer(('127.0.0.1', 0), AdminForwarder)
forwarder.daemon_threads = True
threading.Thread(target=forwarder.serve_forever, daemon=True).start()
base = f'http://127.0.0.1:{forwarder.server_address[1]}'


def send(path, data=None, method=None, bearer=None):
    headers = {}
    if bearer:
        headers['Authorization'] = 'Bearer ' + bearer
    if isinstance(data, (dict, list)):
        data = json.dumps(data).encode()
        headers['Content-Type'] = 'application/json'
    elif isinstance(data, bytes):
        headers['Content-Type'] = 'application/x-www-form-urlencoded'
    request = urllib.request.Request(base + path, data=data, method=method, headers=headers)
    with urllib.request.urlopen(request, timeout=15) as response:
        body = response.read()
        return json.loads(body) if body else None


try:
    token = send('/realms/master/protocol/openid-connect/token', urllib.parse.urlencode({
        'grant_type': 'password', 'client_id': 'admin-cli',
        'username': 'idp-admin', 'password': 'idp-admin-local-only',
    }).encode())['access_token']
    path = '/admin/realms/aso-dev'
    users_before = send(path + '/users', bearer=token)
    memberships_before = {
        user['id']: send(path + '/users/' + user['id'] + '/groups', bearer=token)
        for user in users_before
    }
    shared = send(path + '/clients?clientId=aso', bearer=token)[0]
    shared = send(path + '/clients/' + shared['id'], bearer=token)
    shared['redirectUris'] = [
        'http://127.0.0.1:8080/login/oauth2/code/oidc',
        'http://127.0.0.1:8766/callback',
        'http://localhost:6274/oauth/callback',
    ]
    send(path + '/clients/' + shared['id'], shared, 'PUT', token)
    offline_scope = next(scope for scope in send(path + '/client-scopes', bearer=token) if scope['name'] == 'offline_access')
    send(path + '/clients/' + shared['id'] + '/optional-client-scopes/' + offline_scope['id'], method='DELETE', bearer=token)
    offline_role = send(path + '/roles/offline_access', bearer=token)
    for user in users_before:
        send(path + '/users/' + user['id'] + '/role-mappings/realm', [offline_role], 'DELETE', token)
    send(path + '/roles', {'name': 'unrelated-test-role'}, 'POST', token)
    unrelated_role = send(path + '/roles/unrelated-test-role', bearer=token)
    send(path + '/users/' + users_before[0]['id'] + '/role-mappings/realm', [unrelated_role], 'POST', token)
    roles_before = {
        user['id']: {role['id'] for role in send(path + '/users/' + user['id'] + '/role-mappings/realm', bearer=token)}
        for user in users_before
    }
    legacy_ids = ('aso-web', 'aso-codex', 'aso-mcpjam', 'aso-mcp-test', 'aso-dev-probe')
    for client_id in legacy_ids + ('unrelated-test-client',):
        client = copy.deepcopy(shared)
        client.pop('id')
        for mapper in client.get('protocolMappers', []):
            mapper.pop('id', None)
        client['clientId'] = client_id
        client['enabled'] = True
        if client_id == 'aso-web':
            client['publicClient'] = False
            client['secret'] = 'legacy-fixture-local-only'
        send(path + '/clients', client, 'POST', token)

    environment = {key: value for key, value in os.environ.items() if not key.startswith('ASO_')}
    environment['ASO_KEYCLOAK_PORT'] = str(forwarder.server_address[1])
    unrelated_before = send(path + '/clients/' + send(path + '/clients?clientId=unrelated-test-client', bearer=token)[0]['id'], bearer=token)
    for attempt in range(2):
        subprocess.run([sys.executable, 'scripts/keycloak-configure-identity.py'], env=environment, check=True)
        clients = {client['clientId']: client for client in send(path + '/clients', bearer=token)}
        assert clients['aso']['enabled'] and clients['aso']['publicClient']
        assert clients['unrelated-test-client']['enabled']
        assert all(not clients[client_id]['enabled'] for client_id in legacy_ids)
        configured = send(path + '/clients/' + clients['aso']['id'], bearer=token)
        assert configured['attributes']['pkce.code.challenge.method'] == 'S256'
        assert set(configured['redirectUris']) == {
            'http://127.0.0.1:8080/login/oauth2/code/oidc',
            'http://127.0.0.1:8766/callback',
            'http://localhost:8767/callback',
            'http://localhost:8787/callback',
        }
        optional_scopes = send(path + '/clients/' + clients['aso']['id'] + '/optional-client-scopes', bearer=token)
        assert [scope['name'] for scope in optional_scopes].count('offline_access') == 1
        assert send(path + '/clients/' + clients['unrelated-test-client']['id'], bearer=token) == unrelated_before
        assert configured['standardFlowEnabled']
        assert not any(configured[grant] for grant in ('implicitFlowEnabled', 'directAccessGrantsEnabled', 'serviceAccountsEnabled'))
        assert send(path + '/users', bearer=token) == users_before
        for user in users_before:
            role_ids = {role['id'] for role in send(path + '/users/' + user['id'] + '/role-mappings/realm', bearer=token)}
            assert role_ids == roles_before[user['id']] | {offline_role['id']}
        assert {
            user['id']: send(path + '/users/' + user['id'] + '/groups', bearer=token)
            for user in users_before
        } == memberships_before
    print('PASS: two shared-client upgrades preserve users, subjects, memberships and unrelated clients; legacy clients are disabled.')
except urllib.error.HTTPError as failure:
    raise SystemExit(f'Disposable migration HTTP {failure.code} at {urllib.parse.urlsplit(failure.url).path}; no response body printed.') from None
except Exception as failure:
    raise SystemExit(f'Disposable migration test failed ({type(failure).__name__}); no credentials or response bodies printed.') from None

finally:
    forwarder.shutdown()
    forwarder.server_close()
