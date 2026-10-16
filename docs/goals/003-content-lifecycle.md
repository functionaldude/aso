# 003 — Context, editing, accepted solutions, and moderation

Status: planned

## Dependencies and prerequisites

- [002 — Questions and answers](002-questions-and-answers.md).
- Goal 001's trusted admin mapping and browser-only action boundary.

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

## Progress and blockers

Not started. Depends on the identity boundary and Q&A schema. Expose any mismatch between browser-only actions and the chosen authentication flow before claiming acceptance is protected.

## Completion evidence

None yet. Record authorization/concurrency/audit results, browser evidence, commit, and PR when implemented.
