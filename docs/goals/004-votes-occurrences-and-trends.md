# 004 — Upvotes, occurrences, and recurring problems

Status: complete (PR open, unmerged)

## Dependencies and prerequisites

- [003 — Content lifecycle](003-content-lifecycle.md).

## Objective

Show which contributions are useful and which problems recur without mixing these signals or letting retries inflate them. See [votes, occurrences, and trends](../../spec.md#votes-occurrences-and-trends).

## Included and excluded work

Include shared vote/occurrence services, schema/constraints, REST/MCP operations, browser controls and counts, and recent/unanswered/recurring views. Exclude downvotes, reputation, gamification, notifications, and an opaque trend-scoring system.

## Acceptance criteria

1. Members upvote questions/answers and undo votes. One active vote per authenticated user/target is enforced under retries and concurrency; self-voting is rejected even when the author/voter uses different MCP clients. Undo is safe to repeat and cannot remove another user's vote.
2. MCP answer upvotes require a short attestation of applying/verifying the fix. Client instructions forbid an answer upvote merely because the problem recurred. Display the attestation as caller-reported evidence, never independent server verification or human acceptance.
3. A distinct actual encounter is reported with a stable event ID, question ID, authenticated reporter, and server-recorded time. A replay returns the same event without increasing counts; conflicting ID reuse fails. A later independent encounter by the same developer increases events but not the distinct-developer total. Repeated build/tool/transport retries within one incident reuse the event.
4. Initial question creation does not invent an occurrence. UI/MCP workflows can explicitly record the initial encounter and subsequent encounters. Caller-supplied reporter IDs or timestamps cannot impersonate users or manipulate the trend window.
5. UI, REST, and MCP expose separate upvote, encounter, and distinct-developer counts. Acceptance never changes automatically. Hidden content is excluded from ordinary views and aggregates as appropriate.
6. Recent, unanswered (zero visible answers), and recurring views use stable pagination. The recurring view uses a rolling seven-day UTC window, ordered by distinct reporters, then encounter count, then latest occurrence, then stable question ID. Show labelled seven-day and all-time metrics; exclude zero-occurrence questions from recurring results.

## Verification

- Run `./gradlew --no-daemon oidcSmokeTest`, `./gradlew --no-daemon check bootJar`, `./gradlew --no-daemon integrationTest`, and `./gradlew --no-daemon e2eTest`.
- Integration tests cover concurrent upvotes/undo, self-votes, independent users, conflicting/replayed occurrence IDs, later independent incidents, and transaction/aggregate consistency.
- Use controlled clocks for exact seven-day boundaries, deterministic ties, and unanswered versus unresolved behavior. Browser tests show the distinct counters and recurring ordering using real persisted data.

## Material decisions

- Owner selected upvotes only, undo/no self-voting, agent answer votes after verification, and per-distinct-encounter event IDs with separate unique-developer counts (2026-10-16).
- Trend ordering and server timestamps make recurring behavior explicit. Do not cap a developer at one encounter per day or substitute occurrences for endorsement.

- Flyway V5 adds active votes and immutable occurrence events, preserving V1–V4. Kotlin jOOQ generation deliberately expands to V2–V5. Composite foreign keys retain answer/question association; unique constraints enforce one active vote per developer/target and one event per developer/event ID.
- REST and MCP call the same engagement service, with the existing parent-question lock order. Vote setters use explicit `active`; repeats preserve the first vote's channel, time and evidence. Undo deletes only the authenticated actor's row. A re-upvote after undo creates a new active vote. No acceptance/version transition is triggered.
- MCP answer upvotes require 1–500 trimmed UTF-16 code units of credential-checked verification prose. Public response field `callerReportedVerification` and browser labels explicitly identify caller-reported evidence. Active evidence is paginated; undo removes it from active evidence. Browser upvotes may omit attestation; missing evidence is labelled accordingly.
- Occurrence IDs are canonical UUIDs scoped to authenticated developer across questions/transports. The only caller inputs are question/event IDs. Replays preserve the initial channel/time; reuse for another question fails with `event_conflict`, including a different missing question. No automatic initial event or per-day cap exists. Browser pending/completed incident IDs are retained in tab session storage by developer/question, surviving reload; the user explicitly starts a later independent incident.
- The rolling UTC interval includes both endpoints `[asOf - 7 days, asOf]`, at PostgreSQL microsecond precision. Recurring ranks distinct reporters, events, latest event, then question UUID, all descending. List cursors bind to normalized tag/state/view/moderation scope, retain the first page's server cutoff, and carry keyset rank values. Later events after the cutoff cannot reorder that traversal. This remains keyset pagination over committed data, not a held database snapshot; moderation and in-flight commits may change visibility. Seven-day/all-time metrics are labelled separately; all-time counts on a list are through its cutoff. Unanswered means no visible answers, independently of unresolved state.
- Default list state remains `open` for compatibility; select `state=all` for all questions in any view. Hidden questions and descendants remain absent from ordinary adapters; hidden answers do not disqualify unanswered questions. Restoration retains underlying votes/events without changing acceptance.

## Progress and blockers

Implementation, local acceptance verification, and Linux PR CI are complete on `codex/004-votes-occurrences-trends`, from updated master `e46b054` containing merged goal 003. PR #7 is open and unmerged. No blockers remain and no goal 005 work is included.

## Completion evidence

Local implementation and acceptance verification recorded on 2026-10-17 (Europe/Vienna), using Java 25.0.4.1, the checked-in Gradle 9.2.1 wrapper, Gradle-provisioned Node 24.22.0, and the existing pinned PostgreSQL/pgvector and Keycloak images.

| Criterion | Actual evidence |
| --- | --- |
| 1 — Upvotes/undo/identity | Real-Keycloak HTTP tests mix 16 REST/MCP concurrent answer upvotes and 16 undo requests, exercise mixed set/undo races, preserve original evidence/time across repeats, prevent another user's undo, and count independent voters. Browser and tokens from two distinct disposable public PKCE client registrations reject self-votes against the same issuer/subject identity. Packaged UI exercises upvote/undo and permission-aware own-content controls. |
| 2 — Verification attestation | Shared validation rejects absent/blank/over-500/credential-bearing MCP answer attestations. Bounded REST/MCP vote pages expose `callerReportedVerification` with voter/channel/server time; retries preserve it. Packaged browser reads a real MCP-created attestation with the explicit caller-reported/no independent verification/no human acceptance label. |
| 3–4 — Actual encounters/retries | Sixteen concurrent cross-adapter retries return one identical event; delayed replay preserves original channel/time. Reuse for another existing or missing question returns 409; concurrent conflicting parent reuse produces one success and one conflict. Later independent encounters by the same developer raise encounters only; another developer raises the distinct total and may independently reuse the UUID. New questions start at zero events. Impersonation/time fields and scalar coercions fail. Browser simulates a committed request with a lost response, checks identical eventId on retry and unchanged count, persists the completed incident across reload, and explicitly starts a different incident with a new ID. |
| 5 — Separate metrics/visibility/acceptance | Both adapters and packaged UI show votes separately from all-time/seven-day encounters and distinct developers. Engagement leaves acceptance null; existing human-only acceptance/moderation security tests remain intact. Hidden answers suppress vote operations/evidence and do not count as visible answers. Hidden questions reject new/replayed events and disappear from recurring results; restoration retains events. |
| 6 — Views/window/order | Controlled server clock tests include the exact inclusive lower/upper bounds and exclude events one microsecond outside each boundary. Expected ordering independently covers distinct reporters over event count, event count over recency, latest occurrence, and deterministic UUID ties. One-item recurring pages match the full expected ordering while later encounters change fresh rankings; cursors reject changed views. Unanswered excludes an open question with a visible answer and includes it after that answer is hidden. Existing recent keyset/filter tests are retained. Packaged browser uses real persisted events to show a two-developer/three-encounter problem above a one-developer/four-encounter problem, plus recent/unanswered views. |
| Schema preservation | Fresh V1–V5 startup and populated V2/V3 upgrades pass. A new populated V4-to-V5 test retains accepted answer, question version, hidden context, retry reservation and audit, while inventing no votes/events. |

Required local checks (final reports have zero failed/error/skipped tests):

- `./gradlew --no-daemon oidcSmokeTest`: 2 fresh disposable-Keycloak tests; discovery, signing, issuer/audiences, claims and PKCE assertions retained.
- `./gradlew --no-daemon check bootJar`: 15 backend unit tests, 4 Angular tests, strict compilation, lint/format checks, production frontend and executable jar. Original lint settings are retained.
- `./gradlew --no-daemon integrationTest`: 16 tests (5 migration/database, 2 identity, 9 Q&A/lifecycle/engagement) using disposable PostgreSQL/Keycloak.
- `./gradlew --no-daemon e2eTest`: one comprehensive packaged-jar Chromium suite, including all prior authentication/Q&A/lifecycle scenarios and the new engagement scenario.
- `./gradlew --no-daemon generateJooq` twice: all 25 generated Kotlin files match each other and the original candidate by SHA-256, offline from V2–V5. Zero generated Java files. V1–V4, image pins, CI workflow and dependency versions are unchanged.
- `git diff --check`: pass. Visually inspected `backend/build/e2e/engagement-desktop.png` and `recurring-mobile.png`; mobile scenario asserts no horizontal overflow. Reports/screenshots/build output stay untracked.

Initial compile/format issues were corrected before passing checks. The first integration attempt exposed an overlong generated fixture tag and incorrect use of fixed real-client callbacks; tests now assert fixture creation and add the second client only to the disposable realm, preserving development/real-client registrations. No failed or unavailable prerequisite is treated as a successful skip.

All automated data was disposable PostgreSQL/Keycloak state. No development database, realm, persistent volume, or local credentials were reset or mutated. No merge, deployment, dependency upgrade, goal 005 implementation, or new infrastructure was performed.

The existing goal 002 Codex CLI 0.162.1/MCP Jam CLI 5.16.1 verification is retained. Goal 004 is verified through real-Keycloak HTTP adapters, two isolated MCP registrations, and the packaged browser, without claiming another actual-client login/conformance run. Claude Code/Cursor remain explicitly unverified. Local HTTP/Keycloak resource-indicator and human-presence limitations remain documented; another IdP still needs its own verification.

Implementation commit: `487224b31970c5b2cee504e7dea6e143831c647f`. Pull request: [#7 — Add upvotes, retry-safe encounters and recurring views (goal 004)](https://github.com/functionaldude/aso/pull/7), targeting `master`, open and unmerged. Current checks: [PR #7 checks](https://github.com/functionaldude/aso/pull/7/checks).

[Implementation CI run 38979523832](https://github.com/functionaldude/aso/actions/runs/38979523832) passed every step for `487224b31970c5b2cee504e7dea6e143831c647f`: unit/lint/format/package, offline regeneration drift, Linux Chromium/OS dependencies, fresh OIDC smoke, independent disposable reset checks, integration, packaged browser, and diagnostic upload. Downloaded and inspected the retained JUnit reports: 15 backend unit tests, 2 OIDC smoke tests, 16 integration tests, and 1 packaged-browser suite, all with zero failures/errors/skips. The frontend gate also passed its 4 tests. This publication-evidence-only follow-up runs the same CI gate again; current final-commit results are linked on PR #7 checks above.
