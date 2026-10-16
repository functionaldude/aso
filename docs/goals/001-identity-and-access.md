# 001 — Individual identity and private workspace access

Status: planned

## Dependencies and prerequisites

- [000a — Local Keycloak](000a-local-keycloak.md), including its reusable realm fixture and isolated OIDC smoke task.
- Use seeded `alice`/`bob` for independent users, `aso-admin` for administration, and `outsider` for membership denial. Configure ASO against the local issuer/clients/groups defined in the spec; no external IdP access or personal credentials are prerequisites.
- Actual Codex, Claude Code, and Cursor installations for client verification. Record versions when testing.

## Objective

Let each developer authenticate through the browser and remote MCP as the same persisted person, with private access enforced before product writes exist. See [identity and private access](../../spec.md#identity-and-private-access).

## Included and excluded work

Include provider-neutral Spring Security/OIDC integration using local Keycloak, browser login/logout/session recovery, team/admin authorization, user persistence, the first deterministic jOOQ generation workflow, and an authenticated MCP endpoint with a minimal identity operation. Reuse goal 000a's fixture; finalize and verify browser/MCP client registrations, discovery, PKCE, and resource/audience settings against actual client versions. Keep issuer, clients, expected audiences, and group mappings configurable. Exclude Q&A, embedding services, external IdP setup, and deployment.

## Acceptance criteria

1. Two seeded Keycloak users can sign in/out through the browser and obtain distinct persisted identities keyed by issuer plus subject. Display-name/email changes do not create a new user or merge different subjects. Reuse the reserved local browser callback/registration or record any deliberate fixture-contract adjustment.
2. Those same two users authenticate from actual Codex, Claude Code, and Cursor clients against local Keycloak and reach the protected MCP identity operation. Browser and MCP resolve to the same user; no shared bot credential or user-ID tool argument is needed. Verify discovery, the supported registration mechanism, PKCE, and intended resource/audience handling rather than manually injecting a token and claiming login works.
3. Invalid, expired, wrong-issuer, wrong-resource/audience, anonymous, and non-member credentials cannot access protected product operations. Ordinary members cannot assign themselves administrative privileges.
4. Record how the selected browser and MCP flows enforce human-only browser actions later, including how an MCP bearer credential is prevented from invoking them. Do not infer human presence from a request's claimed client name.
5. Browser errors/session expiry and API/MCP authorization failures have appropriate recovery/error behavior. Health remains minimal; public shell/info routes are explicitly documented.
6. Flyway creates identity data and deterministic jOOQ generation works without inspecting the development database. Standard unit/build checks still require no live IdP or database.
7. Application OIDC integration uses configurable standard protocol settings rather than a Keycloak-specific application dependency. Seed credentials/development issuer are explicit development configuration; a non-development runtime cannot silently inherit them. Document the settings and separate verification needed to use another OIDC IdP without claiming it has already been tested.

## Verification

- Run goal 000a's isolated OIDC smoke task, then `./gradlew --no-daemon check bootJar`, `./gradlew --no-daemon integrationTest`, and `./gradlew --no-daemon e2eTest`. Application integration/browser checks provision isolated Keycloak instances from the reusable fixture; unit checks remain service-free.
- Verify token/session validation, identity-key edge cases, membership/admin denial, and migration/generation reproducibility.
- Separately exercise two seeded users in the browser and each actual target MCP client against the development Keycloak. Record client versions, sanitized results, and configuration shape. A successful provider smoke test or automated protocol fixture alone does not satisfy criterion 2. Do not use/reset persistent development state in automated regression tests.

## Material decisions

- Owner requires individual identities from the start, one shared private workspace, all three target clients, and vendor-neutral terminology: **OIDC IdP** (2026-10-16).
- Application membership and admin mappings are configuration; no provider-specific product dependency or arbitrary identity override is allowed.
- Owner selected local seeded Keycloak first (2026-10-16). Real browser/MCP authentication is exercised against that local IdP; future external-provider verification is a separate step and does not block this local goal.

## Progress and blockers

Not started. Goal 000a must supply the local IdP fixture first; this goal finalizes actual client/resource configuration and verifies ASO integration. No external IdP access is required. Surface unsupported client/IdP flows before subsequent goals rely on them; do not hide a compatibility gap behind mocked tokens or a development authentication bypass.

## Completion evidence

None yet. Record checks, actual client/user results, migration/generation evidence, commit, and PR when implemented.
