# MVP implementation goals

The product requirements live in [the spec](../../spec.md). These records describe bounded implementation work and the evidence required to finish it. Planning a goal does not start implementation or enable Goal Mode.

| Goal                                                                        | Result                                                                            | Depends on                                              | Status    |
| --------------------------------------------------------------------------- | --------------------------------------------------------------------------------- | ------------------------------------------------------- | --------- |
| [000 — Foundation](000-foundation.md)                                       | Runnable monorepo, database tooling, checks, and workflow                         | —                                                       | Complete  |
| [000a — Local Keycloak](000a-local-keycloak.md)                             | Seeded development IdP, lifecycle helpers, and isolated OIDC smoke verification   | 000; Docker                                             | Complete  |
| [001 — Identity and access](001-identity-and-access.md)                     | Individual browser/MCP identities and private workspace access                    | 000a; local Keycloak fixture and actual MCP clients     | Complete* |
| [002 — Questions and answers](002-questions-and-answers.md)                 | Authenticated end-to-end Q&A through the browser, REST, and MCP                   | 001                                                     | Complete* |
| [002a — Backend type safety](002a-backend-type-safety.md)                   | Kotlin jOOQ bindings, typed payloads, and permanent backend conventions           | 002                                                     | Complete  |
| [002b — CI verification repair](002b-ci-browser-dependencies.md)            | Repaired Linux browser setup and fixture networking with passing PR CI            | 002a; self-hosted runner                                | Complete  |
| [003 — Content lifecycle](003-content-lifecycle.md)                         | Context/comments, editing, accepted solutions, and moderation                     | 002                                                     | Complete  |
| [004 — Votes, occurrences, and trends](004-votes-occurrences-and-trends.md) | Useful contributions and recurring problems become visible                        | 003                                                     | Complete  |
| [005 — Local hybrid search](005-local-hybrid-search.md)                     | Exact, lexical, and semantic retrieval using configurable LM Studio embeddings    | 003; endpoint/model configuration and evaluation corpus | Complete  |
| [006 — Agent collaboration and setup](006-agent-collaboration.md)           | Actual Codex/MCP Jam workflows, explicit simulations, and in-app setup/copy guide | 004, 005                                                | Complete  |
| [007 — Laptop MVP release](007-mvp-release-readiness.md)                    | Tested local release, setup/configuration docs, and delivery evidence             | 006                                                     | Planned   |

*Goals 001 and 002 use Codex and MCP Jam per the owner's 2026-10-16 amendment. The 2026-10-17 amendment extends this to goal 006 and laptop MVP delivery: actual Codex/MCP Jam CLI plus explicitly simulated unavailable-client workflows. Native Claude Code/Cursor compatibility remains unverified and is not a completion gate for this amended MVP. Goal 006 also adds the in-app setup guide and recommended copyable `AGENTS.md` paragraph. Historical evidence remains in merged PRs #2 and #3.

Start with preparatory goal 000a, then follow goals 001–007 in order, completing cleanup 002a before 003. This keeps the existing product goal numbers intact. Goal 005 can follow goal 003 directly if search is the priority; its implementation must still integrate with goal 004 before goal 006 completes. Local Keycloak supplies development identities; no external IdP account is required. Confirm LM Studio configuration/evaluation examples early.

For each goal:

1. Read its record and the current spec; resolve its blocking inputs before dependent work.
2. Implement on a feature branch, retaining shared authorization and business logic across adapters.
3. Run the listed checks and record actual results, artifacts, and limitations.
4. Open a pull request and link it in completion evidence. Do not mark the goal complete until its acceptance criteria have evidence.

Infrastructure credentials, tokens, and real private diagnostics belong outside these records. Label deliberately public fictitious fixture credentials as local-only. The default check/build remains independent of a running IdP, embedding service, or database; explicit tests provision isolated dependencies. Actual Codex/MCP Jam verification and labelled simulations use local Keycloak with seeded users; real-model verification uses LM Studio. A future external IdP or previously untested native client gets a separate compatibility check before use.
