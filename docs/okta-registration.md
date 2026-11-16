# Okta org registration

ASO supports an explicit `okta-org` MCP token mode for installations using Okta's org authorization server. Browser login remains standard OIDC authorization code with S256 PKCE, state/nonce, signature/issuer/client-audience checks, CSRF and browser-only administrative actions. The default `jwt` mode and local Keycloak setup are unchanged.

## Register the shared public application

Create one public OIDC application with **Client authentication: None**, mandatory S256 PKCE, authorization code and refresh-token grants. Disable implicit/password/service-account grants. Assign only the intended Engineering group to the application. No client secret or Okta management API credential is needed by ASO.

Register exact callbacks for the enabled clients:

- Browser: `https://aso-39737082673.europe-west1.run.app/login/oauth2/code/oidc`
- Codex: `http://127.0.0.1:8766/callback`
- Claude Code: `http://localhost:8767/callback`
- Cursor desktop: `http://localhost:8787/callback`
- Post-logout: `https://aso-39737082673.europe-west1.run.app/?signedOut`

Replace the ASO origin if using another installation. Custom Codex callbacks must match `ASO_CODEX_CALLBACK_URL` exactly.

On the application's **Sign On** tab, configure an ID-token group claim named `groups`, filtered by `^Engineering$`. Allow/request `openid profile email groups`; MCP additionally requests `offline_access` for refresh. Okta's [group-claim guide](https://developer.okta.com/docs/guides/customize-tokens-groups-claim/main/) explains that org access tokens do not contain configurable groups, while `/userinfo` returns the configured claims. Verify that endpoint actually returns `groups: ["Engineering"]` for this application before enabling access.

## Runtime configuration

```dotenv
ASO_OIDC_ISSUER=https://prewave.okta.com
ASO_OIDC_CLIENT_ID=REPLACE_ME_PUBLIC_ASO_CLIENT_ID
ASO_OIDC_MCP_TOKEN_MODE=okta-org
ASO_PUBLIC_URL=https://aso-39737082673.europe-west1.run.app
ASO_MCP_AUDIENCE=https://aso-39737082673.europe-west1.run.app/mcp
ASO_OIDC_GROUPS_CLAIM=groups
ASO_OIDC_ADMIN_GROUP=Engineering
ASO_OIDC_MEMBER_GROUP=aso-members
```

Use the org issuer origin with no trailing slash or `/oauth2/default` path. Keep `aso-members` unassigned and outside the emitted group claim; Engineering alone grants workspace access and admin privileges. The member setting remains a required, distinct configuration value, without requiring a member group to be created. In `jwt` mode, `ASO_MCP_AUDIENCE` remains a validation requirement; in org mode it is not used to validate token audiences.

When combined with the Cloud Run deployment configuration, set these non-secret Terraform inputs in the ASO checkout's ignored `infra/gcp/terraform.tfvars`:

```hcl
oidc_issuer       = "https://prewave.okta.com"
oidc_client_id    = "REPLACE_ME_PUBLIC_ASO_CLIENT_ID"
oidc_admin_group  = "Engineering"
oidc_member_group = "aso-members"
runtime_environment = {
  ASO_OIDC_MCP_TOKEN_MODE = "okta-org"
}
```

The org mode does not need a custom authorization server, its access policy, its claims, or a resource audience configured in Okta. Infrastructure must register the public application and its app-level group claim rather than create unsupported custom-server resources. Application changes do not provision or apply that registration.

## What ASO verifies

For every MCP request ASO posts the opaque bearer token to [`/oauth2/v1/introspect`](https://developer.okta.com/docs/api/openapi/okta-oauth/oauth/orgas/introspect). Public-client authentication uses the configured `client_id` query parameter and no Authorization header. ASO requires `active: true`, exact issuer and ASO client ID, Bearer type, a future integer expiration, `openid` and `groups` scopes, a nonempty end-user `uid`, and valid optional issuance/not-before times. Okta documents `uid` only for end-user access tokens; ID and refresh tokens are rejected.

ASO then calls [`/oauth2/v1/userinfo`](https://developer.okta.com/docs/api/openapi/okta-oauth/oauth/orgas/userinfo) with that access token. Its `sub` must equal introspection's `uid`. Userinfo supplies the display attributes and well-formed group array. ASO persists `(configured issuer, userinfo sub)`, matching the browser's signed ID-token subject. Introspection's `sub` may be a mutable login/email and is never an identity key. Different users with the same email remain distinct. Browser and MCP requests consume the same individual rate budget.

HTTP failures, redirects, malformed/oversized responses, missing claims, subject mismatches and expired tokens fail closed. Responses are limited to 64 KiB, connect timeout is 3 seconds and each remote call has a 5-second timeout. There is no token cache: MCP requires two Okta calls per request, so Okta availability and rate limits affect MCP access. Browser userinfo/groups remain the validated login snapshot until the ID token expires or the user signs in again.

This is an installation-specific compatibility mode: it substitutes exact issuer/client binding for an MCP resource audience. It does not claim the resource-audience isolation required by the [MCP authorization profile](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization#token-handling). Keep the public registration dedicated to ASO, and do not share it with unrelated applications. Strict local JWT audience validation remains the default. Okta [documents org tokens as intended for Okta](https://developer.okta.com/docs/concepts/auth-servers/); this integration trusts Okta's remote authentication responses rather than locally interpreting those tokens.

## Installation verification

Automated HTTP fixtures and isolated PostgreSQL tests prove the application contract, not live Okta or native-client interoperability. After registering the application:

1. Sign in through the browser as an Engineering user and verify admin access. Inspect trusted local diagnostics without logging tokens; confirm the signed ID-token subject equals userinfo `sub` and introspection `uid`.
2. Configure Codex from ASO's Agent setup page, which includes `groups` in org-mode scopes. Authenticate and call `identity`; its ASO user ID and subject must match the browser. Verify that logout/login and a display-name/email change retain the same individual identity.
3. Verify a token from an unrelated client, ID/refresh-token replay, expired/revoked tokens, and an unassigned or non-Engineering user cannot gain access. Confirm browser-only administration cannot be performed through MCP.
4. Verify refresh renewal, browser logout, PKCE and every enabled native client separately. Native Claude Code/Cursor and the real tenant remain unverified until exercised.

Switching from a custom authorization server changes the issuer and therefore the ASO identity namespace. Do not switch an installation containing authored data without a deliberate identity migration; ASO does not merge users by email or reset product data.
