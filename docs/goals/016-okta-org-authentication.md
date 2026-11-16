# 016 — Okta org authentication

Status: ready for review

## Dependencies and prerequisites

[Goal 014](014-admin-access-and-okta.md), Java 25 and Docker for isolated integration/browser regression checks. Live verification requires the public ASO registration, Engineering assignment and app-level group claim in the real Okta tenant; no management credential is required by the application.

## Objective

Use the Okta org authorization server for individual browser and MCP access without a custom authorization server, with Engineering mapped to admin and the same stable person across both channels.

## Included and excluded work

Add an explicit opt-in token mode, remote MCP introspection and userinfo, stable-subject binding, trusted group mapping, shared rate limits, scope discovery/setup and registration documentation. Preserve the default provider-neutral signed-JWT mode, browser OIDC/PKCE/CSRF, and human-only administrative boundaries. No infrastructure-repository edits, Okta management API, client secret, database migration, deployment or live-tenant claim is included.

## Acceptance criteria

- MCP requires an active end-user access token, exact configured issuer and public ASO client ID, future expiry and appropriate scopes. Reject ID/refresh tokens, wrong clients/issuers, malformed responses, unavailable endpoints and inconsistent userinfo subjects.
- Userinfo supplies groups and canonical stable subject, bound to introspection `uid`. Engineering alone grants admin access. Browser and MCP persist the same issuer/subject user and consume the same rate budget; mutable or shared emails do not merge individuals.
- Browser authentication and the default JWT path preserve their existing validation. Configuration and docs explicitly state the client-binding/resource-audience tradeoff and real-tenant verification limits.
- Open an independent draft PR for review after the relevant automated checks pass.

## Verification

Run `./gradlew --no-daemon check bootJar oidcSmokeTest integrationTest e2eTest` with Java 25 and Docker. HTTP-backed unit tests use no credentials; the new org integration tests traverse Spring's opaque-token filter and isolated pgvector PostgreSQL. Existing Keycloak/browser tests retain real signed-token, code/PKCE and human-action regression coverage.

## Material decisions

On 2026-11-16 the owner requested a separate ASO PR implementing Okta introspection/userinfo, exact issuer/client/expiry validation, Engineering admins and shared individual identity. This explicitly authorizes an opt-in Okta compatibility mode instead of a general audience-disable flag. The default JWT mode remains strict. Remote responses are bounded, redirects disabled and no token cache hides revocation; two calls per MCP request make availability/rate limits an installation consideration. See [registration and validation contract](../okta-registration.md).

## Progress and blockers

Implementation and full regression verification are complete and ready for review. Draft PR creation is pending. Real Okta login/groups, token shape and native-client interoperability require registration and an interactive installation check; automated fixtures do not establish those facts.

## Completion evidence

- Focused `spotlessApply :backend:test :backend:integrationTest --tests '*OktaOrg*'` passed: 5 HTTP contract tests and 3 isolated database/Spring security tests, without skips.
- Full `spotlessApply check bootJar oidcSmokeTest integrationTest e2eTest` passed with Java 25 and Docker: 32 backend unit tests, 23 integration tests, 2 OIDC smoke tests and 2 packaged browser tests, all without failures/errors/skips; 13 frontend tests, lint, formatting and production build passed.
- Existing Keycloak suites retain actual code/PKCE browser login, signed JWT validation and browser-only administration coverage. New org-mode integration uses a protocol fixture with real Spring bearer filtering and isolated PostgreSQL, plus an already-validated browser principal for persistence comparison. It does not simulate an actual Okta sign-in.
- No production tenant, infrastructure apply or deployment was exercised.
