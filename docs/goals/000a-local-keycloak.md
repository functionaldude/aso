# 000a — Seeded local development Keycloak

Status: complete

## Dependencies and prerequisites

- [000 — Foundation](000-foundation.md), Java 25, Docker Desktop/Compose, and the existing Gradle/Playwright tooling.
- No external IdP, personal credentials, LM Studio, or application authentication implementation is required.
- Run before [001 — Identity and access](001-identity-and-access.md). This preparatory goal does not change the completed foundation record or renumber the product goals.

## Objective

Provide a reproducible local OIDC IdP and an independent automated login smoke check so goal 001 can implement ASO identity without waiting for external infrastructure. Follow [development and test identity](../../spec.md#development-and-test-identity).

## Included and excluded work

Include a pinned official Keycloak development container, a checked-in realm/client/user fixture, lifecycle/configuration helpers, an isolated browser/OIDC smoke task, and setup documentation. Use dedicated local state separate from ASO's PostgreSQL data. Prepare reusable fixtures for later tests.

Exclude ASO Spring Security/login integration, Angular login UI, identity schema/jOOQ generation, MCP transport/client integration, product APIs, LM Studio changes, a production IdP, deployment, and backup/restore. Do not mark goal 001 complete or begin implementing it as a side effect.

## Acceptance criteria

1. A clean checkout starts healthy Keycloak through one documented root helper command, defaulting to `http://127.0.0.1:8180` and realm `aso-dev`. Pin an intentional version and immutable image digest, verify the selected image on the Mac, and keep Compose/test references aligned through a shared pin such as `gradle/keycloak-image.txt`. Bind host ports to loopback, bound memory, and keep state in a dedicated Keycloak volume using its development database.
2. Import a checked-in fixture containing `alice`, `bob`, `aso-admin`, and `outsider` with stable subject IDs, fictitious attributes, labelled local-only credentials, and the group memberships defined in the spec. The ASO admin account is distinct from the IdP bootstrap administrator. Start/restart preserves state and subjects; no manual realm clicking, MFA enrolment, or password changes are required for the fixture.
3. Configure `aso-web` and `aso-dev-probe` according to the spec. Probe login uses authorization code plus PKCE with an explicitly allowed loopback callback. Publish standard discovery/JWKS/token/userinfo endpoints and document intended test audience and claims. Do not use password grants or an ID token as an API access token. Final real MCP client registration/discovery/resource settings remain goal 001 work.
4. Add a documented explicit root task, preferably `./gradlew --no-daemon oidcSmokeTest`, that provisions its own disposable Keycloak from the same versioned fixture and automates browser login for the seeded users. Validate discovery, code exchange with PKCE, token signature via JWKS, issuer/audience, distinct subjects, member/admin claims, and the outsider's lack of membership. Include a failed/incorrect PKCE exchange. Run this check in CI as an explicit step or dependency of the Docker-backed integration task, retaining failure reports and failing honestly on unavailable prerequisites. This proves the IdP fixture works, not ASO authorization or client interoperability.
5. Test instances use isolated state and suitable dynamic ports, shut down on success/failure, and never read `.env` or use/reset the persistent development realm. The fixture works on the local Mac and is designed for the Linux self-hosted runner. Keep issuer/discovery URLs consistent across the test browser and verifier; do not weaken validation to solve networking problems.
6. Provide root start/status/stop commands and an explicitly confirmed Keycloak reset/reseed command. Document that startup import skips an existing realm and how fixture changes are deliberately applied. Update development orchestration to start Keycloak with the database, preserving the application's current unauthenticated behavior until goal 001.
7. Fix the existing broad database reset before adding persistent Keycloak state: database reset must preserve Keycloak, and Keycloak reset must preserve ASO's database. Prove this with disposable Compose projects/volumes containing sentinel data, not by deleting the owner's actual state. Starting/stopping only Keycloak must not stop or recreate the database.
8. README, `.env.example`, helper scripts, and goal evidence describe actual commands, local users, issuer, ports, configuration, cleanup, and startup-import behavior. Label provider-side bootstrap settings separately from future ASO runtime settings. No real secrets or Keycloak-specific application dependency is introduced. Normal `check bootJar` remains Docker/IdP-independent.

## Verification

- Validate shell/Compose/realm configuration and image pin alignment.
- Run the new isolated `oidcSmokeTest` (or document the chosen equivalent root task) and retain useful failure diagnostics without passwords/tokens in logs/artifacts.
- Start the development service; confirm readiness/discovery, documented users/claims, restart persistence, and service-specific stop/start behavior.
- Demonstrate reset isolation with disposable projects/volumes and record the observed results. Do not reset the owner's ASO or Keycloak data to prove the helpers work.
- Run `./gradlew --no-daemon check bootJar`, `./gradlew --no-daemon integrationTest`, and `./gradlew --no-daemon e2eTest` to catch foundation regressions. The existing default tests do not yet prove ASO login; wire application authentication in goal 001.

## Material decisions

- Owner selected a local seeded Keycloak for autonomous development/testing on 2026-10-16, and requested this setup as a separate next-chat goal.
- ASO's OIDC contract remains configurable and provider-neutral. A future external IdP is not required for the laptop MVP and cannot be declared compatible without its own check.
- [Keycloak container documentation](https://www.keycloak.org/server/containers) describes development mode and startup realm imports; [import behavior](https://www.keycloak.org/server/importExport#importing-a-realm-during-startup) explains why existing realms are not overwritten. Use these as setup references, then verify the selected version's actual behavior.
- Replaced the foundation's broad `docker compose down --volumes` database reset with removal of only the selected service and its inspected volume. Disposable lifecycle projects are the only tests allowed to use a project-wide volume cleanup.
- Selected official Keycloak 26.8.1, multi-platform image digest `sha256:79c623a22abacf3fe6c00a5fb30f5b7843d717b234033b80995891c06116d244`. Verified native ARM64 execution on Docker Desktop. Compose and isolated tests share this pin, with an automated alignment check.
- Development uses a separate H2 `dev-file` volume, loopback port 8180, private management readiness, and a 1 GiB memory limit. Isolated tests use H2 `dev-mem`, a dynamic Keycloak port, and a real OS-allocated loopback callback. The same realm fixture substitutes only that exact callback through `ASO_PROBE_CALLBACK`; development defaults to port 8765, path `/oidc/callback`. No wildcard redirects or password grants are enabled on the seeded clients.
- Keycloak's `basic` default client scope supplies the access-token subject. `profile` and `email` supply fictitious attributes; explicit group and probe-audience mappers supply membership and the standalone test audience. This is not the final ASO resource audience.
- The browser smoke check uses Java's RSA signature verification against discovery JWKS and checks issuer, audience, lifetime, nonce, subject, groups, and userinfo. No Keycloak application dependency, Spring Security, product schema, or Angular login code was added.
- The initial working tree was clean at planning commit `7983391` on `docs/mvp-spec-and-goals`; that planning work is retained unchanged as the feature branch's parent. Only goal 000a implementation and related status/command documentation are added.

## Progress and blockers

Goal 000a is complete: the provider fixture, lifecycle tooling, standalone OIDC verification, documentation, and required foundation regressions pass on the Mac. No implementation blockers remain for this goal. ASO browser/MCP authentication remains pending in goal 001.

## Completion evidence

Verified on 2026-10-16 on the owner's ARM64 Mac, Java 25.0.4.1, Docker Desktop engine 29.8.2 and Compose 5.6.0:

- Pulled the official 26.8.1 image and inspected its immutable repository digest and native `arm64` image; pin recorded above and in `gradle/keycloak-image.txt`.
- Every helper passes `bash -n` individually; `docker compose config --quiet` and the smoke task's fixture/pin contract checks pass.
- `./scripts/keycloak-start.sh` and `./scripts/keycloak-status.sh`: healthy `aso-keycloak-1` on `127.0.0.1:8180`. Discovery returns the exact documented issuer and `/auth`, `/token`, `/certs`, `/userinfo` endpoints. The existing database container remained running with the same ID.
- Stopped/started development Keycloak independently; it reported `Realm 'aso-dev' already exists. Import skipped`. Admin-console inspection after restart confirmed all four users with their expected fixed subject IDs. During implementation, assigned the corrected `basic` client scope to the two already-imported development clients without resetting the realm; final clean imports include it automatically. The persistent development database and Keycloak volumes were never reset for testing.
- `./scripts/keycloak-lifecycle-test.sh`: PASS in disposable projects using dynamic ports and no `.env`. Confirmed both deletion guards; Keycloak stop/start preserves the database container and SQL sentinel; database reset removes only its SQL sentinel while preserving Keycloak's container and file sentinel; Keycloak reset removes only its sentinel while preserving the database container and SQL sentinel. The disposable project/volumes are removed on exit.
- `./gradlew --no-daemon oidcSmokeTest`: two tests pass, no skips. Chromium signs in as `alice`, `bob`, `aso-admin`, and `outsider`, exchanges real codes with S256 PKCE, verifies RSA signatures against discovery JWKS, issuer/audience/lifetime/nonce, fixed distinct subjects, userinfo and exact groups. An incorrect verifier returns HTTP 400 `invalid_grant` specifically for PKCE failure and no access token. The dynamic callback listener and disposable container close on success and failure.
- `./gradlew --no-daemon check bootJar`, `./gradlew --no-daemon integrationTest`, and `./gradlew --no-daemon e2eTest`: PASS. Also forced fresh execution with `./gradlew --no-daemon check bootJar integrationTest e2eTest --rerun-tasks`: 23 tasks executed; backend/API unit checks, two Angular tests, lint/formatting/build/package, isolated pgvector migration/API integration, and executable-jar Chromium checks all pass.
- Reports: `backend/build/reports/tests/{test,integrationTest,e2eTest,oidcSmokeTest}/`, JUnit XML under `backend/build/test-results/`, packaged UI screenshots/trace/log under `backend/build/e2e/`, and a token-free OIDC success summary (or Keycloak failure log) under `backend/build/oidc/`. These stay untracked. CI explicitly runs the smoke and lifecycle checks and retains diagnostics.
- Initial smoke attempts failed on an intercepted redirect (connection refused), then exposed the missing `basic` subject scope. Both were corrected and verified with a real dynamic callback and the final scope configuration; no failed check is treated as a skip.
- Linux execution is designed into the existing self-hosted workflow; local Mac results do not claim an observed Linux CI result. Actual ASO authorization and MCP client compatibility remain unimplemented and unverified until goal 001.
- Implementation commit: `9867995a0579614c0a5b3ba1e902fb67f54a3c9f` on `codex/000a-local-keycloak`. Pull request: [#1 — Add seeded local Keycloak and isolated OIDC smoke verification](https://github.com/functionaldude/aso/pull/1), targeting `master` and retaining the prior planning commit. Completion evidence/status is recorded in the subsequent documentation commit on the same PR. No merge or deployment was performed.
