"""Development-only additive client update. Never resets the realm, users, or product database."""
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
    if isinstance(data, dict):
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
    clients = json.loads(text)['clients']
    for client in clients:
        if client['clientId'] == 'aso-dev-probe':
            continue
        path = '/admin/realms/aso-dev/clients'
        matches = send(path + '?clientId=' + urllib.parse.quote(client['clientId']), bearer=token)
        if matches:
            current = send(path + '/' + matches[0]['id'], bearer=token)
            if client['clientId'] == 'aso-web':
                # Preserve existing redirects/customizations; add only the logout URI used by ASO.
                current.setdefault('attributes', {})['post.logout.redirect.uris'] = client['attributes']['post.logout.redirect.uris']
            else:
                # These dedicated development registrations are managed by the fixture.
                current.update(client)
            send(path + '/' + current['id'], current, 'PUT', token)
        else:
            send(path, client, 'POST', token)
        print('Configured local client:', client['clientId'])
except Exception as failure:
    # Never print an HTTP response body, token, password, or credential-bearing request.
    raise SystemExit(f'Local Keycloak client configuration failed ({type(failure).__name__}). Check service readiness and bootstrap credentials.') from None
print('Existing subjects and product data preserved; no reset performed.')
