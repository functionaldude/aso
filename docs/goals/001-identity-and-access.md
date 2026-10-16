# 001 — Individual identity and private workspace access

Status: verified; PR handoff pending

## Dependencies and prerequisites

- [000a — Local Keycloak](000a-local-keycloak.md), including its reusable realm fixture and isolated OIDC smoke task.
- Use seeded `alice`/`bob` for independent users, `aso-admin` for administration, and `outsider` for membership denial. Configure ASO against the local issuer/clients/groups defined in the spec; no external IdP access or personal credentials are prerequisites.
- Codex CLI and MCP Jam CLI for this milestone, per the owner's 2026-10-16 scope amendment. Claude Code and Cursor are not installed and are explicitly untested. Record actual tested versions.

## Objective

Let each developer authenticate through the browser and remote MCP as the same persisted person, with private access enforced before product writes exist. See [identity and private access](../../spec.md#identity-and-private-access).

## Included and excluded work

Include provider-neutral Spring Security/OIDC integration using local Keycloak, browser login/logout/session recovery, team/admin authorization, user persistence, the first deterministic jOOQ generation workflow, and an authenticated MCP endpoint with a minimal identity operation. Reuse goal 000a's fixture; finalize and verify browser/MCP client registrations, discovery, PKCE, and resource/audience settings against actual client versions. Keep issuer, clients, expected audiences, and group mappings configurable. Exclude Q&A, embedding services, external IdP setup, and deployment.

## Acceptance criteria

1. Two seeded Keycloak users can sign in/out through the browser and obtain distinct persisted identities keyed by issuer plus subject. Display-name/email changes do not create a new user or merge different subjects. Reuse the reserved local browser callback/registration or record any deliberate fixture-contract adjustment.
2. Those same two users authenticate from actual Codex and MCP Jam clients against local Keycloak and reach the protected MCP identity operation. The owner explicitly replaced Claude Code/Cursor verification for this goal on 2026-10-16; their compatibility remains unverified. Browser and MCP resolve to the same user; no shared bot credential or user-ID tool argument is needed. Verify discovery, the supported registration mechanism, PKCE, and intended resource/audience handling rather than manually injecting a token and claiming login works.
3. Invalid, expired, wrong-issuer, wrong-resource/audience, anonymous, and non-member credentials cannot access protected product operations. Ordinary members cannot assign themselves administrative privileges.
4. Record how the selected browser and MCP flows enforce human-only browser actions later, including how an MCP bearer credential is prevented from invoking them. Do not infer human presence from a request's claimed client name.
5. Browser errors/session expiry and API/MCP authorization failures have appropriate recovery/error behavior. Health remains minimal; public shell/info routes are explicitly documented.
6. Flyway creates identity data and deterministic jOOQ generation works without inspecting the development database. Standard unit/build checks still require no live IdP or database.
7. Application OIDC integration uses configurable standard protocol settings rather than a Keycloak-specific application dependency. Seed credentials/development issuer are explicit development configuration; a non-development runtime cannot silently inherit them. Document the settings and separate verification needed to use another OIDC IdP without claiming it has already been tested.

## Verification

- Run goal 000a's isolated OIDC smoke task, then `./gradlew --no-daemon check bootJar`, `./gradlew --no-daemon integrationTest`, and `./gradlew --no-daemon e2eTest`. Application integration/browser checks provision isolated Keycloak instances from the reusable fixture; unit checks remain service-free.
- Verify token/session validation, identity-key edge cases, membership/admin denial, and migration/generation reproducibility.
- Separately exercise two seeded users in the browser and each actual in-scope MCP client against the development Keycloak. Record client versions, sanitized results, and configuration shape. A successful provider smoke test or automated protocol fixture alone does not satisfy criterion 2. Do not use/reset persistent development state in automated regression tests.

## Material decisions

- Owner requires individual identities from the start, one shared private workspace, all three target clients, and vendor-neutral terminology: **OIDC IdP** (2026-10-16).
- Application membership and admin mappings are configuration; no provider-specific product dependency or arbitrary identity override is allowed.
- Owner selected local seeded Keycloak first (2026-10-16). Real browser/MCP authentication is exercised against that local IdP; future external-provider verification is a separate step and does not block this local goal.

- Owner amended client scope on 2026-10-16: Claude Code/Cursor are not installed, must not be tested here, and MCP Jam CLI is preferred. Retain their unverified status for later all-client work.
- Persist only `(issuer, subject)` identity and mutable profile attributes; derive roles from signed claims at the adapter boundary. A unique key plus PostgreSQL upsert handles concurrent first login.
- Browser uses confidential code flow with explicit S256 PKCE, state, nonce and RP-initiated logout; protected browser service checks ID-token expiry. MCP uses a separate stateless JWT access-token chain with exact issuer/resource audience validation. Future human-only handlers must require a browser session and CSRF, never claimed client names.
- Use offline Boot-managed jOOQ DDLDatabase generation from V2 identity DDL into committed Java sources. Ordinary builds never inspect a database; CI verifies regeneration drift.
- Keep explicit development-only configuration in a selected Spring profile; base runtime has no identity defaults. Existing local realm updates are additive, not resets.
- Minimal MCP uses stateless Streamable HTTP/JSON, protocol 2025-11-25. Pre-registered public PKCE clients have exact callbacks and ASO-only audience mappers. This follows [Keycloak's documented non-experimental audience-mapper approach](https://www.keycloak.org/securing-apps/mcp-authz-server); full RFC 8707/HTTPS OAuth conformance is not claimed for local HTTP.

## Progress and blockers

Implementation and the owner-amended client verification are complete. All required regressions passed; PR handoff is pending. No goal 002 implementation is included. Claude Code/Cursor are deliberately untested per the owner's instruction; no compatibility is claimed. MCP Jam's strict OAuth conformance rejects the HTTP loopback resource at RFC 9728 metadata validation; separate real login and protocol evidence is recorded below, and full HTTPS OAuth conformance is not claimed.


## Completion evidence

Verified on 2026-10-16 on the owner's ARM64 Mac, Java 25.0.4.1, Gradle 9.2.1, Docker Desktop engine 29.8.2. Feature branch `codex/001-identity-and-access` starts from `c6be8e4`, the merged completed Keycloak setup. The initial worktree was clean.

| Criterion | Recorded evidence |
| --- | --- |
| 1 — Individual browser identity | Executable-jar Chromium signs in/out as Alice, Bob, ASO admin, and outsider using real isolated Keycloak code/S256-PKCE login. Alice/Bob/admin persist distinct UUIDs; outsider receives 403. Storage tests retain the ID after name/email changes, keep colliding names/email and different issuers/subjects separate, and resolve concurrent first access to one row. Separate manual browser checks below match the real MCP clients. |
| 2 — In-scope actual MCP clients | Codex CLI 0.162.1 and MCP Jam CLI 5.16.1 each complete real code/PKCE login and call `identity` for Alice and Bob against development Keycloak. See IDs and configuration below. Claude Code/Cursor were explicitly replaced by MCP Jam for this goal and remain unverified. No token was manually injected to claim a login. |
| 3 — Credential and privilege denial | Signed-token unit checks reject expiry, wrong issuer/audience, missing subject, malformed token and incorrect signing key. Isolated HTTP integration checks reject anonymous, outsider, probe-audience, ID-token and corrupted-signature requests; bearer tokens cannot access browser identity/admin routes, even for the admin. Browser tests deny ordinary members the admin endpoint and reject CSRF-free role payloads. Origin/query-token denial regressions are included. |
| 4 — Human browser boundary | Separate stateless bearer-only `/mcp` and session-only web chains; session cookies cannot authenticate MCP and bearer credentials cannot authenticate browser handlers. Shared identity/admin service enforces trusted claims. Future acceptance/moderation must use browser-only CSRF-protected handlers and no MCP tools. This enforces a credential/transport boundary, not attested human presence. |
| 5 — Errors and recovery | Packaged UI tests cover invalid callback/state recovery, logout including IdP SSO removal, lost-session sign-in recovery with stable identity, membership guidance, minimal health/public shell, authenticated unknown-API JSON 404 and missing-asset 404. Unit tests reject an expired ID token despite a live servlet session; frontend tests clear stale identity on 401 and recover on refresh, and distinguish denial/outage. |
| 6 — Migrations and generation | Applied new additive Flyway V2 on isolated databases and the preserved development database. V1 is unchanged. `generateJooq` twice produces the same SHA-256 hashes for all six committed Java sources, offline from V2 DDL. Normal builds compile committed sources without introspection. CI regenerates and checks drift. |
| 7 — Provider-neutral explicit configuration | Spring Security standard OIDC/OAuth client/resource-server support, Boot-managed versions, no Keycloak application dependency. Eight identity settings are mandatory outside explicitly selected `development`; HTTPS is required except loopback. README documents signed groups, endpoints, audience, client/callback registration, logout, expiry and separate verification for another IdP. |

Actual client results (fictitious local accounts; sanitized IDs only):

| User | Browser `/api/v1/identity` | Codex `identity` | MCP Jam `identity` | Subject suffix |
| --- | --- | --- | --- | --- |
| Alice | `4319abd8-84fe-489a-b2cc-01484d96b939` | same ID, channel `mcp` | same ID, channel `mcp` | `000000000001` |
| Bob | `e956a716-d680-4c7a-9ca4-e30d8927dbe2` | same ID, channel `mcp` | same ID, channel `mcp` | `000000000002` |

Browser channels were `web`; both used the exact issuer `http://127.0.0.1:8180/realms/aso-dev`. Codex actually invoked `aso_goal001.identity` / `aso_goal001_bob.identity` in narrow read-only ephemeral CLI runs, after real `codex mcp login`. No implementation was delegated to those client runs. MCP Jam `oauth login --verify-tools --verify-call-tool identity` completed for each user, followed by `tools call --validate-response --expect-success`, returning the IDs above and `isError=false`. Each saw exactly one tool. Resource discovery produced 401 challenge → protected-resource metadata 200 → authorization-server metadata 200 → real code/PKCE token exchange 200 → authenticated MCP 200. Client-side credential caches/private temporary files were not committed.

The exact callback registrations are `http://127.0.0.1:8766/callback` for `aso-codex`, `http://localhost:6274/oauth/callback` for `aso-mcpjam`, and an explicitly substituted dynamic loopback callback for `aso-mcp-test`. All three are public authorization-code/S256-PKCE clients without password/implicit/service-account grants. `aso-web` retains its reserved callback, with an exact post-logout URI added. Pre-registration is used; anonymous DCR permissions are not opened. Both real clients sent the canonical ASO `resource` in their observed authorization requests; MCP Jam diagnostics also record it in the token exchange. The fixture's audience mapper binds these registrations to ASO; Keycloak's experimental resource-indicators feature is not enabled and arbitrary-resource support is not claimed.

Required commands observed passing (reports stay untracked):

- `./gradlew --no-daemon oidcSmokeTest`: two tests, no skips; all four fixture identities, signatures/JWKS, issuer/audience/nonce/userinfo and incorrect-PKCE rejection.
- `./gradlew --no-daemon check bootJar`: seven backend policy/validation/route tests, four Angular tests, lint/formatting, production build and executable jar; no service required by the task graph.
- `./gradlew --no-daemon integrationTest`: four passing tests with isolated Keycloak/PostgreSQL migrations, identity and HTTP security checks; no local database or `.env`.
- `./gradlew --no-daemon e2eTest`: one passing end-to-end suite, Chromium against the executable jar, disposable Keycloak and pgvector database; real browser login/logout, admin/member/outsider and session/bearer boundaries.
- `./gradlew --no-daemon generateJooq` twice: six sources byte-identical. CI includes regeneration plus `git diff --exit-code -- backend/src/generated`.
- `bash -n scripts/keycloak-configure-identity.sh`, Python syntax compilation and `git diff --check`: pass.

The additive `./scripts/keycloak-configure-identity.sh` updated only the local browser logout and dedicated MCP clients. It preserved existing users/subjects, the probe, and both persistent volumes. No development reset was run. Automated regression state used disposable containers. Authentication traces are disabled to avoid credentials/callbacks in artifacts; public-shell screenshots and sanitized app logs remain under `backend/build/e2e`, with test reports under `backend/build/reports/tests` and `backend/build/test-results`.

Additional MCP Jam protocol evidence: the final jar scored 100/100, with all 15 applicable 2025-11-25 checks passed. Initial metadata quality advice identified a missing tool title, which was added and verified in the final run. Nine checks for other profiles were pending/unscored; this is not a claim of 2026 protocol support. The separate strict OAuth conformance command failed before login because its RFC 9728 checker requires an HTTPS resource URI even for loopback (2/3 applicable checks passed). The real HTTP-loopback OAuth login/tool flows above passed independently. No failed conformance check is reported as a pass or mocked away. An early interactive run also timed out during password-manager/UI handling; rerunning the real flow completed successfully.

Commit and PR: pending final handoff. No merge or deployment is authorized.
