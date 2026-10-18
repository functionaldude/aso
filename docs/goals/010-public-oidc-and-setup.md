# 010 — One public OIDC client and simpler agent setup

Status: in progress

## Dependencies and prerequisites

- Completed identity, agent setup, UI, and image packaging work through goals 001, 006, 008, and 009.
- Java 25, Docker, actual Codex and MCP Jam CLI, and disposable Keycloak/PostgreSQL fixtures.

## Objective

Use one public OIDC registration for browser, Codex, and MCP Jam sign-in and provide complete, copyable Codex setup instructions. The owner approved this plan and a clean configuration switch on 2026-10-18.

## Included and excluded work

Include shared public-client authentication/configuration, a non-destructive local registration upgrade, updated fixtures, the public setup API/UI, canonical documentation, container settings, regression checks, live client verification, and a feature-branch PR. Product schema changes, deployment, other IdP/native-client compatibility claims, and Goal Mode are outside scope.

## Acceptance criteria

1. Browser and MCP use `ASO_OIDC_CLIENT_ID`, without a client secret or per-tool client-ID settings. Browser code flow retains S256 PKCE, sessions, state/nonce, CSRF and logout; issuer/audience and browser-only action boundaries remain enforced.
2. The development seed has one public `aso` application client with exact browser/Codex/MCP Jam redirects, groups and the MCP access-token audience. Auxiliary clients exist only in disposable tests.
3. The local upgrade is idempotent, disables obsolete fixture-managed registrations, preserves subjects/users and both persistent volumes, and is verified twice against disposable legacy state.
4. Public setup metadata exposes one client ID and nullable tool callbacks. The web guide and canonical Markdown explain opening/editing Codex configuration, include scopes and matching loopback listener ports, separate login/logout, and show a marked callback placeholder when unconfigured. Copy/fallback and desktop/mobile layouts work.
5. Runtime/container documentation describes the clean setting migration and external registration conversion without resetting or merging product identities. Required checks and actual Codex/MCP Jam verification have recorded results.

## Verification

Run `check bootJar`, `oidcSmokeTest`, `integrationTest`, `e2eTest`, disposable migration/lifecycle checks, `bootBuildImage`, and `imageSmokeTest`. Verify two users through the shared client using actual Codex and MCP Jam with disposable services/private configuration. Inspect rendered setup screenshots and retain only sanitized evidence.

## Material decisions

- One public registration is an explicit owner choice; its client ID does not establish human presence. Existing browser-session-only acceptance/moderation rules remain authoritative.
- Clean switch: `ASO_OIDC_CLIENT_ID` replaces browser and per-tool client-ID settings; there are no compatibility aliases or client secrets. Each tool retains its callback setting.
- No schema migration or data reset. External providers must retain stable issuer/subject identities and receive separate compatibility verification.

## Progress and blockers

Implementation is complete on `codex/goal-010-public-oidc`. Automated local checks, image smoke tests, and disposable migration/lifecycle checks pass. Actual Codex/MCP Jam checks pass for Alice and Bob with the same public registration. PR handoff is being finished.

## Completion evidence

All commands use Java 25.0.4.1 and the Gradle 9.2.1 wrapper. Local automated results on 2026-10-18:

| Verification | Result |
| --- | --- |
| `./gradlew --no-daemon spotlessApply check bootJar` | Passed: 23 backend unit tests and 8 frontend tests, lint, formatting and production packaging. |
| `./gradlew --no-daemon check bootJar oidcSmokeTest integrationTest e2eTest` | Passed: 2 OIDC smoke tests, 19 integration tests and 2 packaged browser suites; zero failures/errors/skips. The final default check above additionally covers two public-metadata regressions. |
| `./scripts/keycloak-lifecycle-test.sh` | Passed: two real upgrades from disposable legacy registrations, preserved subjects/memberships and unrelated clients, database/Keycloak data sentinels, restart persistence and independent reset guards. The test forwards its local admin endpoint so the same helper can run on the containerized CI host. |
| `./gradlew --no-daemon :backend:bootBuildImage --imageName=aso-ci:local-010 --environment=BP_OCI_REVISION=local-010 -PimageBuildId=local-010 imageSmokeTest -PimageUnderTest=aso-ci:local-010` | Passed: native local application image and 2 smoke tests, including runtime/template alignment and startup rejection for each of the seven required identity settings. No browser client secret is supplied. |
| `git diff --check`, shell/Python syntax | Passed. |

The real shared-client tests preserve browser/MCP identity equality, per-user limits, membership/admin rules, logout/reauthentication and browser-only action boundaries. They reject ID-token bearer use, wrong issuer/audience/signature, outsiders, incorrect PKCE, missing PKCE and unregistered callbacks. Keycloak reports missing PKCE as an OAuth error redirect to the valid callback; the smoke test asserts the error and absence of an authorization code.

Packaged Chromium checks exercise the new response shape, configured backend origin, generated scopes/shared ID, configuration clipboard contents, separate login, paragraph copy/fallback, direct navigation/refresh and mobile reflow. Desktop/mobile screenshots under ignored `backend/build/e2e/agent-setup-*.png` were visually inspected. The canonical recommended paragraph is unchanged.

Initial fixture preparation reused provider-generated mapper IDs and failed; the legacy-state test now strips those generated IDs. An initial smoke assertion expected HTTP 400 for missing PKCE; it now verifies the actual OAuth rejection contract. These initial runs were not counted as successful checks.

### Actual client verification

Codex CLI **0.162.1** and MCP Jam CLI **5.16.1** (Gradle-provisioned Node **24.22.0**) used the actual executable jar with disposable PostgreSQL and Keycloak. Browser, Codex and MCP Jam all used the same public `aso` registration and `openid profile email`. Exact tool callbacks were registered on that client with temporary loopback ports; no client secret or password grant authenticated an ASO user.

For Alice and Bob separately, MCP Jam completed browser PKCE login using its real callback listener, listed tools, and called `identity`; its returned ID matched that person's browser ID. Codex loaded the UI-generated TOML, completed real PKCE login with its supported `--no-browser` manual-callback mode, called `identity` through `codex exec`, and logged out. Both users retained distinct IDs matching the browser.

The Codex probe additionally allowlisted only `identity`. An initial unrestricted model run selected `create_question` with empty arguments instead of the requested identity tool; ASO rejected it for a missing request ID and created no content. Restricting the diagnostic tool catalog kept the connection probe harmless; this is not a claim of deterministic model tool selection. A separate harness stdin timeout was corrected before the successful run.

The probe used private temporary Codex configuration/credential storage and separate private MCP Jam credential files. Its copied model-login credential and all OAuth credentials/raw client diagnostics were removed after verification. Only sanitized results remain under ignored `backend/build/e2e/goal-010/live-clients.txt`; automated command logs are retained alongside them. Owner configuration, local realm, product database and volumes were not changed.

Another IdP remains unverified; no native Claude Code/Cursor claim is added. PR/CI links follow at handoff.
