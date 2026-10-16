# 005 — Hybrid search with locally hosted embeddings

Status: planned

## Dependencies and prerequisites

- [003 — Content lifecycle](003-content-lifecycle.md); integrate goal 004's metrics before goal 006 completes.
- Owner's LM Studio with model ID `text-embedding-qwen3-embedding-0.6b`; confirm the actual endpoint, loaded model ID/dimensions, input preparation, and sanitized evaluation examples during setup.

## Objective

Find reusable problems and solutions through one exact/lexical/semantic search shared by humans and agents, while keeping embedding data on local infrastructure. See [local hybrid search](../../spec.md#local-hybrid-search).

## Included and excluded work

Include configurable LM Studio/OpenAI-compatible embedding integration, embeddings and index lifecycle, identifier-aware lexical retrieval, result fusion, browser/REST/MCP search, and reproducible relevance/performance evaluation. Exclude provisioning a production model server, cloud providers, generated answers, automatic duplicate merging, and a separate search cluster.

## Acceptance criteria

1. Integrate the owner's LM Studio model through its [OpenAI-compatible embeddings API](https://lmstudio.ai/docs/developer/openai-compat/embeddings). Configure base URL, model ID, optional authentication, expected output dimensions, query/document preparation, input limits, batching, and timeouts. Record the actual model/runtime versions and laptop resources; do not assume the service supports a request-time dimension parameter. ASO's backend sends question/query content and embeddings only to the configured internal service; no external embedding/LLM API or cloud fallback is used.
2. The same query/filter returns the same authorized question-level results through REST/MCP and the browser. Search includes questions, structured identifiers/context, tags, and answers, with pagination, useful snippets, accepted-answer state, and a concise explanation of relevant matches.
3. Commit an owner-reviewed sanitized evaluation set before tuning: at least six exact-identifier queries, ten semantic paraphrases, and four unrelated/no-known-solution or misleading-version cases. All exact targets rank in the top three; at least eight of ten paraphrase targets rank in the top five. Preserve punctuation/version distinctions and report per-query results against a lexical-only baseline. No-known-solution results must not be represented as verified fixes or forced matches.
4. Record a repeatable latency measurement on the selected hardware: at least 100 warmed searches, five concurrent requests, and a seeded corpus of at least 1,000 questions. Target p95 at or below two seconds for the first 20 results. Record hardware, embedding model, cold behavior, dataset size, and actual latency; a missed target is visible work, not a silently relaxed gate.
5. Creates/edits/acceptance changes propagate to search. Available-runtime updates become searchable within 60 seconds in the integration scenario. Pending/failed work survives application restarts and can be retried/rebuilt. A different model, dimension, or preparation profile is selectable through configuration and an explicit index rebuild, without application-code changes or resetting product data. Validate embedding responses and index generations so incompatible vectors are never mixed.
6. Hidden/removed content is inaccessible immediately through search and detail fetches, regardless of index lag. An embedding outage leaves creates/reads and exact/lexical search usable, returns a visible degraded-search state, and recovers without lost work.

## Verification

- Run `check bootJar`, `integrationTest`, and `e2eTest` with the Gradle wrapper; automated gates use controlled embedding fixtures and disposable PostgreSQL.
- Run the committed evaluation and latency measurement against the actual selected local model/runtime. Add documented reproducible root task(s) in this goal; report their names/results here and in README.
- Exercise outages, indexing retries/restarts, immediate removal, reindexing, and outbound endpoint selection. Preserve sanitized per-query ranking and latency reports.

## Material decisions

- Owner requires hybrid search in the MVP and locally hosted embeddings only (2026-10-16).
- Owner already has `text-embedding-qwen3-embedding-0.6b` in LM Studio and expects a different production model. Keep provider/model details configurable; use the [Qwen model card](https://huggingface.co/Qwen/Qwen3-Embedding-0.6B) when configuring query versus document preparation. The actual runtime behavior must be verified.
- The initial engineering quality/latency gates above make completion measurable. Any necessary change to them must be discussed and recorded before claiming completion.
- Ordinary tests need no live model download or embedding endpoint; separate real-runtime evidence is mandatory.

## Progress and blockers

Not started. Endpoint/output-shape/preparation confirmation and representative evaluation examples remain setup inputs. Do not substitute a cloud endpoint, skip real-model relevance checks, or call vector connectivity a completed search feature. A production provider/model choice is deferred and does not block the laptop MVP.

## Completion evidence

None yet. Record selected runtime/model, evaluation baseline and hybrid results, latency/outage/reindex evidence, commands, commit, and PR.
