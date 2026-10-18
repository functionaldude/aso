# 012 — MCP client-ID audience compatibility

Status: in progress

## Dependencies and prerequisites

[Goal 011](011-codex-onboarding.md), Java 25, Docker, and the existing shared public OIDC registration. Live diagnostic credentials stay outside the repository.

## Objective

Allow Codex to connect when its provider issues access tokens for the shared OIDC client ID instead of the MCP resource URL, preserving the [identity boundary](../../spec.md#identity-and-private-access).

## Included and excluded work

Accept either configured audience, retain ID-token rejection, update administrator documentation, and verify the packaged application. No provider registration changes, client secret, database migration or data reset.

## Acceptance criteria

- Signed access tokens with either exact configured audience authenticate the same individual; unrelated audiences still fail.
- ID tokens cannot authenticate MCP even with an accepted audience. Missing, malformed or unrelated scope claims fail.
- Issuer, signature, lifetime, subject, membership, browser-only actions and CSRF checks remain enforced.
- Real provider-issued client-ID tokens pass the application boundary, and the existing resource-audience workflows remain covered.

## Verification

Run `check bootJar`, `oidcSmokeTest`, `integrationTest`, `e2eTest`, and an application image build/smoke test. Check actual Codex discovery/identity against the live installation when available. Record unavailable checks honestly.

## Material decisions

On 2026-10-18 the owner explicitly requested accepting both `ASO_MCP_AUDIENCE` and `ASO_OIDC_CLIENT_ID`. Authentik issued a valid access token whose audience was only the configured shared client ID; the previous validator returned 401 despite successful CLI OAuth login. Codex consequently exposed the configured server in settings but no usable tools.

Audience matching remains exact. Because browser ID tokens also name the shared client ID, MCP additionally requires `openid` in a signed, space-delimited access-token `scope` string. The provider contract keeps this claim out of ID tokens. Keycloak and the observed Authentik tokens provide this distinction. This change accepts the shared application's access tokens; it does not identify agent software or prove human presence. Browser-session authorization remains separate.

## Progress and blockers

Implementation is on `codex/mcp-client-id-audience`. Unit, OIDC and integration checks pass; packaged browser and image verification remain in progress.

## Completion evidence

The pre-fix live MCP `initialize` request returned HTTP 401 with a legitimately signed Authentik access token containing the configured client-ID audience, `openid` scope and member group. No token or personal claims are retained. Unit tests cover both accepted audiences and arrays, unrelated/prefix audiences, invalid issuer/signature/expiry/subject, and missing/malformed/unrelated scopes. The real Keycloak integration fixture now issues client-ID-only access tokens for two members, an admin and a non-member; successful identities, outsider rejection, ID-token rejection and browser-only boundaries pass. Other integration/browser fixtures retain the resource audience.

Remaining verification and pull request will be recorded after completion.
