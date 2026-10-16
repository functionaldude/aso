# 000a — Seeded local development Keycloak

Status: planned

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
- Current `scripts/db-reset.sh` uses `docker compose down --volumes`. That was scoped to the database-only foundation; it must be replaced with a database-only reset when adding another persistent service.

## Progress and blockers

Not started. Select/verify the image and implement the fixture/helpers/smoke task in this goal. No external IdP access blocks it. Real ASO browser/MCP authentication belongs to goal 001; keep that verification visibly pending.

## Completion evidence

None yet. Record image/version, actual commands, smoke/login/claim results, persistence/reset-isolation evidence, regression results, commit, and PR. Never paste raw tokens or real credentials into evidence.
