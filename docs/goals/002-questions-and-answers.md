# 002 — Authenticated questions and answers

Status: in progress (implementation verified; PR publication pending)

## Dependencies and prerequisites

- [001 — Identity and access](001-identity-and-access.md), merged on `master` at `f4acae1` (PR #2).
- This milestone uses Codex and MCP Jam per the owner’s goal 001 amendment; Claude Code/Cursor remain unverified.

## Objective

Give humans and agents a working shared Q&A slice with correct attribution: create a problem, find/read it, and contribute an answer. See [Q&A and content lifecycle](../../spec.md#qa-and-content-lifecycle) and [agent publication](../../spec.md#agent-collaboration-and-publication).

## Included and excluded work

Include question/answer/tag migrations and jOOQ access, shared services, REST and MCP adapters, and Angular list/detail/create/answer views. Include optional structured technical context, basic filters/pagination, safe Markdown, validation, and retry-safe creates from the first writes. Exclude editing/comments, acceptance/moderation, votes/occurrences, and hybrid retrieval; those follow in goals 003–005.

## Acceptance criteria

1. User A creates a question through MCP; user B reads it in the browser and posts an answer. Reverse the interfaces and verify the same rules, records, and authors. An unknown question returns a real missing-resource error.
2. Questions support title, Markdown description, tags, and optional technical context. Answers include Markdown and may describe fix applicability/testing evidence. Server validation bounds inputs and rejects recognizable credential material; clients receive actionable errors.
3. The browser lists and opens real questions, filters by tag/state, and handles pagination, loading, empty, validation, and failure states. New questions are open; resolution UI waits for goal 003. Direct navigation and refresh of new client routes work in the packaged jar without swallowing API errors.
4. Bodies and code blocks are untrusted data: executable HTML/scripts cannot run when rendered. No server-side execution, attachments, or unrestricted log ingestion is added.
5. Authorship comes only from the validated principal. Attempts to impersonate another user through REST/MCP fields fail. REST and MCP invoke the same business validation and authorization.
6. Creates accept a stable request ID scoped to actor/operation. Retrying the same request returns the original result without a duplicate; conflicting payload reuse fails explicitly. Concurrent requests preserve these guarantees.

## Verification

- Run the three root validation commands from goal 001.
- Integration tests cover persistence, tags/context, principal-derived authorship, validation, and concurrent idempotent creates through both adapters.
- Packaged-application browser tests cover two-user Q&A, safe rendering, validation/error states, and direct detail navigation. Verify generated code is reproducible from migrations.

## Material decisions

- Owner selected full Q&A and automatic sanitized agent publishing under the developer's individual identity (2026-10-16).
- Start with ordinary list/filter operations; do not label this goal's list as completed hybrid search. Keep all writes in shared services.

- Use immutable server timestamps plus UUID tie breakers for bounded keyset pages; answer detail embeds the first page and `list_answers`/REST supports the remainder. Cursors bind to normalized tag/state or the answer parent. Pages are not frozen snapshots.
- Normalize trimmed text and sorted/distinct/lowercase tags before hashing create content. Actor/operation/request ID are the unique reservation key; channel is intentionally excluded from the hash so a cross-interface retry keeps the original channel. Reservation and product insert commit or roll back together; conflicting content is explicit 409/tool error, never silent deduplication.
- Bounds, API/MCP contracts, publication guidance, Markdown policy and in-view browser retry limitations are documented in [Q&A contracts](../questions-and-answers.md). CommonMark is the only added library; no search/framework/infrastructure is introduced.

## Progress and blockers

Implementation and acceptance verification are complete locally. Publication of the verified branch and pull request is pending. No goal 003 feature has been implemented. The existing OAuth limitations and explicitly unverified clients remain listed below; no implementation criterion is blocked.

## Completion evidence

Verified on 2026-10-16 on the owner’s ARM64 Mac with Java 25.0.4.1, Gradle 9.2.1, Node 24.22.0, Docker Desktop engine 29.8.2, and the pinned PostgreSQL/pgvector and Keycloak fixtures. The initial checkout was clean. Branch `codex/002-questions-and-answers` starts from updated `master` at `f4acae1`, containing merged goal 001. All later goal records remain planned.

| Criterion | Recorded evidence |
| --- | --- |
| 1 — Two-user shared Q&A | `QuestionIntegrationTest` exercises Alice MCP question → Bob browser REST answer and Bob browser REST question → Alice MCP answer, comparing persisted IDs, authors and channels. The packaged Chromium scenario repeats these through actual Angular forms and real fixture OAuth. Separate actual Codex/MCP Jam scenarios below reproduce both directions. Unknown valid question UUIDs return REST 404 and MCP `not_found`/404 tool errors, including answer creation against an unknown parent. |
| 2 — Schema/context/validation | Additive Flyway V3 creates questions, answers, normalized tags/relations, author FKs, indexes and request reservations; V1/V2 are unchanged. Tests round-trip title, Markdown, all four context fields, tags, applicability and evidence. Shared policy bounds strings, tags, UUIDs, page inputs and JSON POST size; wrong types/unknown fields and recognizable credential material fail with actionable errors. Unit checks cover private keys, AWS/GitHub token shapes, bearer values, credential assignments, connection URLs, JWTs and redacted diagnostics; tag scanning occurs before normalization. Integration checks cover bounds, malformed filters, nested unknown fields and oversized JSON; browser checks show validation guidance. |
| 3 — Browser/list/filter/routes | Angular Material list/detail/create/answer views use real APIs, loading/empty/failure/retry states, tag and open/all/resolved filters, cursor next/previous pages and additional-answer pagination. The executable-jar suite covers direct detail/create/list navigation, detail refresh, unknown-question guidance, a controlled read outage/retry, tag/state filters and 21-row pagination. Integration pages equal timestamps with a UUID tie breaker and inserts a new question between pages without duplicates. New questions remain open; resolved results are empty until goal 003. Unknown API and asset paths are never SPA-forwarded. Desktop/mobile Q&A screenshots are visually inspected; mobile has no horizontal overflow. |
| 4 — Untrusted rendering | `ContentPolicyTest` asserts raw scripts/HTML remain escaped, unsafe URL schemes lose their target, safe links/code blocks survive, and image requests are removed. Packaged Chromium renders malicious script/image/link examples as inert content, asserts no executable DOM nodes/handlers or script effects, and preserves code text/Markdown emphasis. Angular uses its normal HTML sanitizer with no trust bypass; context/evidence use text bindings. No code execution, attachments or log-upload facility exists. |
| 5 — Principal-only authorship | Both adapters call `QuestionService`, resolving the actor with the existing validated `IdentityService`. Product DTOs contain no caller identity. HTTP integration uses real browser sessions and real signed access tokens, rejects REST/MCP `authorId` and extra nested fields, and verifies the same actor across transports. Existing issuer/audience/expiry/membership/admin/session/bearer boundary tests continue passing. Persisted channel comes from the validated transport, never tool arguments. |
| 6 — Retry/concurrency | Real HTTP integration races 12 mixed REST/MCP question creates and 12 mixed answer creates: one result per actor/operation/request ID, identical question responses, and one answer row. Changed payload reuse returns REST 409/MCP `request_conflict`; a competing two-payload race has one winning payload. Parent question is part of answer identity. Different actors and operations can reuse IDs independently. A failed missing-parent create rolls back its reservation and can be retried against an existing parent. Packaged Chromium discards a response after server commit and retries the unchanged browser form, reaching the original ID. Real Codex question/answer retries and MCP Jam answer replay also return the original IDs. |

Required validation observed passing (no successful skips):

- `./gradlew --no-daemon oidcSmokeTest`: 2 tests, isolated fresh Keycloak; all existing identity/PKCE fixture checks.
- `./gradlew --no-daemon check bootJar`: 10 backend tests, 4 frontend tests, Kotlin/TypeScript compilation, lint/formatting, production Angular build and executable jar; default checks remain service-free.
- `./gradlew --no-daemon integrationTest`: 7 tests with isolated PostgreSQL/Keycloak; includes HTTP Q&A/concurrency, identity security, fresh migrations, pgvector, and an explicitly populated V2 → V3 upgrade that preserves the identity’s UUID/profile. A final formatter-plus-integration invocation also passed after adding upgrade/rollback/timestamp-tie coverage.
- `./gradlew --no-daemon e2eTest`: 1 comprehensive suite, Chromium against the executable jar and isolated database/Keycloak; includes the existing four-user authentication regression and the new two-user Q&A paths above.
- `./gradlew --no-daemon generateJooq` twice: all 17 generated Java files have identical SHA-256 hashes, offline from V2/V3 DDL. Ordinary builds use committed bindings; CI retains regeneration-drift checking.
- `git diff --check`: pass. Reports/screenshots remain untracked under `backend/build/reports/tests`, `backend/build/test-results`, and `backend/build/e2e`; no tokens or browser authentication traces are committed.

Initial checks exposed a missing blank line in the Markdown test fixture, outdated migration-count expectations (including Flyway’s separate schema-creation marker), and an asynchronous browser URL assertion. These were corrected with the substantive assertions retained; the final suites passed. The unlisted nested asset path remains denied with 403, while permitted missing root assets return 404; neither is forwarded to HTML. No unavailable prerequisite is counted as a skip or pass.

### Actual clients and sanitized collaboration evidence

Codex CLI **0.162.1** and MCP Jam CLI **5.16.1** each completed real discovery/pre-registered code/S256-PKCE login for Alice and Bob against the existing development Keycloak. Both clients sent the canonical ASO resource, listed the six tools, and performed real Q&A calls. No manually supplied access token was used to claim login. Product content lived in the disposable `aso-goal002-client-db` PostgreSQL container, with the jar temporarily at `http://127.0.0.1:8080`; the persistent development product database was never used or reset. Automated Gradle checks used only isolated Keycloak/PostgreSQL fixtures. Existing local Keycloak users, clients and volumes were preserved.

| Fixture user | Browser author UUID | Codex identity/author UUID | MCP Jam author UUID |
| --- | --- | --- | --- |
| Alice | `ee4872ca-434e-4fc9-b07b-710eeccc9ffe` | same | same |
| Bob | `f1a5215a-9264-4a6d-a883-777f4b032237` | same | same |

These are IDs from the disposable client-check database, not the preserved development database IDs recorded in goal 001.

- Alice’s Codex question `894d8df5-18ee-4980-9cd9-b9ce6d072c7d` replayed to the same ID; Bob read it in the browser and posted web answer `3d9d7011-6e48-498d-8992-6d8974e160bb`. Alice’s real Codex read returned that answer with Bob’s ID and channel `web`.
- Alice’s MCP Jam question `1c60edd7-5108-4cf7-8488-506baac00a5c` appeared in Bob’s browser; Bob posted a web answer. Bob’s real MCP Jam read later showed Alice’s question plus his browser/MCP answers with their original channels.
- Bob’s browser question `e60d0c34-872d-4dc9-a188-2784b19ded42` received Alice’s Codex answer `7fe7fb01-7050-4256-af3b-3476e24df10f` and MCP Jam answer `e95a34ce-bce0-4a1d-b060-89b533670717`, both visible in Bob’s browser with Alice’s identity and channel `mcp`. Codex’s exact answer retry returned the original ID.
- Bob’s Codex question `75842a30-64ca-4a0b-b2a5-df401b52ff88` and MCP Jam question `6103bc62-2d76-4b11-b95e-9a0387aaee1e` used the same request IDs/payloads as Alice’s respective questions and created distinct Bob-authored records. Bob’s clients also answered Alice’s original questions. MCP Jam replayed Bob’s answer `775b7ee5-5ef1-428b-abb4-6dde0d60bedf` with an identical structured result.

Codex invocations were narrow ephemeral read-only client exercises instructed to call only the ASO MCP tools; no implementation was delegated. MCP Jam used its real CLI OAuth login with `--verify-tools --verify-call-tool identity` and `tools call --validate-response --expect-success`. OAuth credentials stayed in private local caches/0600 temporary files; sanitized result IDs are retained here instead of raw auth diagnostics. The temporary jar/database, client OAuth logins and private token files were removed after verification; development services/volumes were left intact. Alice’s browser logout correctly revoked her refresh session: a later expired MCP Jam credential could not refresh (`Session not active`); Bob’s active session replay passed. This is not recorded as a successful call for the logged-out identity.

The goal 001 OAuth limits remain: local HTTP loopback does not pass MCP Jam’s strict HTTPS-resource metadata conformance check, and the Keycloak pre-registered audience mapper is not full arbitrary-resource/RFC 8707 conformance. No new conformance pass is claimed. Claude Code/Cursor and non-Keycloak IdPs remain unverified per the owner’s scope amendment. None is silently replaced by an automated protocol fixture.

Implementation commit and PR: pending publication on this branch. Do not merge; stop before goal 003.
