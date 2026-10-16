# Working on ASO

ASO (Agentic Stack Overflow) is a private engineering knowledge space for coding agents and humans.
Read `README.md`, `spec.md`, and the applicable record in `docs/goals/` before making changes.

## Setup and verification

- Use Java 25 and the checked-in Gradle 9.2.1 wrapper. Gradle provisions Node 24.22.0 (bundled npm 11.19.1); no global Node changes are needed.
- `./scripts/setup.sh` checks prerequisites and installs the frontend dependencies. `./scripts/dev.sh` starts the local database and both development servers.
- `./gradlew --no-daemon check bootJar` runs backend/frontend unit tests, lint, formatting checks, and packages the application. It needs no Docker, database, credentials, OIDC, or embedding provider. First use downloads dependencies.
- `./gradlew --no-daemon integrationTest` uses an isolated pgvector Testcontainer. `./gradlew --no-daemon e2eTest` runs Chromium against the actual executable jar and another isolated database. Docker is required; unavailable prerequisites are failures, not successful skips.
- Format with `./gradlew spotlessApply frontendFormat`. Do not replace meaningful assertions with assertions that only mirror the implementation.

## Architecture

- Backend: Kotlin/Spring MVC, package `com.functionaldude.aso`; frontend: Angular standalone components, strict TypeScript, signals, Angular Material, SCSS.
- Group new code by feature. REST and future MCP adapters must use shared application services and the same authorization rules.
- The production jar contains Angular's browser output. Keep explicit SPA forwarding routes in sync with Angular routes. Never forward API, Actuator, or missing asset paths to `index.html`.
- Use relative `/api/v1` URLs in Angular. The development proxy targets localhost:8080; production uses the same origin.
- The foundation has no authentication or domain operations. Preserve the requirement for individual identity before adding product writes. Authorship must come from a validated principal, never an arbitrary caller-supplied user ID.
- A vote measures usefulness; an occurrence measures another encounter with a problem. Keep those separate when the domain is designed.

## Data, dependencies, and generated files

- Flyway owns schema evolution. Add a migration for intentional schema changes; never rewrite an applied migration. Use jOOQ for application database access.
- No domain schema or jOOQ generation exists yet. Introduce generation deliberately with the first data model; ordinary builds must not introspect a developer's database.
- Keep the database image digest in `compose.yaml` and `gradle/database-image.txt` aligned when intentionally upgrading it.
- Commit npm's lockfile and the Gradle wrapper, including its jar. Build output, dependency caches, browser traces, local `.env` files, credentials, and IDE state stay untracked.
- Tests do not load `.env` and must never depend on or mutate the local development database. The explicit reset script deletes only the Compose project's local volume.
- Prefer Spring Boot-managed backend dependency versions. Do not add infrastructure or frameworks for hypothetical features.

## Goals and handoff

- `spec.md` owns product intent and requirements. Goal records own bounded scope, acceptance criteria, material decisions, verification evidence, and remaining blockers. Keep README commands accurate.
- Create a goal record from the template when a user-defined implementation milestone begins. Do not turn proposed feature areas into approved implementation work.
- Continue routine work autonomously within the agreed scope. Raise unresolved choices that materially change product behavior, architecture, or requirements.
- For later goals use a feature branch and pull request. Do not force-push, merge, publish deployments, or enable Goal Mode as a side effect of implementation.
- Update material decisions and evidence before handoff. Report failed or unavailable checks honestly; do not mark a goal complete merely because code exists.
