# 011 — Simpler Codex onboarding

Status: Complete — implementation and local verification finished; PR open for review

## Dependencies and prerequisites

Completed [goal 010](010-public-oidc-and-setup.md), Java 25, Docker and actual Codex/MCP Jam clients with disposable identity/database fixtures.

## Objective

Make the Agent setup page a short Codex walkthrough with complete installation-specific configuration, as approved by the owner on 2026-10-18. The [spec](../../spec.md) owns product intent; [the canonical guide](../agent-setup.md) owns instructions.

## Included and excluded work

Include four-step setup, expandable advanced guidance, a standard local Codex callback in all profiles, removal of product MCP Jam configuration/instructions, fixture updates, documentation, verification and a feature-branch PR. Preserve MCP Jam as a test tool. No schema migration, data reset, callback relay, deployment or Goal Mode.

## Acceptance criteria

1. Codex setup leads with open, paste/save, sign-in and reload/identity steps. Preserve accessible copy/fallback, editor and duplicate-table guidance, the recommended paragraph, and desktop/mobile readability. Product guidance contains no MCP Jam, test history or verification badges.
2. Unset/blank `ASO_CODEX_CALLBACK_URL` resolves to `http://127.0.0.1:8766/callback` in every profile. Generate correct URL/client ID/scopes/callback and matching loopback listener ports; retain overrides without inferring a listener port from a remote proxy.
3. Public metadata always contains `codexCallbackUrl`, omits `mcpJamCallbackUrl`, and preserves other fields. Remove product MCP Jam settings/callbacks; disposable fixtures explicitly register their test callbacks.
4. Administrator guidance explains one-time exact callback registration, discovery limitations, custom/remote overrides and shared-client security boundaries. Local client updates remain idempotent and preserve subjects and data.

## Verification

Run `check bootJar`, `oidcSmokeTest`, `integrationTest`, `e2eTest`, disposable lifecycle/migration checks, application-image build/smoke tests, and actual Codex default-configuration login against disposable services. Retain separate MCP Jam interoperability verification. Inspect setup screens at desktop/mobile sizes.

## Material decisions

- The owner selected a standard loopback callback default, with explicit custom/remote overrides.
- Browser and Codex callbacks deliver results to different processes. Keep the shared public client, mandatory S256 PKCE, state/nonce, token validation, CSRF and session-based browser authorization.
- Generated configuration reflects application settings; ordinary OIDC discovery does not verify a client's registered redirect list. Another IdP needs its own interoperability verification.

## Progress and blockers

Implementation and local verification finished on `codex/goal-011-codex-onboarding`, delivered through [PR #14](https://github.com/functionaldude/aso/pull/14). Linux verification is reported by the [PR checks](https://github.com/functionaldude/aso/pull/14/checks).

## Completion evidence

Java **25.0.4.1**, Gradle **9.2.1**, provisioned Node **24.22.0** and npm **11.19.1** were used on 2026-10-18.

| Check | Observed result |
| --- | --- |
| `./gradlew --no-daemon spotlessApply frontendFormat check bootJar oidcSmokeTest integrationTest e2eTest` | Passed: 23 backend unit, 11 frontend unit, 2 OIDC smoke, 19 integration and 2 packaged browser tests; no failures, errors or skipped backend tests. |
| `./scripts/keycloak-lifecycle-test.sh` | Passed: two identical updates remove the stale Jam redirect, disable obsolete clients and preserve users/subjects/memberships, unrelated registrations, data sentinels and independent service resets. |
| `./gradlew --no-daemon :backend:bootBuildImage --imageName=aso-ci:local-011 --environment=BP_OCI_REVISION=local-011 -PimageBuildId=local-011 imageSmokeTest -PimageUnderTest=aso-ci:local-011` | Passed: local application image build and 2 image smoke tests, including absent callback environment/default API values and template alignment. |
| `./gradlew --no-daemon spotlessApply check e2eTest` | Passed after final test-locator readability cleanup and callback-port comment; no behavior changes. |
| Shell/Python syntax and `git diff --check` | Passed. |

Unit/browser coverage checks complete generated URL/client ID/scopes, blank callback handling, default and custom local ports (including explicit scheme-default ports and IPv6), remote proxy behavior, removal of Jam metadata/badges/instructions, default-collapsed help, clipboard/manual fallback and the unchanged recommended paragraph. The container test covers an unset callback; packaged E2E covers a blank callback. Authentication rejection and browser-only action checks remain in the passing OIDC/integration/browser suites.

Desktop **1440 px** and mobile **390 px** full-page screenshots were inspected at `backend/build/e2e/agent-setup-desktop.png` and `agent-setup-mobile.png`. There is no horizontal page overflow. Long textareas remain scrollable on mobile and copy their full contents.

### Actual clients with disposable services

Codex CLI **0.162.1** loaded the UI-generated TOML with the standard callback and `callback_port = 8766`; the application had **no** `ASO_CODEX_CALLBACK_URL` override. Actual `codex mcp login aso` completed browser PKCE authentication through Codex's real local port-8766 listener for Alice and Bob separately. Each `identity` tool result matched the same person's browser ID, users remained distinct and Codex logout succeeded. The diagnostic added only an identity-tool allowlist to keep the model probe harmless; it does not claim full workflow verification or deterministic unrestricted tool selection.

MCP Jam CLI **5.16.1** separately completed real listener/PKCE login, tool discovery and `identity` for both users through callbacks explicitly added to the disposable fixture. Their IDs matched the browser as well. No Jam callback is present in the normal development registration.

The probe used the packaged jar, disposable PostgreSQL/Keycloak and private temporary client configuration/credential storage. Temporary credentials, the private model-login copy and raw client diagnostics were removed. Owner configuration, persistent services and volumes were untouched. Sanitized results and automated logs remain under ignored `backend/build/e2e/goal-011/`.

Implementation commit `d827346` is delivered in [PR #14](https://github.com/functionaldude/aso/pull/14). Another IdP and remote/proxy Codex are not verified. No merge, image publication or deployment was performed.
