# 005 — Hybrid search with locally hosted embeddings

Status: complete

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
- The owner's 2026-10-17 corpus review follow-up requests explicit target-before-distractor and negative-case expectations plus answer/context retrieval and deduplication fixtures. [Evaluation corpus version 2 and its scoring contract](../search-evaluation/README.md) retain the 14 questions and ten paraphrases, expand exact queries to ten, and distinguish relevant related results from unsupported fix/applicability claims. The original top-three exact and eight-of-ten top-five semantic gates remain; all added ordering, source, duplicate, and negative assertions must be reported and assessed before claiming relevance acceptance.

## Progress and blockers

Implementation started on `codex/005-local-hybrid-search` from updated master `a87a631` (merged goal 004). Initial corpus committed before ranking implementation as `7553d58`; owner-provided version 2 and scoring contract committed as `3c08d1a`, and explicit owner approval recorded as `cb2ae68` before evaluation/tuning. Production provider/model choice remains deferred.

The requested corpus review amendments are recorded in version 2. The owner explicitly approved version 2 for evaluation and tuning on 2026-10-17. Evaluator support and measured results remain required; fixture edits alone are not a passing relevance evaluation.

Static fixture validation on 2026-10-17 passed for 14 unique questions, 15 unique answers, 24 unique queries (10 exact / 10 semantic / 4 negative), all target/answer/acceptance references, reciprocal version/language ordering constraints, source-only identifier isolation, and negative allowlist/required-target consistency. Corpus/document formatting and scoped whitespace checks passed. This review follow-up changes only evaluation fixtures and documentation; it does not run or claim a real-model relevance/latency result.

Local runtime verified on 2026-10-17: `http://127.0.0.1:1234/v1`, model ID `text-embedding-qwen3-embedding-0.6b`, Qwen3 0.6B GGUF Q8_0 (639,150,592 bytes), LM Studio 0.4.26+4, llama.cpp Metal runtime 2.59.0, CLI commit dcefd5a. Apple M4 Max, 16 CPU cores (12 performance/4 efficiency), 64 GiB unified memory, macOS 27.0.1. Authentication initially required; owner enabled unauthenticated access for the probe. Credentials remain optional configuration.

Actual batched response: indices 0/1, 1,024 finite components per vector. A request with `dimensions: 32` still returned 1,024: ASO must validate output and never depend on this request parameter. Raw versus instructed-query cosine was 0.89963; prepared input returned the same vector alone and in a batch (cosine 1.0). The API therefore embeds the supplied text; ASO supplies the query instruction and plain retrieval documents per the Qwen model card. Probe preparation: `Instruct: Given a technical problem, retrieve related engineering questions and solutions.\nQuery: <query>`. Warm single probe 35.7 ms, three-input batch 520.8 ms; these are setup probes, not the required search latency evaluation. First authenticated-free two-input call took approximately 1.1 s including model-list HTTP and local summary processing; no model unload or cold-load claim is made.

## Completion evidence

Implementation, local acceptance verification and the initial Linux PR CI are complete on 2026-10-17 (Europe/Vienna). PR #8 is open and unmerged; final follow-up checks are linked below.

| Criterion | Actual evidence |
| --- | --- |
| 1 — Actual local model/configuration | Live model/preparation/batch probes above, verified GGUF hash below, strict typed adapter, internal-host/redirect restrictions, configurable credentials/preparation/bounds/timeouts, and finite/count/index/model/dimension response checks. Unit tests exercise wrong dimensions/duplicate indices, oversized and slow responses, outages, and refused public endpoints without external DNS. |
| 2 — Shared authorized question results | Real-Keycloak REST/MCP responses are equal for the same query/filter. Anonymous REST is rejected; typed MCP coercions/cursor misuse fail. Browser shows source excerpts, explanations, separate existing metrics and acceptance. Evaluation asserts resolved/open source acceptance, answer-only/context-only identifiers, one parent despite multiple matching answers, and two-item pages matching full ordered results. |
| 3 — Owner-reviewed relevance | Approved fixed corpus version 2 has 10 exact / 10 unchanged paraphrase / 4 negative queries. Both 14- and 1,000-question real-model reports pass: 10/10 exact in top three (all rank one), 10/10 paraphrases in top five. The lexical-only baseline gets 10/10 exact and 9/10 paraphrases; `semantic-4` is absent. All ordering/source/pagination/uniqueness/negative assertions pass: 218 hybrid assertions on the small set, 247 on the latency set. Negatives 1–3 return nothing; negative 4 returns only the version concern at rank one. See retained reports and per-query table below. |
| 4 — Actual latency | 1,000 seeded questions, 100 warmed authenticated MCP searches, concurrency five, limit 20: p50 **186.95 ms**, p95 **210.24 ms**, max **244.84 ms**; first application query **588.61 ms**. Indexing takes **100.87 s**. Meets the unchanged p95 ≤2 s target. Hardware/model/cold/synthetic-data limits are explicit below. |
| 5 — Updates/recovery/configured reindex | Question and answer edits reach model inputs/search within the 60-second integration bound; removed old identifiers immediately stop matching. Acceptance changes are reflected. Failed queue state persists into a new worker; retry and expired claims recover. Packaged-jar browser test creates during outage, stops the actual JVM while work cannot finish, restarts against the same disposable DB/realm and observes automatic semantic recovery within 60 seconds. Configuration-only model/dimension/document-preparation switch (3→4 fixture dimensions) requires explicit rebuild; all resulting vectors have the selected size and product row counts survive. Concurrent creates, four rebuilds and multiple worker ticks retain current indexed revisions. |
| 6 — Visibility/outage/metrics | Hiding an accepted answer clears acceptance and immediately removes its stale excerpt/vector matches; hiding its question excludes search/detail before cleanup. During a controlled provider outage, creates/detail/exact/lexical search remain usable, all adapters show explicit degradation, and recovery drains durable work. Search retains the separately asserted upvote/encounter counts. |

Required local commands passed, including a final combined invocation and subsequent final application recheck. All JUnit reports have zero failures/errors/skips:

- `./gradlew --no-daemon oidcSmokeTest`: 2 fresh isolated-Keycloak checks.
- `./gradlew --no-daemon check bootJar`: 19 backend tests, 4 Angular tests, strict compilation, lint/format checks and executable jar.
- `./gradlew --no-daemon integrationTest`: 18 disposable PostgreSQL/Keycloak tests, including populated V5→V7 preservation and search/lifecycle/engagement concurrency.
- `./gradlew --no-daemon e2eTest`: one comprehensive executable-jar Chromium suite, including actual JVM restart recovery and mobile/desktop search.
- `./gradlew --no-daemon searchRelevance` and `./gradlew --no-daemon searchLatency`: actual selected local model, isolated product/IdP data; [14-question report](../search-evaluation/searchRelevance.json), [1,000-question relevance/latency report](../search-evaluation/searchLatency.json), and [per-query results](../search-evaluation/README.md#measured-results--2026-10-17).
- `./gradlew --no-daemon generateJooq`: final independent run matches all 31 generated Kotlin files by SHA-256. Zero generated Java files; V1–V5, image pins, lockfiles and CI workflow are unchanged. `git diff --check` passes.

Desktop/mobile search screenshots were visually inspected under `backend/build/e2e/`; no overflow or unsupported fix label was observed. Screenshots, raw application/test logs, caches and browser artifacts stay untracked and are retained through the existing CI diagnostic upload. Development database/realm/volumes were never used or reset by tests/evaluation. The selected model was unloaded/reloaded once for a separate cold probe and its observed configuration restored. No merge, deployment or goal 006 work occurred.

Intermediate harness decoding, old MCP-tool expectations, an order-dependent existing resolved-list assertion, formatter issues and negative-case leakage were corrected before the final passing runs. The lexical baseline was strengthened to partial-term matching; its remaining semantic miss is reported rather than concealed. No target was relaxed.

### Implementation decisions

- REST `GET /api/v1/search`, MCP `search_questions`, and the browser use the same principal-authorized typed service. Search returns one question with source excerpt, exact/lexical/semantic explanation, human acceptance and unchanged engagement counts. Filters and cursor bind to query, tag, state, generation and semantic availability; restart pagination when these change. Stable score/UUID ordering reproduces fixed-content pages. Content/index changes can change later pages; this is not a held database snapshot.
- Exact complete identifiers constrain candidates across question/context/tags/visible answers. Coordinates, versions, error codes, `C++` and `C#` are not replaced by nearest-vector neighbors. A colon terminating an exception name remains a delimiter, while coordinate colons remain significant. English partial-term lexical retrieval and cosine similarity use reciprocal rank fusion (constant 60), preceded by exact matches. No vote/occurrence/acceptance boost or generated fix/applicability claim is introduced.
- V6 adds an independent content search revision, configuration-fingerprinted generations, dimension-independent vector arrays, and a durable question work queue. V7 triggers enqueue question/answer/tag changes in the product transaction. Work is generation-independent so an edit committing across a rebuild cannot disappear into a retired generation. Rebuild preserves the highest queued revision; claims use leases and revision/lease comparisons. Shared advisory locks serialize vector commits with explicit rebuilds. Existing migrations and product versions/data are preserved; offline Kotlin jOOQ generation selects table DDL V2–V6 and excludes V7's functions.
- Indexing does not contact the model inside product transactions. It validates model ID, response indices/count, finite/nonzero vectors and actual dimensions; incompatible responses persist as retryable failures. Credentials are excluded from fingerprints and failures. Endpoint hosts must be explicitly allowed and resolve internally; redirects/public addresses have no fallback. Limits, preparation, batching, timeouts and selected local model are configuration only. Reindexing replaces derived vectors while product data remains intact.
- Search reads current product visibility and revision, using shared parent locks during response assembly. Hidden questions/answers cannot surface through stale snippets or vectors; a changed question temporarily loses stale semantic candidates while exact/lexical retrieval remains current. Disabled/unavailable embeddings, pending work and incompatible generations are explicit degraded states.

### Runtime identity

Selected GGUF SHA-256: `06507c7b42688469c4e7298b0a1e16deff06caf291cf0a5b278c308249c3e439`. The recorded laptop/runtime details above describe the actual endpoint, not a cloud provider or inferred production model. Java 25.0.4.1, Gradle 9.2.1 and Gradle-provisioned Node 24.22.0 are retained. No dependency/image upgrades or goal 006 implementation are included.

After the final warmed benchmark completed, a separate [cold probe](../search-evaluation/modelCold.json) unloaded only the selected idle embedding model and reloaded it with the same identifier and 32,768 context. Load wall time including CLI: **0.828 s**. First prepared API embedding: **89.69 ms**; three following calls: **35.65–36.75 ms**. Actual output remains 1,024 dimensions. Identifier, model artifact path, context, null TTL and ordinary engine mode match the pre-probe state. No other model or product/IdP state was changed. The report preserves lifecycle commands and exact prepared input; these API timings are separate from end-to-end ASO search.

### Publication and CI

Implementation commit: `3ce7b8f29d1bd043cdefcc61c69135388ae86dfe`. Pull request: [#8 — Add local hybrid search, durable indexing and measured evaluation (goal 005)](https://github.com/functionaldude/aso/pull/8), targeting `master`, open and unmerged. [Implementation CI run 39022128506](https://github.com/functionaldude/aso/actions/runs/39022128506) passed every step: check/package, offline regeneration drift, Linux browser setup, fresh OIDC smoke, disposable reset isolation, integration, executable-jar browser and diagnostic upload. Downloaded retained reports confirm 19 backend tests, 2 OIDC tests, 18 integration tests and one packaged-browser suite, all with zero failures/errors/skips; Angular's four tests also pass.

The follow-up fixes a review-found sentence-ending-period boundary for complete identifiers, adds Java/PostgreSQL-adapter regression coverage, and refreshes both actual-model reports plus cold evidence. It preserves version/language distinctions and passes the same local gates. Current final-commit results are on [PR #8 checks](https://github.com/functionaldude/aso/pull/8/checks). Existing GitHub Action/JVM deprecation notices do not represent failed checks; their unrelated upgrades are outside this goal.

### Limits to retain

- Exact vector distances and current-text scans are measured at 1,000 questions, not a production capacity guarantee. Latency distractors are 986 short synthetic inventory records; large diagnostic bodies/answer collections need their own later capacity evidence.
- Semantic document chunks are bounded per question; exact/lexical retrieval still covers all current visible sources beyond that cap. Comments do not contribute search documents in this slice.
- The first authenticated application query is measured separately from a real model unload/reload. The cold probe is one sample with warm OS file caches, not a reboot/disk-cold measurement; identifier/path/context/TTL/engine mode were verified restored.
- This adds protocol-level real-Keycloak search verification and packaged-browser coverage. Existing Codex/MCP Jam evidence remains; no new actual-client login run or Claude Code/Cursor verification is claimed. Goal 006 remains planned.
