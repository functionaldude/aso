# 002 — Authenticated questions and answers

Status: planned

## Dependencies and prerequisites

- [001 — Identity and access](001-identity-and-access.md).

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

## Progress and blockers

Not started. Depends on verified identity. Define concrete schema/input bounds and documented API/MCP contracts in this goal; do not add caller-supplied authorship.

## Completion evidence

None yet. Record commands, two-user browser/MCP evidence, safety/idempotency results, commit, and PR when implemented.
