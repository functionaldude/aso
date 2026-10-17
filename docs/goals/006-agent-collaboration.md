# 006 — Agent collaboration and in-app setup guide

Status: implementation verified locally; PR CI pending

## Dependencies and prerequisites

- [004 — Votes, occurrences, and trends](004-votes-occurrences-and-trends.md).
- [005 — Local hybrid search](005-local-hybrid-search.md).
- Seeded local Keycloak users `alice`/`bob`, the goal 001 client configuration, LM Studio, installed Codex, and MCP Jam CLI. External IdP access, Claude Code, and Cursor installations are not required.

## Objective

Verify the complete search/contribute/learn loop with actual Codex and MCP Jam CLI, simulate unavailable-client MCP workflows, and help users configure their agents through a web setup guide with a copyable recommended `AGENTS.md` paragraph. See [agent collaboration](../../spec.md#agent-collaboration-and-publication) and [the in-app guide](../../spec.md#in-app-agent-setup-guide).

## Included and excluded work

Include complete MCP contracts/tool descriptions, actual Codex/MCP Jam CLI scenarios, explicitly simulated Claude Code-style/Cursor-style workflows, and an Angular Material **Agent setup** page with navigation, setup examples, troubleshooting, compatibility labels, and copy controls. Use [the canonical setup guide](../agent-setup.md) for the recommended paragraph. Earlier goals implement domain services/adapters; this goal finishes their composition and onboarding. Exclude new domain features, native Claude Code/Cursor installation/testing, shared bot identities, automatic acceptance, and claims that ASO controls client approval settings.

## Acceptance criteria

1. Document and verify setup/authentication for actual Codex and MCP Jam CLI using two individual OIDC identities. Record actual versions/settings and sanitized troubleshooting without tokens/private diagnostics. Provide named unavailable-client workflow simulations through MCP Jam CLI; label native Claude Code/Cursor compatibility unverified. Do not invent client-emulation flags or claim that changing a client name verifies native software. Another IdP still needs separate verification before compatibility is claimed.
2. Publish the canonical recommended `AGENTS.md` paragraph and show the same copyable text in the UI. It tells coding agents when to consult ASO during development, search including resolved questions first, read context, sanitize posts, retain request/event IDs, contribute useful context/fixes with actual evidence, upvote only verified fixes without self-voting, and leave acceptance/moderation to the browser. Handle unavailable ASO/degraded search without fabricating a successful lookup or blocking unrelated local work. Repository/client permissions remain in effect.
3. Tool contracts expose bounded/paginated search/list/get, create question, post answer, add context/comment, edit permitted own content, report occurrence, upvote, and undo. Errors distinguish authentication, authorization, validation, conflict, missing content, throttling, and degraded search where applicable. No acceptance or admin moderation tool is exposed.
4. Run the collaboration scenario using actual Codex and MCP Jam CLI: find an exact/paraphrased known issue; read it; contribute appropriate context/answer; record an encounter without inflating retries; and exercise a permitted verified-answer upvote after actually checking the fix. Across scenarios, one user's agent creates a question and another contributes a fix. Replay the relevant tool sequences as explicitly simulated unavailable-client workflows. Human acceptance happens in the browser and appears on subsequent reads.
5. Demonstrate a sanitized no-match publication and retry, embedding-outage fallback, and recovery from expired authentication. Retrieved content resembling tool instructions remains untrusted data in the guidance and scenario; it cannot create server-side execution or override authorization.
6. Actual and simulated scenarios use real individual OAuth credentials and the same authorization/domain rules. Preserve a matrix distinguishing actual Codex, actual MCP Jam CLI, simulated workflows, and untested native applications. The owner-amended goal may complete without native Claude Code/Cursor, provided the agreed actual/simulated evidence and limitations are recorded accurately.
7. Add a discoverable **Agent setup** page. Show the configured safe public MCP URL/client/callback/scope information and working Codex/MCP Jam examples, individual login steps, an `identity` check, logout/reauthentication and troubleshooting. Resolve the correct backend MCP URL when the Angular dev origin is port 4200, and use configured values on other origins. Never send browser client secrets, bootstrap credentials, tokens or private knowledge to this page/configuration response.
8. Provide accessible copy controls for setup snippets and the recommended paragraph, a selectable fallback, clear success/failure feedback, compatibility labels, and desktop/mobile layouts. Verify direct page navigation/refresh in the packaged jar, configured URL correctness, clipboard/fallback behavior, canonical paragraph consistency, and absence of sensitive values. Keep SPA forwarding and public-shell/security allowlists explicit and API errors intact.

## Verification

- Run `./gradlew --no-daemon oidcSmokeTest`, `./gradlew --no-daemon check bootJar`, `./gradlew --no-daemon integrationTest`, and `./gradlew --no-daemon e2eTest`, including the packaged two-user collaboration and setup-guide scenarios.
- Run actual Codex/MCP Jam CLI and explicitly simulated workflow scenarios with two distinct seeded Keycloak users and the actual local embedding endpoint. Preserve sanitized steps/tool results, actual versions, and outcomes under an evidence document linked here. Use a disposable application database, seeded local Keycloak for its pre-registered actual clients, and disposable IdP state for automated checks; keep credentials private.
- Confirm REST/MCP parity, retry IDs, verified-vote attestations, and browser acceptance behavior. Document any necessary manual client setup separately from automated CI checks.
- Verify setup-page deep links, configured non-secret values, canonical copy text, accessible copy/fallback behavior, and mobile layout. Inspect actual rendered output. Keep guide requirements visibly pending until implemented; adding Markdown documentation alone does not satisfy the UI criteria.

## Material decisions

- The original 2026-10-16 scope targeted Codex, Claude Code, and Cursor at MVP completion and permitted automatic sanitized publishing under individual developer identities. The 2026-10-17 amendment below supersedes the native-client completion requirement; automatic sanitized publishing remains unchanged.
- Human acceptance stays separate. Client instructions guide agent behavior; server authorization/validation must still protect every operation independently.
- Owner amended goal 006/MVP scope on 2026-10-17: Claude Code/Cursor cannot be provided; use MCP Jam CLI to simulate their MCP workflows alongside actual Codex, without requiring native tests or claiming native compatibility. This supersedes the original all-three-client completion gate for the laptop MVP.
- Owner also requires setup documentation in the web UI and a recommended copyable paragraph for consuming repositories' `AGENTS.md`. Keep its canonical text in `docs/agent-setup.md`; do not silently add that operational policy to ASO's own development instructions.

## Progress and blockers

The Angular Material Agent setup page is linked from the public navigation. A typed public API exposes only the configured backend MCP URL, issuer, scopes, optional public registrations, and the canonical paragraph embedded from `docs/agent-setup.md`. Copy controls have live-region success/failure feedback and selectable text fallback. The development profile supplies seeded public client details; non-development deployments must explicitly configure them. The packaged browser suite covers direct navigation/refresh, copy/fallback, dynamic public URL, signed-out access, secret absence and mobile layout. Actual Codex/MCP Jam two-user work and labelled simulations are recorded in [the evidence](006-evidence.md). Native Claude Code/Cursor remain unverified under the owner's amended scope. PR CI is the remaining handoff check.

## Completion evidence

[Sanitized goal 006 evidence](006-evidence.md) records the client matrix, live two-user collaboration, exact and paraphrased discovery, publication/retry, one encounter despite replay, context/answer, verified vote, browser acceptance, expired-token reauthentication, model outage/recovery, and setup-page browser/copy/layout checks. Local checks passed: `check bootJar`, `oidcSmokeTest`, `integrationTest`, and `e2eTest`. The executable jar contains the guide and the exact canonical paragraph. Commit, PR and CI evidence will be added after publication.
