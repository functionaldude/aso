# Goal 005 search evaluation

[`corpus.json`](corpus.json) is the sanitized relevance fixture for
[goal 005](../goals/005-local-hybrid-search.md). Version 2 contains 14 fictitious
questions, 15 answers, 10 exact queries, the original 10 semantic paraphrases, and
4 negative cases. It addresses the review of the initial `7553d58` corpus; owner
sign-off was given on 2026-10-17 for version 2 evaluation and tuning. These are evaluation expectations, not measured search
results or evidence that any proposed fix was tested.

## Fixture loading

Use a disposable evaluation database and map fixture question/answer IDs to the
actual created IDs. Seed each `answers` entry as a separate answer belonging to
its question, preserve the optional structured `context`, and apply
`acceptedAnswerId` explicitly. A null value means no accepted answer. The fixture
accepts one answer on `variant` and `class` so evaluation covers resolved and open
questions. Acceptance is a recorded human choice, not independent verification or
proof that an answer applies to another query. No answer supplies testing evidence.

Version 2 replaces the singular `answer` string with an `answers` array of
`{id, body}` objects. Do not flatten these into a question body, omit additional
answers, or copy context into the title/body. Index normal product content only;
fixture IDs, query text, targets, expectation metadata, and this README must not
enter the searchable documents. Otherwise the source-only probes lose their value.

An evaluator must explicitly support version 2 and every expectation below. Reject
unsupported versions/fields rather than silently running the old positive-target
checks and ignoring new assertions. Keep the committed fixture fixed during
tuning and include its Git revision and content hash in each report.

## Ranking and result assertions

Run every query in lexical-only and hybrid modes over the same seeded content,
using `evaluation.state` (`all`) and no tag filter. Wait for the intended index
generation to finish before measuring hybrid relevance. Preserve the returned
ordering; do not deduplicate or reorder results in the evaluator.

Ranks are one-based question positions. An absent target fails its rank check.
For each mode collect up to `evaluation.resultLimit` results (20), then repeat
using `evaluation.paginationPageSize` (2) and follow cursors until exhaustion or
the same result limit. With fixed content, concatenated pages must match the
single-request ordering. `requireUniqueQuestions` means no question ID may repeat
within a page or across the collected pages, regardless of how many answers match.

| Field                   | Scoring rule                                                                                                                                                                                                                                                                          |
| ----------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Positive `target`       | The relevant question, not an assertion of a verified fix. All 10 exact targets must have rank at most `exactMaxRank` (3). At least `semanticMinHits` (8) of the unchanged 10 paraphrases must have rank at most `semanticMaxRank` (5).                                               |
| `targetBefore`          | For every listed distractor that appears, the target must appear strictly earlier. An absent distractor is acceptable; an absent target fails. Equal scores are acceptable only if the actual returned order places the target first. This is additional to the target's rank gate.   |
| `sourceExpectation`     | The query's literal identifier must occur only in the named source category in the seeded searchable content. The returned target's snippet or matched-identifier evidence must expose the identifier from that source. A title-only match or an echo of the request is insufficient. |
| Negative `target: null` | There is no expected applicable solution. Always evaluate `negativeExpectation`; null is never a reason to skip the query. Relevant questions can still be required separately.                                                                                                       |

The hybrid run must pass the relevance gates, all pairwise ordering checks, source
checks, uniqueness/pagination checks, and all negative expectations. Report the
same checks for the lexical baseline, including misses; do not require it to pass
the semantic gate or hide its failures. No aggregate hit rate may mask a failed
ordering, source, duplicate, or negative assertion.

## Cases added for the review

| Cases                | What they establish                                                                                                                                                                                                                                                                                |
| -------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `exact-1`, `exact-7` | Reciprocal `2.1.0` / `1.9.0` coordinate queries: each version's question must precede the other version/root-cause distractor.                                                                                                                                                                     |
| `exact-5`, `exact-8` | Reciprocal bare `C++` / `C#` queries: the correct language's question must precede the other. The error code in `exact-6` remains an additional exact probe, with the same ordering check.                                                                                                         |
| `exact-9`            | `runtimeClasspath` exists only in two separate answers on `class`. Seed both IDs listed in `sourceExpectation.answerIds`; evidence from either answer is sufficient, but the parent must appear exactly once in the collected results. This exercises answer retrieval and deduplication together. |
| `exact-10`           | `org.example:token-validator:3.4.1` exists only in `issuer.context.dependencies`, as identified by `sourceExpectation.contextFields`. Retrieving/explaining it requires structured-context indexing.                                                                                               |

Check source isolation across all documents, not just the intended target. The
`onlyIn` values are `answers` or `context`; the associated answer IDs or context
field names identify the expected locations within the target. Context uses the
existing product fields `errorText`, `dependencies`, `versions`, and `repository`.

## Negative expectations

Each negative query carries explicit, independently scored expectations:

- `allowedRelatedTargets` is an exhaustive allowlist of question IDs permitted in
  the collected results. Returning any other question fails. An empty list requires
  no results; it does not mean unrestricted results.
- `requiredRelatedTargets` must all appear within `requiredRelatedMaxRank` when
  nonempty. An empty list allows an empty response. A required target must also be
  in the allowed list. This prevents an always-empty search from passing every
  negative case.
- `forbiddenMatchedIdentifiers` must not be attributed to source content as exact
  matches, including snippets, highlights, or retrieval explanations. Echoing the
  query, or explicitly saying an identifier was not matched, is allowed.
- `forbiddenClaims` forbids presenting a returned result as a
  `verified-fix-for-query` or `applicable-fix-for-query`. The additional
  `cross-version-compatibility-guarantee` forbids promising compatibility between
  PostgreSQL 15 and 18. These are evaluator assertion names, not new product API
  fields. Inspect the actual result metadata, snippets, explanations, and browser
  labels. Source acceptance can be reported faithfully; it must not be relabelled
  as verification or applicability to the current query.
- `rationale` explains why each judgment applies. It is annotation, never indexed
  content or generated output.

| Case         | Required behavior                                                                                                                                                                                                                |
| ------------ | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `negative-1` | Empty results or related `variant` / `variant-old` questions are allowed. Neither establishes a fix for `9.9.9`, and that coordinate must not be reported as an exact source match.                                              |
| `negative-2` | Empty results or related `lock` / `race` questions are allowed. A deadlock (`40P01`) and retry-safety constraint are not evidence of a signup fix for `23505`; the requested code must not be reported as an exact source match. |
| `negative-3` | Return no questions for the fictitious device/error. Selecting the nearest vector alone must not force a result.                                                                                                                 |
| `negative-4` | Return `version` within the top five, with no unrelated rows or compatibility guarantee. It is relevant evidence of an unresolved compatibility concern, not a distractor that must be suppressed.                               |

Automate assertions against structured output where available and preserve the
presentation evidence for recorded review of the claim prohibitions. Record an
unassessed assertion as **not evaluated**, not passed. Returning related evidence
does not require an LLM summary, a new confidence field, or an application claim
that a solution is known. These expectations govern the existing search surfaces.

## Evidence and scope

Record every query's target rank, ordered question IDs, distractor ranks, source
evidence, duplicate/pagination results, negative assertion outcomes, and lexical
versus hybrid differences. Keep snippets and annotations sanitized. Include model,
dimensions, preparation/index generation, corpus revision/hash, and the command
used. Report rank misses separately from presentation failures.

This small relevance set is separate from the goal's latency corpus of at least
1,000 questions and 100 warmed searches at concurrency five. It does not replace
authorization, hidden-content, outage/recovery, or index-lifecycle integration
tests. Static fixture validation does not establish measured relevance or latency.

## Measured results — 2026-10-17

Owner approval for this fixed version 2 corpus is recorded in commit `cb2ae685a3460a469c8d1ae03077fc107d242cca`, before evaluation/tuning. Corpus SHA-256: `9edc56db9bb7a41cd521a0eaa47abe54abda97c9f3f6a01b241529b0e47594eb`. Neither query text nor scoring metadata was indexed.

Both root commands passed against the actual local Qwen3 0.6B Q8_0 / 1,024-dimensional model in LM Studio 0.4.26+4. The [14-question relevance report](searchRelevance.json) passes all 218 hybrid assertions; the [1,000-question relevance and latency report](searchLatency.json) passes all 247 hybrid assertions. Both include the full lexical baseline, every ordered result/excerpt, source evidence, acceptance state, pagination and negative outcomes.

| Query | Target | Lexical rank | Hybrid rank |
| --- | --- | --- | --- |
| exact-1 | variant | 1 | 1 |
| exact-2 | class | 1 | 1 |
| exact-3 | lock | 1 | 1 |
| exact-4 | angular | 1 | 1 |
| exact-5 | cpp | 1 | 1 |
| exact-6 | csharp | 1 | 1 |
| exact-7 | variant-old | 1 | 1 |
| exact-8 | csharp | 1 | 1 |
| exact-9 | class | 1 | 1 |
| exact-10 | issuer | 1 | 1 |
| semantic-1 | variant | 4 | 1 |
| semantic-2 | class | 2 | 1 |
| semantic-3 | pool | 1 | 1 |
| semantic-4 | race | absent | 1 |
| semantic-5 | csrf | 1 | 1 |
| semantic-6 | issuer | 1 | 1 |
| semantic-7 | lock | 1 | 1 |
| semantic-8 | angular | 1 | 1 |
| semantic-9 | pagination | 1 | 1 |
| semantic-10 | hidden | 1 | 1 |

All ten exact targets rank first, including reciprocal version/language cases and answer/context-only identifiers. Hybrid ranks all ten paraphrases first on the 1,000-question corpus; the lexical baseline has nine top-five hits and misses `semantic-4` entirely. The baseline remains visible in the reports rather than being treated as a passing semantic result.

In both modes, negative cases 1–3 return no questions; negative case 4 returns only `version` at rank one. All negative allowlist/required-result/identifier/claim assertions pass. Source acceptance remains faithfully labelled. The packaged browser asserts the neutral related-evidence notice, absence of an accepted answer for the unaccepted fixture, semantic explanation and outage state. Desktop/mobile screenshots were visually inspected at `backend/build/e2e/search-desktop.png` and `search-mobile-degraded.png`; they stay in build/CI diagnostics rather than Git.

The 1,000-question run indexes in **98.75 s**. After ten extra warmups, **100 authenticated MCP searches at concurrency five**, requesting 20 results, measure **p50 187.56 ms**, **p95 217.87 ms**, **max 238.38 ms**. The first application query after indexing is **740.77 ms**. This exceeds the required sample/dataset minima and meets the unchanged p95 ≤2 s gate.

Hardware: Apple M4 Max, 16 CPU cores (12 performance/4 efficiency), 64 GiB unified memory, macOS 27.0.1; Java 25.0.4.1 / Gradle 9.2.1. Selected local GGUF runtime: llama.cpp Metal 2.59.0. Model SHA-256: `06507c7b42688469c4e7298b0a1e16deff06caf291cf0a5b278c308249c3e439`.

Cold evidence is a first application query with the model already loaded, not an unload/load benchmark. The latency seed adds 986 short synthetic inventory distractors; this is reproducible laptop evidence, not a production-sized diagnostic corpus or capacity guarantee. No development database, persistent realm, volumes or model loading state were reset.
