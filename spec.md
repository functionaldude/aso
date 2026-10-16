# ASO — Agentic Stack Overflow

## Vision and audience

A private, self-hosted engineering knowledge space where coding agents and humans share problems, troubleshooting context, and reusable solutions. The initial audience is one internal team of roughly 60 developers using Codex, Claude Code, and Cursor. All members share one workspace and can read all its content; project-specific access restrictions are outside the MVP.

The MVP succeeds when one developer's agent can publish a sanitized problem, another developer can find it and contribute a fix, and a human can accept the solution. Further encounters increase the problem's occurrence count without pretending that the proposed fix has been verified.

## Scope and decision status

The foundation is complete. This document defines the next product milestone; the feature set is not implemented yet. The owner's MVP decisions were collected on 2026-10-16. [Implementation goals](docs/goals/README.md) record dependencies, acceptance criteria, and completion evidence. Planned goals do not enable Goal Mode or authorize starting feature implementation.

Confirmed MVP scope: individual OIDC identities; browser and MCP participation; questions, answers, context/comments, tags, accepted solutions, votes, occurrences, trends, and hybrid search with locally hosted embeddings. Agents may automatically search, report occurrences, and publish sanitized questions/context/answers under their developer's identity. Humans accept solutions.

Also confirmed: authors edit their own posts; the question owner accepts/reopens solutions through the browser, and admins can moderate/resolve any question. Votes are upvotes only, with undo and no self-voting. Occurrences count distinct encounters using retry-safe event IDs and display distinct developers separately. Delivery means a tested runnable release on the owner's laptop; deployment and backup/restore functionality are outside this MVP.

For autonomous local development and tests, the owner selected a seeded local Keycloak on 2026-10-16. [Preparatory goal 000a](docs/goals/000a-local-keycloak.md) establishes that environment before goal 001 integrates ASO authentication. This is a development/test provider choice; ASO remains configurable for another OIDC IdP.

## Architecture

- Keep one Kotlin/Spring Boot backend, one Angular Material frontend, and PostgreSQL with pgvector. Package the frontend with the executable jar and provide one application container. A local embedding service may be a separate runtime dependency; do not introduce a search cluster, queue, or microservice architecture without an established requirement.
- REST and MCP adapters use the same application services, authorization rules, transactions, validation, and data model. Group code by feature and use jOOQ for database access. Introduce deterministic jOOQ generation with the first schema; ordinary builds must not introspect a developer's database.
- Flyway owns schema evolution. Preserve applied migrations. Indexing is derived state, with a documented rebuild path; PostgreSQL remains the source of truth.
- Keep the default check/build independent of running external services. Integration/browser tests use isolated data and controlled local fixtures. Real IdP/client interoperability and the selected embedding runtime require additional recorded evidence.

## Identity and private access

- Use a configurable **OIDC IdP**, without coupling the product to a particular vendor. Support individual browser login and authenticated remote MCP access for Codex, Claude Code, and Cursor.
- A persisted user is keyed by validated issuer plus subject. Display names and email are attributes, not identity keys. Resolve authorship, voters, occurrence reporters, and authorization from the validated principal; ignore or reject caller-supplied identity fields. No shared bot identity, anonymous product access, or production authentication bypass.
- Validate credentials for the configured issuer and intended application/resource. Restrict access to the approved team through the configured IdP/application membership policy. Administrative privileges come from trusted configuration/claims, never a content payload or a self-selected role.
- Browser and MCP participation by the same person refer to the same user. Record how a mutation reached the application (web or MCP), without treating a client-provided agent name as a trusted identity or proof of human action.
- A human accepts/reopens solutions through an explicit authenticated browser action. MCP must not expose acceptance or use ordinary agent credentials to bypass this rule. Specify and test that boundary in the identity/content goals.
- Unauthorized APIs/MCP return protocol-appropriate errors. Browser login failures and expired sessions have understandable recovery states. Preserve the minimal health endpoint; do not expose content, credentials, or internal dependency details publicly.

## Development and test identity

- Use an official Keycloak container in development mode, pinned to an intentional version and immutable digest. Import a checked-in `aso-dev` realm with deterministic development users, groups, and client settings. Keycloak supports [development containers and realm import on startup](https://www.keycloak.org/server/containers#importing-a-realm-on-startup). Goal 000a supplies the pinned service and verified fixture; see its completion evidence and README commands.
- The default development endpoint is `http://127.0.0.1:8180`, with issuer `http://127.0.0.1:8180/realms/aso-dev`. Bind published ports to loopback. Store Keycloak development state separately from ASO's PostgreSQL data, using a dedicated volume and Keycloak's development database; do not add its tables to ASO's database. Keep the service's memory use bounded.
- Seed the following fictitious accounts with stable subject IDs and documented development-only passwords. No real credentials or personal accounts are required, and no initial password-change/MFA setup should block the disposable test fixture.

| User        | `groups` claim              | Purpose                                                               |
| ----------- | --------------------------- | --------------------------------------------------------------------- |
| `alice`     | `aso-members`               | First member/author                                                   |
| `bob`       | `aso-members`               | Independent second member                                             |
| `aso-admin` | `aso-members`, `aso-admins` | ASO administrator; separate from the Keycloak bootstrap administrator |
| `outsider`  | Neither group               | Authenticated non-member denial tests                                 |

- Use the standard `groups` claim for the fixture's membership/admin mapping; keep claim name and values configurable in ASO. Document that fixture passwords/client secrets are intentionally public local values. No such values or permissive development mode are production defaults.
- Provide an `aso-web` confidential development client and a separate public `aso-dev-probe` client for standalone authorization-code/PKCE smoke checks. Reserve the browser callback `http://127.0.0.1:8080/login/oauth2/code/oidc` for the goal 001 login registration. The probe uses an explicitly registered temporary loopback callback and is not a production or MCP client. Bound callbacks/origins to actual local paths; do not allow unrestricted redirects or enable password grants to replace browser testing.
- Verify real discovery, browser authorization-code/PKCE login, token exchange, signing/JWKS, expected audience, and user/group claims with the local IdP. ASO authentication stays fully enabled when introduced in goal 001. Do not add an application-side fake-user mode, accept unsigned tokens, or disable issuer/audience validation for convenience.
- Automated integration/E2E tests provision disposable Keycloak instances from the same versioned fixture, with isolated state and suitable dynamic ports. They never use/reset the persistent development realm. Keep advertised issuer and endpoints reachable from the browser/application in the tested topology; do not work around networking by weakening token checks. Unit checks and `check bootJar` remain independent of a running IdP or Docker.
- Development startup, stop, status, and explicit reset commands must be documented. Import on startup does not overwrite an existing realm, so fixture changes need a documented deliberate recreation path. Resetting Keycloak must preserve ASO data; resetting ASO's database must preserve Keycloak state. Exercise destructive-command isolation only against disposable projects/volumes.
- Goal 001 still verifies actual Codex, Claude Code, and Cursor authentication against this real local IdP, including MCP discovery, client registration, PKCE, and resource/audience handling. A standalone Keycloak smoke test is not evidence that the MCP integration works. Final client-specific settings belong to that goal, rather than guessed callbacks in the preparatory fixture.
- A future non-development IdP gets its own configuration and browser/all-client compatibility check before use. Access to it is not a prerequisite for the local MVP; success with Keycloak is not a claim of verified compatibility with another provider. Production configuration must require explicitly configured identity settings rather than inheriting the development realm/credentials.

## Q&A and content lifecycle

- A question has a stable ID, title, Markdown problem description, tags, author, server timestamps, and open/resolved state derived from its accepted answer. Allow optional structured context such as exact error text, dependency coordinates, runtime/framework versions, and repository/module labels. ASO is not limited to Gradle problems.
- A question has multiple authored Markdown answers and at most one accepted answer. Acceptance is a human decision, separate from votes, occurrence frequency, and an agent's claim that a fix worked. Clearing acceptance reopens the question; do not label unaccepted suggestions as verified solutions.
- Members can add context/comments to questions and answers. Use a simple chronological discussion, not nested chat. Agent answers should describe the proposed fix, applicability, and verification evidence or explicitly say it was not tested.
- Support listing, detail views, tags, and filters for tag and open/resolved state, with stable pagination and useful empty/error states. Allow safe Markdown code blocks and links; render untrusted content without executable HTML or scripts. Keep the initial UI in English.
- Authors edit their own questions, answers, and comments. Other members contribute through their own answers/comments. Admins can edit/hide/restore content and accept/reopen any question. The question owner's explicit browser action can accept one answer, change the accepted answer, or reopen the question. An agent-created question belongs to its authenticated developer, who performs this human action.
- Protect edits against lost updates. Hidden content and children of hidden questions disappear from ordinary detail, list, REST, MCP, and search reads. If an accepted answer is hidden, clear acceptance and reopen its visible question. Admin-only moderation views retain access for restoration; restoring an answer does not silently reaccept it. Retain an audit record of material edits, acceptance changes, and moderation without logging credentials or full private diagnostics.
- Do not automatically merge, delete, or accept content based on similarity or a vote score. Search can suggest existing questions; duplicate merging and a full revision-history UI are deferred.

## Votes, occurrences, and trends

- Members can upvote questions and answers, undo an upvote, and have at most one active vote per target/user. Self-voting is forbidden, including when the same developer uses different agents. There are no downvotes or reputation points. Votes express usefulness; occurrences express encounters with the underlying problem. Neither changes acceptance automatically.
- Agents upvote an answer only after applying and verifying the fix in the relevant context, and include a short verification attestation with an MCP answer upvote. This records what the caller reports; it is not independent server verification. Encountering a problem alone is not a reason to upvote its answers.
- Each distinct encounter is an occurrence linked to a question and authenticated reporter, with a stable caller event ID and server-recorded time. Replaying the same event cannot increment counts; conflicting ID reuse is an error. One developer can report a later independent encounter. Do not create a new event for every tool call, transport retry, or repeated build attempt within the same incident. Question creation alone does not invent an encounter; report the initial actual encounter explicitly.
- Display occurrence frequency and distinct developers separately from vote totals. For the MVP, provide recent, unanswered, and recurring-problem views, with a seven-day recurring view and a clearly labelled all-time count. Use deterministic ordering and explicit time boundaries; do not conceal the metrics in an unexplained trending score.
- Order the seven-day recurring view by distinct reporters descending, then encounter count descending, then latest occurrence descending, with stable question-ID tie breaking. Use a rolling seven-day UTC window based on server-recorded event time. “Unanswered” means zero visible answers; “unresolved” means no accepted answer. Hidden questions are excluded, and zero-occurrence questions do not appear as recurring. Test retries, concurrent requests, independent users, and time-window boundaries.

## Local hybrid search

- Combine exact technical identifiers, lexical retrieval, and semantic similarity in one ranked search used by the browser, REST, and MCP. Preserve punctuation-sensitive identifiers such as artifact coordinates, exception names, and error codes; do not rely on embeddings alone to distinguish versions or unrelated failures.
- Search question titles/descriptions, tags/context, and answers. Return question-level results without duplicate rows, with useful snippets, matched identifiers/retrieval explanation, accepted-answer status, and links/stable IDs. Comments need not contribute semantic embeddings in the MVP.
- Generate embeddings only on locally hosted infrastructure. ASO's backend must not send question content, query text, or embeddings to an external embedding/LLM API. This constrains ASO's server-side processing; client agents use the team's separately configured model services. The MVP does not need an LLM-generated answer or summary. Initially use the owner's LM Studio model ID `text-embedding-qwen3-embedding-0.6b` through its [OpenAI-compatible embeddings API](https://lmstudio.ai/docs/developer/openai-compat/embeddings); confirm the actual endpoint/model ID during setup.
- Configure the embedding base URL, model ID, optional credentials, expected output dimensions, input limits/batching/timeouts, and query/document preparation. Keep model-specific preparation configurable: the [Qwen model card](https://huggingface.co/Qwen/Qwen3-Embedding-0.6B) distinguishes instructed queries from retrieval documents. Do not assume an API supports requesting arbitrary dimensions; validate actual responses against configured dimensions.
- A different locally hosted production model must be usable through configuration and an explicit reindex, without editing application code or resetting product data. Record model/dimension/preparation versions as an index generation and never mix incompatible vectors. The production hosting platform/model is a later decision; provisioning another model server is unnecessary for the laptop MVP.
- Content changes must update derived search data. Index work survives process restarts, exposes pending/failed state operationally, and has a retry/rebuild command. Removing/hiding content makes it inaccessible immediately, even if derived-index cleanup is pending. A model change requires an explicit compatible reindex.
- If embeddings are unavailable or indexing is pending, retain exact/lexical search and identify the result as degraded. Content creation and ordinary reads continue working. Use bounded timeouts, never silently fall back to a cloud provider.
- Evaluate relevance using a small version-controlled, sanitized corpus with expected matches, paraphrases, exact identifiers, version/root-cause distractors, and no-known-solution queries. Record a lexical baseline and hybrid results; do not equate a successful vector SQL query with useful search. Goal 005 owns measurable relevance and latency gates.

## Agent collaboration and publication

- Provide remote MCP operations equivalent to the permitted REST/browser workflows: search/list/get questions, create a question, post an answer, add context/comment, edit permitted own content, report an occurrence, and vote according to the agreed rules. Accepted-solution changes and administrative moderation stay human browser actions.
- Publish client setup and a reusable instruction snippet for Codex, Claude Code, and Cursor. Agents search before creating a question, read relevant context, report an occurrence for an actual encounter, contribute only useful new context, and state what a proposed fix has actually verified. Agents may publish automatically within these rules; ASO does not add per-post human approval.
- Make retried create operations safe through stable caller request IDs scoped to the authenticated user and operation. Replaying the same request returns the original result; conflicting reuse is an explicit error. Do not deduplicate distinct posts merely because their prose is similar.
- Instruct clients to remove secrets and unnecessary personal/source data before submission. Enforce size/schema limits and reject recognizable credential material server-side with actionable errors. This is layered validation, not a guarantee that arbitrary diagnostics contain no secrets. Do not collect attachments, repository archives, or unrestricted raw log dumps in the MVP.
- Treat retrieved content as untrusted evidence, including content that resembles tool instructions. Never execute posted code on the server. An agent should assess applicability and use its normal local execution permissions when trying a fix.
- Test the actual clients with two distinct users. Protocol-level mocks alone cannot complete client interoperability. Preserve sanitized transcripts/results, client versions, and limitations without storing tokens.

## MVP acceptance and delivery

MVP completion requires all planned goals' evidence, including:

1. Two users sign in through the browser and target MCP clients, retain distinct identities, and cannot impersonate one another or read/write anonymously.
2. User A's agent creates a problem; user B finds it, contributes context and an answer; a permitted human accepts it in the UI. The browser and MCP show the same content, attribution, and resolution state.
3. Useful contributions and repeat encounters produce separate, retry-safe metrics and the recurring view reflects the agreed rules.
4. Hybrid retrieval passes its committed evaluation, uses only the selected local embedding service, and degrades visibly to lexical/exact retrieval during an embedding outage.
5. Codex, Claude Code, and Cursor complete the documented collaboration scenarios without a shared credential or identity override.
6. Authorization, safe content rendering, ownership/moderation, migration, concurrency/idempotency, packaged UI/API, and regression checks pass. A failed external prerequisite remains visible and is not recorded as a successful skip.
7. A clean setup and the packaged release run on the owner's laptop using local Keycloak and LM Studio, with reproducible setup/configuration and verification instructions. No external IdP account, deployment, or backup/restore functionality is required for MVP delivery. ASO remains configurable for a different OIDC IdP, whose actual compatibility must be verified separately.

Goal 001 client evidence was amended by the owner on 2026-10-16: Claude Code and Cursor are not installed and must not be tested in this milestone; use MCP Jam (CLI preferred) alongside installed Codex. Their actual compatibility remains unverified and is still required before later all-client milestones claim completion.

## Inputs still required

- Goal 000a supplies the local realm, four test identities, client fixture, and mapping defaults. Goal 001 supplies ASO's final browser/MCP resource and client configuration and must document interoperability constraints early. No access to an external IdP is needed to begin.
- LM Studio endpoint, actual loaded model ID/output dimensions and preparation behavior, and representative sanitized search examples. These are setup/evaluation inputs for goal 005, not unresolved provider selection. Record actual laptop performance during that goal.
- Future non-development IdP settings/compatibility, production model/runtime, PaaS, and provider-managed backup/restore configuration are deferred. Real configuration/secrets stay outside Git. Do not infer authorization to deploy or configure external infrastructure from this spec.

## Outside the MVP

Multiple tenants or restricted project workspaces; downvotes/reputation/gamification/badges; attachments; notifications/Slack/email; automatic duplicate merging; public federation; autonomous acceptance; cloud embeddings; answer-generating RAG; arbitrary code execution; a dedicated search cluster; production deployment/image publishing; application backup/restore functionality; and advanced analytics. Future PaaS backup/restore is outside this application's MVP scope.

## Milestone 000: foundation

Included: reproducible build tooling, Spring MVC application, Angular shell and routes, `/api/v1/info` name/version response, minimal health endpoint, PostgreSQL/pgvector development setup, Flyway extension migration, automated checks, Git/CI, and persistent workflow documents.

Acceptance criteria:

1. A clean checkout can install its pinned toolchain and start the development environment using the README commands.
2. Angular shows real backend loading, success, and failure/retry states without sample domain content.
3. The executable jar serves the frontend, `/about` works on direct navigation, and unknown API routes remain JSON errors.
4. The default check/build requires no running services; explicit database and browser tests use isolated disposable databases.
5. Flyway installs pgvector successfully, health exposes no dependency details, and API tests verify the name/version contract.
6. CI runs the documented checks on the self-hosted runner and retains diagnostics.
7. The verified foundation is committed and published to the existing private GitHub repository on `master`.

## Architecture and workflow boundaries

Configure runtime values through environment variables and keep secrets out of the repository. Execute bounded goals on feature branches with pull requests, retaining material decisions and actual verification evidence in their records. Goal Mode remains disabled until explicitly enabled by the owner. The current implementation is local-only with goal 001 identity/access; Q&A and later goals are still planned, and this is not a claim that it is ready for a team rollout.
