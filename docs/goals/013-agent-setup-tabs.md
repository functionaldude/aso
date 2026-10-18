# 013 — Agent setup tabs and refresh-token support

Status: complete — implementation and local verification; PR handoff below

## Dependencies and prerequisites

[Goal 012](012-mcp-client-id-audience.md), Java 25, Docker, and the shared public OIDC registration. Claude Code and Cursor installation/native testing are outside this milestone.

## Objective

Provide installation-specific Codex, Claude Code and Cursor desktop setup tabs, each requesting refresh-capable authorization. The [spec](../../spec.md) owns intent and the [canonical guide](../agent-setup.md) owns instructions.

## Included and excluded work

Include accessible tabs/copy controls, explicit `offline_access` requests, local Keycloak callbacks/scope upgrades, documentation, disposable verification and a feature-branch PR. Preserve existing API fields, browser scopes and bearer-token validation. No database migration, reset, external IdP administration, deployment or Goal Mode.

## Acceptance criteria

1. Codex is initially selected; all tabs support keyboard navigation, mobile layouts, copy/fallback, individual login and an identity check. Keep advanced help collapsed and the canonical repository paragraph shared.
2. Generate all examples from public installation metadata and request `openid profile email offline_access`. Use Claude's documented public-client settings with safe POSIX shell quoting and port 8767; use Cursor desktop's documented JSON/static OAuth settings and callback port 8787. Preserve Codex overrides.
3. Local Keycloak explicitly enables optional `offline_access`, grants its provider role to seeded subjects and registers browser/Codex/Claude/Cursor callbacks. Repeated upgrades preserve users, subjects, existing roles/workspace memberships, unrelated registrations and product data.
4. Real disposable PKCE login issues a refresh token; refresh authenticates the same person through MCP. Existing access tokens without `offline_access` remain accepted. Keep offline access out of resource metadata and bearer requirements.
5. Document exact callbacks, provider refresh-token configuration, reauthentication and Claude's `@AGENTS.md` import. Keep native verification limitations in evidence, not product instructions.

## Verification

Run `check bootJar`, `oidcSmokeTest`, `integrationTest`, `e2eTest`, and `scripts/keycloak-lifecycle-test.sh`. Inspect desktop/mobile screenshots for each tab, direct navigation, keyboard controls and copy/fallback behavior. Record actual results below.

## Material decisions

- The owner approved this plan on 2026-10-18 and selected Cursor desktop only. Two research subagents checked official Claude/Cursor documentation during planning.
- Public-client configuration and fixed callbacks follow [Claude MCP](https://code.claude.com/docs/en/mcp) and [Cursor MCP](https://cursor.com/docs/mcp). Claude's canonical instruction import follows [its memory documentation](https://code.claude.com/docs/en/memory#share-one-file-with-other-coding-tools).
- `offline_access` is a client authorization request, not a resource-access requirement; follow [MCP refresh-token guidance](https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization). [Authentik](https://docs.goauthentik.io/add-secure-apps/providers/oauth2/) additionally requires its offline-access mapping.

- The first real offline grant failed with Keycloak’s “Offline tokens not allowed for the user or client”: imported fixture users lacked its required realm role. The seed now declares that role; the upgrade helper adds fixture-declared roles only to existing stable seeded subjects, preserving other roles and all workspace groups. See [Keycloak offline-access requirements](https://www.keycloak.org/docs/26.5.7/server_admin/index.html#_offline-access).

## Progress and blockers

Implementation and all required local verification are finished on `codex/goal-013-agent-setup`. Native Claude Code/Cursor interoperability remains an explicit limitation of this documentation milestone.

## Completion evidence

Java **25.0.4.1**, Gradle **9.2.1**, provisioned Node **24.22.0** and npm **11.19.1** were used on 2026-10-18.

| Check | Observed result |
| --- | --- |
| `check bootJar` (with `spotlessApply frontendFormat` during implementation) | Passed: 25 backend unit tests, 13 frontend tests, lint/format checks and production packaging. |
| `oidcSmokeTest` and `integrationTest` stages of the full verification command | Passed: 2 OIDC and 20 integration tests, including actual offline-token issuance and refresh for Alice and Bob. |
| `./gradlew --no-daemon spotlessApply check bootJar e2eTest` | Passed: both packaged browser suites, keyboard tab selection, public metadata, per-client copy, fallback, and shell/JSON round-trip checks. Final recapture from the top of each page also passes. |
| `./scripts/keycloak-lifecycle-test.sh` | Passed: two upgrades restore optional offline scope/seeded role and all callbacks, preserving other roles, workspace groups, subjects, unrelated clients, product-data sentinels and independent service reset behavior. |
| Python syntax, realm JSON, canonical paragraph comparison and `git diff --check` | Passed. |

The first combined verification exposed missing seeded Keycloak offline-role grants, corrected as described above. A later browser failure came from the test's clipboard-denial override being lost on reload; an initialization script now preserves that deliberate denial and both browser clipboard success and manual fallback pass. Early test compilation/registration-contract errors were corrected before the passing checks. No failed prerequisite was skipped or counted as success.

The production build passes with a nonfatal initial-bundle warning: **501.34 kB** against the **500 kB** warning budget (the error threshold remains unchanged). No dependency or lockfile change was needed.

The refresh regression obtains its scopes from the public setup API, uses real browser authorization-code/S256 PKCE exchanges, refreshes without a new login, and verifies the same persisted MCP identity for two users. Ordinary legacy access tokens without offline access remain accepted. Resource metadata retains `openid profile email`; the existing issuer/audience/ID-token/membership and browser-session boundaries remain covered.

Packaged browser tests parse the generated Cursor JSON and feed a generated Claude command containing synthetic quotes, backslashes, shell substitutions and environment-variable syntax through `/bin/sh` argument parsing without running Claude. The resulting JSON retains the exact original public client ID and configured MCP URL. Keyboard arrows/Enter switch tabs, and clipboard denial selects each full configuration for manual copying.

Desktop **1440 px** and mobile **390 px** screenshots were visually inspected and are retained under ignored `backend/build/e2e/` as `agent-setup[-claude|-cursor]-desktop.png` and `agent-setup[-claude|-cursor]-mobile.png`. All tabs fit the page without horizontal overflow; long readonly fields remain internally scrollable. The canonical repository paragraph is unchanged.

Claude Code/Cursor setup follows the official sources above. Neither native client was installed or exercised, and their ASO interoperability remains **unverified**. The Keycloak refresh test does not claim either native client's behavior. No persistent development realm/database, external IdP, owner client configuration or live deployment was changed.

Delivery: feature-branch PR pending creation. Sanitized verification logs are retained under ignored `backend/build/e2e/goal-013/`; the full-run log retains the corrected browser-fixture failure and the final run records passing verification.
