# 014 — Admin access and Okta compatibility

Status: complete

## Dependencies and prerequisites

[Goal 001](001-identity-and-access.md), [goal 012](012-mcp-client-id-audience.md), [goal 013](013-agent-setup-tabs.md), Java 25 and Docker for disposable authentication and browser fixtures. No external Okta tenant or credentials are needed for the automated suite.

## Objective

Allow the configured admin group to grant workspace access without the member group and support Okta's documented access-token scope format while preserving the [identity and private access contract](../../spec.md#identity-and-private-access).

## Included and excluded work

Update shared authorization and JWT scope validation, provider-neutral tests and the administrator guide. No Okta management API, API token, vendor SDK, new runtime setting, schema migration, deployment or production IdP configuration is included. Okta org-authorization-server access tokens are outside scope.

## Acceptance criteria

- Members and admins each have workspace access; only admins have admin privileges. An admin-only user has the same identity across browser and MCP and cannot use MCP for browser-only actions. Outsiders are denied before persistence.
- Access tokens accept well-formed `scope` strings and `scp` arrays containing `openid`. Both claims must match if present; malformed claims, ID tokens and invalid issuer, audience, signature, expiry or subject remain rejected.
- Existing group claim configuration and token validation remain provider-neutral. Documentation covers one-time Okta custom-server setup, signed groups in both token types, stable matching subjects, PKCE, audience, logout and refresh, without requiring management credentials.
- Unit tests use signed JWTs; disposable Keycloak and packaged browser tests exercise an admin without the member group. Existing member, both-group and outsider coverage remains.

## Verification

Run `./gradlew --no-daemon check bootJar oidcSmokeTest integrationTest e2eTest`. Record results and prerequisites honestly. Automated tests do not establish live Okta or native client interoperability; separately verify those before using an external installation.

## Material decisions

On 2026-10-27 the owner approved admin-only membership and targeted Okta compatibility. Scope arrays are accepted without a provider switch; every supplied scope claim is validated and both sets must match. The owner selected automated tests and a guide rather than requiring a live Okta tenant, and specified no Okta API tokens or management API dependency. Administrators configure Okta claims once using the normal provider console.

## Progress and blockers

Implementation and local verification are complete in [PR #17](https://github.com/functionaldude/aso/pull/17). Live Okta compatibility remains unverified; external provider and client checks remain an installation follow-up.

## Completion evidence

- `./gradlew --no-daemon spotlessApply frontendFormat`: passed.
- `./gradlew --no-daemon check bootJar oidcSmokeTest integrationTest e2eTest`: passed with Java 25 and Docker Desktop 29.8.2. Backend reports show 27 unit tests, 2 OIDC smoke tests, 20 integration tests and 2 packaged browser tests, all passing without skips; 13 frontend tests passed.
- The isolated Keycloak fixture issued ID/access tokens for an admin with only `aso-admins`. Integration checked both signed tokens and MCP identity/authorization; the packaged application checked browser/admin access and the same MCP identity. Other isolated suites retain the seeded admin with both groups.
- Signed-JWT decoder tests cover the documented `scp` array, existing `scope` string, malformed/conflicting claims, both audiences, and existing signature/issuer/expiry/subject and ID-token boundaries.
- No live Okta tenant, Okta CLI client, or external deployment was exercised; Okta interoperability is unverified and requires the checklist in the [administrator guide](../agent-setup.md#okta-administrator-setup).
- The verified implementation is commit `40431c2` on `codex/014-admin-access-and-okta`; [PR #17](https://github.com/functionaldude/aso/pull/17) targets `master`. PR CI is separate from the local results above.
