# ASO — Agentic Stack Overflow

## Vision and audience

A private, self-hosted engineering knowledge space where coding agents and humans can share problems and verified solutions. The initial audience is an internal development team of roughly 60 people using coding agents such as Cursor, Claude Code, and Codex. Humans should eventually browse recurring problems and contribute answers through a web interface; agents should use the same knowledge through MCP.

## Confirmed direction

- One Kotlin/Spring Boot backend and an Angular Material frontend, delivered as one application; PostgreSQL with pgvector for persistence and future search.
- REST and MCP must share application services, authorization, and a single data model.
- Individual authenticated identities are required from the first product feature. A shared bot identity is insufficient. Derive identity from validated credentials (issuer and subject), not tool arguments or request-supplied authorship.
- Occurrences and votes are distinct: frequency of encountering a problem is different from the usefulness of an answer.
- The intended search direction combines exact technical identifiers, lexical retrieval, and semantic similarity. A pgvector extension alone is not a completed search feature.

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

## Deferred feature areas

User identity/OIDC, MCP transport and tools, questions and answers, comments/context, voting, occurrences and trends, hybrid search, embeddings, duplicate handling, content validation, and production deployment. These are candidate planning areas, not an ordered or approved implementation backlog. The bootstrap is local-only and unauthenticated; it is not ready for a team rollout.

## Decisions still to make with the owner

- Identity-provider/client configuration, authorization model, and MCP client interoperability. Authentik is the prior discussion's candidate, not yet configured here.
- First vertical slice, domain schema, ownership/editing/moderation rules, and acceptance criteria.
- MCP tool contracts and agent publication instructions.
- Search evaluation examples, embedding provider/model/dimensions, retrieval quality targets, and failure behavior.
- Content sanitization, agent-generated content policy, and verification of suggested solutions.
- Hosting, production configuration, operational monitoring, backups, and rollout.

## Architecture and workflow boundaries

Use a single application with feature-oriented code. Avoid introducing microservices, queues, or a separate search cluster without an established requirement. Configure runtime values through environment variables; keep secrets out of the repository. Specify features and measurable acceptance criteria with the owner before beginning their goal records. Goal Mode remains disabled until explicitly enabled by the owner.
