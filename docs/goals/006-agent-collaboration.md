# 006 — Verified collaboration in Codex, Claude Code, and Cursor

Status: planned

## Dependencies and prerequisites

- [004 — Votes, occurrences, and trends](004-votes-occurrences-and-trends.md).
- [005 — Local hybrid search](005-local-hybrid-search.md).
- Seeded local Keycloak users `alice`/`bob`, the goal 001 client configuration, LM Studio, and current installations of all three target clients. External IdP access is not required.

## Objective

Make the complete search/contribute/learn loop usable by the target coding agents, with clear publication instructions and evidence from the actual clients. See [agent collaboration and publication](../../spec.md#agent-collaboration-and-publication).

## Included and excluded work

Include complete MCP contracts/tool descriptions, client setup examples, reusable repository instruction snippets, consistent error/retry guidance, and real-client scenarios. Earlier goals implement the services and adapters; this goal finishes their composition and interoperability. Exclude new domain features, shared bot identities, automatic acceptance, and claims that ASO controls every client's local approval settings.

## Acceptance criteria

1. Document setup/authentication for Codex, Claude Code, and Cursor using individual OIDC identities from the development Keycloak. Record client versions, required settings, and sanitized troubleshooting examples without tokens or real private diagnostics. Document how another IdP is configured and verified separately; do not claim its compatibility from these local results.
2. Publish reusable instructions: search first; assess existing answers; report one actual encounter with a stable event ID; add only useful new context; create a sanitized question if needed; publish a proposed fix with actual testing evidence; upvote an answer only after applying/verifying it. Preserve request/event IDs across retries. Agents may perform these operations automatically within the policy.
3. Tool contracts expose bounded/paginated search/list/get, create question, post answer, add context/comment, edit permitted own content, report occurrence, upvote, and undo. Errors distinguish authentication, authorization, validation, conflict, missing content, throttling, and degraded search where applicable. No acceptance or admin moderation tool is exposed.
4. Run the collaboration scenario in each client: find an exact/paraphrased known issue; read it; contribute appropriate context/answer; record an encounter without inflating retries; and exercise a permitted verified-answer upvote. Across the scenario set, one user's agent creates a question and another user's agent contributes a fix. Human acceptance happens in the browser and appears on subsequent client reads.
5. Demonstrate a sanitized no-match publication and retry, embedding-outage fallback, and recovery from expired authentication. Retrieved content resembling tool instructions remains untrusted data in the guidance and scenario; it cannot create server-side execution or override authorization.
6. Check that all clients use the same identity/domain rules. Record real interoperability limitations openly; mocked MCP calls or one successful client do not complete all-three-client support.

## Verification

- Run all three root validation commands, including the packaged two-user collaboration scenario.
- Run actual-client scenarios with two distinct seeded development Keycloak users and the actual local embedding endpoint. Preserve sanitized steps/tool results, versions, and observed outcomes under an evidence document linked here. Automated tests use separate disposable IdP state.
- Confirm REST/MCP parity, retry IDs, verified-vote attestations, and browser acceptance behavior. Document any necessary manual client setup separately from automated CI checks.

## Material decisions

- Owner requires Codex, Claude Code, and Cursor at MVP completion, and permits automatic sanitized publishing under individual developer identities (2026-10-16).
- Human acceptance stays separate. Client instructions guide agent behavior; server authorization/validation must still protect every operation independently.

## Progress and blockers

Not started. Real clients/users are required for completion. User/client access may be an external prerequisite; do not mark those checks passed because automated protocol tests pass.

## Completion evidence

None yet. Record client matrix, sanitized collaboration/outage results, automated checks, commit, and PR when implemented.
