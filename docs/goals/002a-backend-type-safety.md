# 002a — Backend type safety

Status: in progress

## Dependencies and prerequisites

- Goal [002 — Questions and answers](002-questions-and-answers.md), merged to `master` in PR #3.
- Java 25 and the checked-in Gradle wrapper; Docker for isolated integration/browser verification.

## Objective

Complete the owner's backend cleanup before goal 003: Kotlin jOOQ bindings, typed payload contracts, and permanent repository conventions. Product intent remains in [the spec](../../spec.md).

## Included and excluded work

Include offline Kotlin code generation, typed REST/MCP inputs and fixed responses, strict binding, compatibility verification, and agent/developer guidance. No schema migration, dependency upgrade, frontend contract change, or goal 003 feature is included.

## Acceptance criteria

1. Committed jOOQ bindings contain Kotlin only, compile without a database, and reproduce identically across repeated generation.
2. Known inputs, nested payloads, and fixed responses use data classes; Q&A services and validation do not accept JSON trees. Dynamic MCP data is decoded before service calls.
3. Both adapters preserve strict types, unknown-field rejection, principal-derived authorship, normalization, error envelopes, and retry compatibility with existing reservations.
4. `AGENTS.md` permanently records the agreed type-safety, dependency-property, request-state, and generation rules.
5. Required checks pass with recorded evidence, and a feature-branch pull request is opened.

## Verification

Run repeated `./gradlew --no-daemon generateJooq` with file hashes, `./gradlew --no-daemon check bootJar`, `./gradlew --no-daemon integrationTest`, and `./gradlew --no-daemon e2eTest`. Inspect adapter validation/error regressions and stored retry hashes against pre-cleanup serialization. Explicit Docker tests must provision isolated fixtures, never development data.

## Material decisions

- Owner approved this bounded cleanup on 2026-10-16, before goal 003.
- Keep per-handler `Authentication` arguments. Prefer immutable constructor properties for stable dependencies; never store request authentication in singleton controllers.
- Use typed contracts for known payloads. Raw JSON is limited to dynamic MCP IDs/payload dispatch and JSON Schema properties, with boundary comments explaining the exception.
- Retain nullable required input strings until shared domain validation so absent/null fields keep actionable errors. Collections retain distinct missing/default and explicit-null behavior; reject coercion and unknown fields in both adapters.
- Preserve the existing ordered normalized payload serialization used for SHA-256 reservations; no rehash or migration is needed.
- Generated Kotlin is excluded from handwritten formatting rules. Flyway V2/V3 remain the offline generation inputs.

## Progress and blockers

Implementation and required local verification are complete on `codex/002a-backend-type-safety`; pull-request publication remains. Goal 003 remains planned.

## Completion evidence

Verified on 2026-10-16 on the owner's ARM64 Mac with Java 25.0.4.1, Gradle 9.2.1, and Docker Desktop engine 29.8.2. The branch starts from merged goal 002 at `40626c2`; the initial working tree was clean.

| Criterion | Evidence |
| --- | --- |
| 1 — Kotlin generation | `generateJooq` passed twice, using the unchanged Boot-managed jOOQ 3.21.7 and offline V2/V3 inputs. SHA-256 manifests match for all 17 Kotlin files; no generated Java remains. Hashes still match after formatting and compilation. Imports use generated Kotlin table references, and generated formatting is excluded from Spotless. |
| 2 — Typed contracts | Q&A creation and technical context use data classes shared by both adapters; each MCP tool decodes known arguments before service calls. Fixed MCP envelopes/results/tool descriptions and CSRF output are typed. `ContentPolicy` accepts typed strings/lists. Remaining JSON trees are documented MCP boundary data; maps describe dynamic JSON Schema properties or provider claim sets. |
| 3 — Compatibility | HTTP integration tests reject unknown/authorship fields, nested extras, missing required values, null/wrong-type tags, scalar coercion, fractional/null/overflowing page limits, and invalid answer inputs. REST and MCP domain errors agree. Golden pre-cleanup question/answer serialization produces the exact stored hashes; normalized cross-interface retries return original results. Existing concurrent creates, pagination, identity security, and Markdown checks remain intact. MCP tests cover integer/fractional/string IDs, initialize/ping/discovery, notifications, protocol errors, and preserved malformed-JSON transport handling. |
| 4 — Permanent guidance | `AGENTS.md` now requires Kotlin generation, immutable stable dependency properties, per-handler authentication, typed payloads, and narrow documented dynamic-JSON exceptions. README and Q&A contracts describe the implementation. |
| 5 — Validation/publication | All local checks below passed. Pull-request publication is pending. |

Final verification command: `./gradlew --no-daemon check bootJar integrationTest e2eTest` — **passed**, exercising all four requested tasks against the final source state:

- `check bootJar`: 13 backend unit tests and 4 frontend tests, lint/formatting checks, Kotlin/TypeScript compilation, production Angular build, and executable jar. Default checks remain independent of running services.
- `integrationTest`: 9 tests with isolated PostgreSQL/pgvector and Keycloak; zero failures/errors/skips.
- `e2eTest`: 1 comprehensive Chromium suite against the actual executable jar and isolated PostgreSQL/Keycloak; zero failures/errors/skips. Existing two-user Q&A, authentication, safe rendering, retry, and navigation assertions pass.
- `generateJooq`: two successful offline runs with matching hashes for all 17 generated Kotlin files.
- `git diff --cached --check`: passed, including the newly generated Kotlin files.

Initial verification exposed an oversized-integer exception outside Jackson's databinding subtype and Boot's higher-priority default Problem Details handler. The implementation catches the appropriate Jackson base exception and orders Q&A advice before Boot's handler. Advice is scoped to `QuestionController`, preserving MCP transport-level parsing behavior; final tests cover both boundaries. Intermediate failures are not counted as passing verification.

Reports remain untracked under `backend/build/reports/tests`, `backend/build/test-results`, and `backend/build/e2e`. Tests used disposable fixtures and did not mutate the development database or Keycloak state. No schema migration, product-data reset, deployment, merge, or goal 003 implementation was performed. Actual Codex/MCP Jam client login was not rerun for this cleanup; no additional client-compatibility claim is made beyond goal 002's existing evidence. Local results do not imply a Linux CI result.
