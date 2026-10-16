# 003 — Context, editing, accepted solutions, and moderation

Status: in progress

## Dependencies and prerequisites

- [002 — Questions and answers](002-questions-and-answers.md).
- Goal 001's trusted admin mapping and browser-only action boundary.
- Goals 002a/002b are merged into master at starting commit `9109bc1`; their Kotlin bindings, strict payloads, Linux browser setup, and loopback fixture forwarding are preserved.

## Objective

Let people and agents enrich a question while preserving authorship, and let its human owner or an admin mark the useful solution. See [Q&A and content lifecycle](../../spec.md#qa-and-content-lifecycle).

## Included and excluded work

Include comments/context on questions and answers, editing own content through REST/MCP/UI, optimistic concurrency, human acceptance/reopening, an admin moderation UI, and audit records. Exclude duplicate merging, full revision-history browsing, voting, and automated acceptance.

## Acceptance criteria

1. Members contribute chronological comments/context to questions and answers through the browser and MCP. Author identity, validation, safe rendering, and create retry safety follow goal 002's rules.
2. Authors can edit their own questions, answers, and comments. Another ordinary member cannot edit them by changing IDs or payload fields. Admins can edit/hide/restore content through the moderation UI.
3. A question's authenticated human owner can accept one answer, select another, or clear acceptance through the browser. An admin can do this for any question. Other members cannot; MCP has no acceptance operation, and MCP bearer credentials cannot call the browser action endpoint.
4. Acceptance and open/resolved state agree in UI, REST, and MCP reads. Acceptance cannot target an unrelated or hidden answer. A verified-by-agent attestation is not automatically an accepted answer.
5. Stale edits fail with a recoverable conflict instead of overwriting another edit. Acceptance/moderation changes are transactionally consistent under concurrent requests.
6. Hidden content is absent from ordinary reads, lists, and all adapters, including descendants of a hidden question. Hiding an accepted answer reopens its question; restoring it does not silently reaccept it. Admin views allow explicit restoration.
7. Audit records identify actor, action, target, server time, and relevant before/after state for material edits, moderation, and acceptance. Tokens and unrestricted diagnostics stay out of logs/audit metadata.

## Verification

- Run `./gradlew --no-daemon check bootJar`, `./gradlew --no-daemon integrationTest`, and `./gradlew --no-daemon e2eTest`.
- Test an owner, a different member, and an admin against the full permission matrix. Include direct HTTP attempts with MCP credentials, unrelated-answer IDs, concurrent/stale edits, hidden descendants, and accept/hide/restore transitions.
- Packaged browser scenarios cover adding context, editing, permission-aware controls, acceptance/reopening, conflict recovery, and admin moderation. Server tests must enforce permissions independently of hidden UI controls.

## Material decisions

- Owner selected own-content editing; human question-owner acceptance/reopening; and admin moderation/resolution authority (2026-10-16).
- The developer owns agent-created content because both share the authenticated user. Human acceptance is a separate browser action, not an agent-supplied boolean.

- Flyway V4 adds comments, independent content versions/visibility, accepted-answer association, and transactional audits. V1–V3 remain unchanged; offline generation deliberately expands to V2–V4. A composite foreign key prevents accepting another question's answer.
- All lifecycle writes take the parent question's exclusive row lock before touching content; detail/answer/comment reads hold a shared parent lock while assembling their response. The existing actor/operation/request-ID reservation check precedes parent existence checks, preserving goal 002's conflict/retry semantics. Failed creates roll back reservations.
- Edits fully replace editable fields and require a positive `expectedVersion`. Question acceptance and visibility also require reviewed versions. Conflicts preserve browser drafts and require loading/comparing current content before explicitly retrying; MCP errors provide the same recovery guidance. Original author, creation time, and channel stay immutable.
- Ordinary author editing is separate from explicit admin moderation. Even trusted admin MCP credentials receive only own-content privileges. Acceptance and all admin operations independently require a browser principal in the shared services, in addition to the web chain's session/CSRF protection. There are no acceptance or moderation MCP tools.
- Comments use flat, ascending keyset pages and goal 002's validation, Markdown, attribution and retry rules. Hidden questions suppress all descendants; hidden answers suppress their comments. Admin pages retain independent child visibility for explicit restoration. Hiding a question clears its acceptance too; restoration always leaves it open.
- Audits record actor/channel/action/target/question/server time and typed before/after versions, visibility, acceptance and overall/per-field SHA-256 fingerprints. They identify changed fields without copying full private prose. Automatic reopening gets its own acceptance audit in the same transaction. No full revision-history UI is added.

## Progress and blockers

Implementation and local acceptance verification are complete on `codex/003-content-lifecycle`, based on updated master `9109bc1`. Publication and PR CI verification are in progress. Goal 004 and later features remain unimplemented.

## Completion evidence

Verified on 2026-10-17 (Europe/Vienna), on the owner's ARM64 Mac with Java 25.0.4.1, Gradle 9.2.1, Gradle-provisioned Node 24.22.0, Docker Desktop 29.8.2, and the existing pinned PostgreSQL/pgvector and Keycloak images. The starting checkout was clean; one feature branch was created from updated master containing merged goals 002/002a/002b.

| Criterion | Actual evidence |
| --- | --- |
| 1 — Context/comments | Real-Keycloak HTTP integration crosses MCP/browser comment creation, reading and exact replay, validates identity/channel, sanitized Markdown and credentials, rejects conflicting parent/content reuse and unknown/impersonation fields, and checks chronological scoped keyset pages. Eight concurrent mixed-interface retries return one comment ID. Packaged Angular forms add context to both questions and answers. |
| 2 — Editing/permissions | HTTP tests edit each owned content type across adapters, deny another member changing IDs, deny admin MCP edits of someone else's content, and permit explicit admin browser edits of questions/answers/comments, including hidden questions. Original author/channel/creation time remain intact. Packaged UI tests verify permission-aware controls and actual question/answer/comment editing. |
| 3–4 — Human acceptance | Owner browser accepts, changes, and clears acceptance; admin browser acts for another owner; unrelated members are denied. Unrelated/hidden answers fail. Resolved/open filters and MCP detail agree with acceptance. Direct HTTP bearer attempts (member and admin) fail even with a CSRF header; bearer-only admin reads fail too. Validated admin JWTs passed directly to shared acceptance/moderation services are denied. Session requests without CSRF fail. Discovery contains exactly eleven permitted tools and unknown acceptance/moderation tools are rejected. Browser tests exercise accept/change/reopen. |
| 5 — Conflicts/concurrency | Real HTTP races produce exactly one successful question, answer and comment edit per reviewed version, with all stale writers receiving 409. Acceptance versus answer hiding always ends open with no hidden accepted answer. A parent hide/edit race allows one versioned change and rejects its competitor. The packaged browser preserves a stale draft, loads/displays a separately committed version, explicitly reuses the reviewed version, and saves successfully. Existing create retry/concurrency assertions remain intact. |
| 6 — Visibility/restoration | Both adapters and direct ordinary reads suppress hidden answers/comments and hidden-question descendants; admin ordinary reads follow the same rules. New descendants under hidden content fail. Admin pages retain all content. Hiding the accepted answer reopens, restoring it leaves acceptance null, independent comment hide/restore works, and question hide/restore preserves child content without reaccepting. Packaged browser verifies hide/restore and ordinary disappearance. |
| 7 — Audits/transactions | Integration checks actor, target, channel, server time, before/after fingerprints and acceptance transitions, excluding full prose/credential diagnostics. Concurrent committed edit audits advance exactly one version; failed competitors do not overwrite. Parent row locking serializes content changes and automatic reopening/audits commit in the same Spring transaction. |

Required local checks passed, with zero failed/error/skipped tests in the final reports:

- `./gradlew --no-daemon oidcSmokeTest`: 2 tests against fresh disposable Keycloak; existing discovery, signing, audiences, claims and PKCE assertions retained. The successful invocation included formatting tasks before the smoke task.
- `./gradlew --no-daemon check bootJar`: 15 backend tests and 4 frontend tests, strict Kotlin/TypeScript compilation, lint/formatting, production Angular build and executable jar. The final UI refinement was reverified with `./gradlew --no-daemon spotlessApply frontendFormat check bootJar e2eTest` (41s).
- `./gradlew --no-daemon integrationTest`: 12 tests, zero failures/errors/skips, with isolated PostgreSQL/Keycloak (57s). Includes fresh V1–V4 migration, populated V2 identity upgrade, populated V3 question/answer/reservation preservation through V4, identity security and full Q&A/lifecycle matrices.
- `./gradlew --no-daemon e2eTest`: one comprehensive packaged-jar Chromium suite, zero failures/errors/skips (35s); the final UI rerun also passes. It retains goal 002's authentication, two-user Q&A, safety, retry, filter and pagination scenarios and adds the lifecycle scenario above.
- `./gradlew --no-daemon generateJooq` twice: all 21 generated Kotlin files match the pre-run output and each other by SHA-256, offline from V2–V4. No generated Java exists. Applied V1–V3, image pins, CI setup and browser fixtures are unchanged.
- `git diff --check`: pass. Desktop moderation and mobile lifecycle screenshots were visually inspected; the mobile scenario asserts no horizontal overflow. Reports and screenshots remain untracked under `backend/build/reports/tests`, `backend/build/test-results`, and `backend/build/e2e` (`moderation-desktop.png`, `lifecycle-mobile.png`).

Initial formatting/Playwright option-type/test-inference failures were corrected before passing verification. The first integration run exposed changed create-request conflict precedence (404 instead of the established 409 for reused request IDs with a different missing parent); locking was moved after reservation identity checks, and the original substantive assertion now passes. No failure or unavailable prerequisite is recorded as a successful skip.

Automated checks used disposable PostgreSQL and Keycloak instances only. No development database, realm, or persistent volume was reset or mutated. No deployment, merge, goal 004 work, new framework, or dependency upgrade was performed.

Existing actual Codex CLI 0.162.1 and MCP Jam CLI 5.16.1 login/Q&A verification from goal 002 is retained, alongside the same individual OAuth configuration. Goal 003's new operations are verified through real-Keycloak HTTP adapters and the packaged browser; no additional manual client-login/conformance run is claimed. Claude Code/Cursor remain explicitly unverified. The existing local HTTP/Keycloak resource-indicator and human-presence limitations remain documented in README and Q&A contracts; another IdP still needs separate verification.
