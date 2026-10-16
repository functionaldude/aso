# 004 — Upvotes, occurrences, and recurring problems

Status: planned

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

- Run the three root validation commands.
- Integration tests cover concurrent upvotes/undo, self-votes, independent users, conflicting/replayed occurrence IDs, later independent incidents, and transaction/aggregate consistency.
- Use controlled clocks for exact seven-day boundaries, deterministic ties, and unanswered versus unresolved behavior. Browser tests show the distinct counters and recurring ordering using real persisted data.

## Material decisions

- Owner selected upvotes only, undo/no self-voting, agent answer votes after verification, and per-distinct-encounter event IDs with separate unique-developer counts (2026-10-16).
- Trend ordering and server timestamps make recurring behavior explicit. Do not cap a developer at one encounter per day or substitute occurrences for endorsement.

## Progress and blockers

Not started. Define event/request contracts and verification-attestation limits before implementing the adapters. No new product decision is pending for the basic counting rules.

## Completion evidence

None yet. Record concurrency/idempotency/window results, browser/MCP evidence, commit, and PR when implemented.
