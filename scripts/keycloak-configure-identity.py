"""Development-only shared public-client upgrade. Never resets the realm, users, or product database."""
import json
import os
import re
import urllib.parse
import urllib.request

base = f"http://127.0.0.1:{os.environ.get('ASO_KEYCLOAK_PORT') or '8180'}"


def send(path, data=None, method=None, bearer=None):
    headers = {}
    if bearer:
        headers['Authorization'] = 'Bearer ' + bearer
    if isinstance(data, (dict, list)):
        data = json.dumps(data).encode()
        headers['Content-Type'] = 'application/json'
    elif isinstance(data, bytes):
        headers['Content-Type'] = 'application/x-www-form-urlencoded'
    request = urllib.request.Request(base + path, data=data, headers=headers, method=method)
    with urllib.request.urlopen(request, timeout=15) as response:
        body = response.read()
        return json.loads(body) if body else None


# Provider bootstrap administration only, never an ASO user/password authentication path.
form = urllib.parse.urlencode({
    'grant_type': 'password', 'client_id': 'admin-cli',
    'username': os.environ.get('ASO_KEYCLOAK_ADMIN') or 'idp-admin',
    'password': os.environ.get('ASO_KEYCLOAK_ADMIN_PASSWORD') or 'idp-admin-local-only',
}).encode()
try:
    token = send('/realms/master/protocol/openid-connect/token', form)['access_token']
    with open('dev/keycloak/aso-dev-realm.json', encoding='utf-8') as fixture:
        text = fixture.read()
    # Resolve the same labelled development defaults Keycloak would resolve during initial import.
    text = re.sub(r'\$\{([^:}]+):([^}]+)\}', lambda m: os.environ.get(m[1]) or m[2], text)
    seed = json.loads(text)
    clients = seed['clients']
    path = '/admin/realms/aso-dev/clients'
    scopes = {scope['name']: scope['id'] for scope in send('/admin/realms/aso-dev/client-scopes', bearer=token)}
    for client in clients:
        matches = send(path + '?clientId=' + urllib.parse.quote(client['clientId']), bearer=token)
        if matches:
            current = send(path + '/' + matches[0]['id'], bearer=token)
            current.update(client)
            # A previously confidential registration must not retain a configured secret.
            current.pop('secret', None)
            send(path + '/' + current['id'], current, 'PUT', token)
        else:
            send(path, client, 'POST', token)
        client_id = send(path + '?clientId=' + urllib.parse.quote(client['clientId']), bearer=token)[0]['id']
        # Client updates do not reliably replace scope associations; bind the built-in scope explicitly.
        for scope in client.get('optionalClientScopes', []):
            send(path + '/' + client_id + '/optional-client-scopes/' + scopes[scope], method='PUT', bearer=token)
        print('Configured local public client:', client['clientId'])
    # Seeded imports need an explicit mapping for the realm's offline-access role.
    # Add only fixture-declared roles to existing seeded subjects; preserve all other mappings.
    existing_users = {user['id'] for user in send('/admin/realms/aso-dev/users?max=1000', bearer=token)}
    for user in seed['users']:
        if user['id'] in existing_users:
            roles = [send('/admin/realms/aso-dev/roles/' + urllib.parse.quote(role, safe=''), bearer=token) for role in user.get('realmRoles', [])]
            if roles:
                send('/admin/realms/aso-dev/users/' + user['id'] + '/role-mappings/realm', roles, 'POST', token)
    # Only known obsolete development fixtures are disabled; unrelated registrations are untouched.
    for obsolete in ('aso-web', 'aso-codex', 'aso-mcpjam', 'aso-mcp-test', 'aso-dev-probe'):
        matches = send(path + '?clientId=' + urllib.parse.quote(obsolete), bearer=token)
        for match in matches:
            current = send(path + '/' + match['id'], bearer=token)
            if current.get('enabled', True):
                current['enabled'] = False
                send(path + '/' + current['id'], current, 'PUT', token)
                print('Disabled obsolete local client:', obsolete)
except Exception as failure:
    # Never print an HTTP response body, token, password, or credential-bearing request.
    raise SystemExit(f'Local Keycloak client configuration failed ({type(failure).__name__}). Check service readiness and bootstrap credentials.') from None
print('Existing subjects and product data preserved; no reset performed.')
