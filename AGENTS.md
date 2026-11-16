# Working on ASO

ASO (Agentic Stack Overflow) is a private engineering knowledge space for coding agents and humans.
Read `README.md`, `spec.md`, and the applicable record in `docs/goals/` before making changes.

## Setup and verification

- Use Java 25 and the checked-in Gradle 9.2.1 wrapper. Gradle provisions Node 24.22.0 (bundled npm 11.19.1); no global Node changes are needed.
- `./scripts/setup.sh` checks prerequisites and installs the frontend dependencies. `./scripts/dev.sh` starts the local database and both development servers.
- Goal `000a-local-keycloak` supplies the seeded local IdP before goal 001. Use `./scripts/keycloak-start.sh` and the lifecycle commands in README. Goal 001 adds ASO authentication; use the explicit development profile for local runtime. Follow the development/test identity contract in `spec.md`.
- `./gradlew --no-daemon check bootJar` runs backend/frontend unit tests, lint, formatting checks, and packages the application. It needs no Docker, database, credentials, OIDC, or embedding provider. First use downloads dependencies.
- `./gradlew --no-daemon integrationTest` uses an isolated pgvector Testcontainer. `./gradlew --no-daemon e2eTest` runs Chromium against the actual executable jar and another isolated database. Docker is required; unavailable prerequisites are failures, not successful skips.
- Format with `./gradlew spotlessApply frontendFormat`. Do not replace meaningful assertions with assertions that only mirror the implementation.

## Architecture

- Backend: Kotlin/Spring MVC, package `com.functionaldude.aso`; frontend: Angular standalone components, strict TypeScript, signals, Angular Material, SCSS.
- Keep short Kotlin conditions and catch declarations on one line. In fluent jOOQ queries, put clauses on separate lines while keeping short argument lists with their call. Review formatter output for unnecessary wrapping.
- Prefer immutable constructor-injected class properties for stable dependencies and reusable configuration. Keep request-specific values in method parameters or local variables; never cache authentication in singleton controllers. Continue resolving `Authentication` through per-handler parameters.
- Use Kotlin data classes for known request, response, and nested payload structures. Do not pass `JsonNode` or untyped maps through application services. Restrict raw JSON to genuinely dynamic protocol boundaries, document the reason, and convert to typed inputs before application logic. Maps are appropriate for dynamic JSON Schema properties.
- Group new code by feature. REST and future MCP adapters must use shared application services and the same authorization rules.
- The production jar contains Angular's browser output. Keep explicit SPA forwarding routes in sync with Angular routes. Never forward API, Actuator, or missing asset paths to `index.html`.
- Use relative `/api/v1` URLs in Angular. The development proxy targets localhost:8080; production uses the same origin.
- Goal 001 provides browser-session and MCP-bearer authentication, with no Q&A operations yet. Preserve individual identity for all product writes. Authorship must come from a validated principal, never an arbitrary caller-supplied user ID.
- A vote measures usefulness; an occurrence measures another encounter with a problem. Keep those separate when the domain is designed.
- Follow the MVP rules in `spec.md`: one private workspace, own-content editing, human owner/admin acceptance, upvotes with undo/no self-voting, and retry-safe encounters with separate distinct-user counts. Use vendor-neutral OIDC IdP configuration.
- Use seeded local Keycloak for development and isolated Keycloak fixtures for integration/browser authentication tests. Default JWT authentication stays provider-neutral with issuer/audience validation. Goal 016 explicitly adds opt-in Okta org introspection with issuer/client binding and userinfo identity; do not turn it into an unchecked audience bypass. Real client interoperability against local Keycloak remains required; another IdP needs its own later verification.
- Goal 006/laptop MVP verification uses actual Codex and MCP Jam CLI, with explicit unavailable-client workflow simulations. Do not require/install native Claude Code/Cursor for this amended scope or claim their compatibility. The user-facing setup guide and recommended consuming-repository paragraph live in `docs/agent-setup.md`; goal 006 must expose them in the web UI with accurate non-secret configuration and usable copy controls.
- The default local embedding provider stays on locally hosted infrastructure with its internal-host restrictions. Initial development uses LM Studio; keep endpoint/model/dimensions and model-specific input preparation configurable. Goal 015 explicitly adds an opt-in Vertex provider for the owner's GCP deployment, authenticated through Application Default Credentials. Model/provider changes require an explicit compatible reindex, not a product-data reset or implicit cloud fallback.

## Data, dependencies, and generated files

- Flyway owns schema evolution. Add a migration for intentional schema changes; never rewrite an applied migration. Use jOOQ for application database access.
- Flyway V2 owns the first identity table. `./gradlew generateJooq` parses the deliberately selected Flyway DDL offline into committed Kotlin bindings; expand inputs deliberately for future tables. Keep output reproducible and generator-formatted. Ordinary builds must not introspect a developer's database.
- Keep the database image digest in `compose.yaml` and `gradle/database-image.txt` aligned when intentionally upgrading it.
- Commit npm's lockfile and the Gradle wrapper, including its jar. Build output, dependency caches, browser traces, local `.env` files, credentials, and IDE state stay untracked.
- Intentionally public fictitious development-only IdP passwords/client secrets may live in labelled seed fixtures. Never copy real/personal/production credentials into them or make them production defaults. Pin the Keycloak image consistently between Compose and isolated tests.
- Tests do not load `.env` and must never depend on or mutate the local development database. The explicit reset script deletes only the Compose project's local volume.
- Adding Keycloak must preserve independent lifecycles: database reset deletes only database data; Keycloak reset deletes only Keycloak state. Do not keep a broad `docker compose down --volumes` reset after adding another persistent service. Test reset isolation using disposable projects, never the owner's data.
- Prefer Spring Boot-managed backend dependency versions. Do not add infrastructure or frameworks for hypothetical features.

## Goals and handoff

- `spec.md` owns product intent and requirements. Goal records own bounded scope, acceptance criteria, material decisions, verification evidence, and remaining blockers. Keep README commands accurate.
- Read `docs/goals/README.md` for order/dependencies. Planned records do not start feature implementation or enable Goal Mode. The MVP ends at a tested laptop release; deployment and application backup/restore are outside its scope.
- Create a goal record from the template when a user-defined implementation milestone begins. Do not turn proposed feature areas into approved implementation work.
- Continue routine work autonomously within the agreed scope. Raise unresolved choices that materially change product behavior, architecture, or requirements.
- For later goals use a feature branch and pull request. Do not force-push, merge, publish deployments, or enable Goal Mode as a side effect of implementation.
- Update material decisions and evidence before handoff. Report failed or unavailable checks honestly; do not mark a goal complete merely because code exists.
