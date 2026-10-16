# 000 — Foundation and development workflow

Status: complete

## Objective

Deliver the runnable, database-ready foundation defined in `spec.md`, before product features are specified or Goal Mode is enabled.

## Included and excluded work

Include Spring/Angular/Material scaffolding, a minimal info endpoint and health, the local PostgreSQL/pgvector setup, migration tooling, automated checks, repository documentation, and initial Git publication. Exclude authentication implementation, domain operations, MCP tools, search, deployment, and automatic image publishing.

## Acceptance criteria

See the seven foundation outcomes in `spec.md`. The packaged application must be exercised with Chromium against a real isolated database, not only mocked API responses.

## Verification

- `./gradlew --no-daemon check bootJar`
- `./gradlew --no-daemon integrationTest`
- `./gradlew --no-daemon e2eTest`
- Clean-checkout setup/development smoke check and inspection of desktop/mobile browser output.
- Initial commit and remote branch verification.

## Material decisions

- Owner selected a database-ready shell, Angular Material, one deployable application, self-hosted CI, and initial publication directly to `master` (2026-10-16 planning discussion).
- Match the reference backend's Java 25, Kotlin 2.3.21, Boot 4.1.1, and Gradle 9.2.1. Use Angular/Material 22.2.3 and project-managed Node 24.22.0/npm 11.19.1; preserve the machine's global Node installation.
- Use Playwright's JVM bindings for the packaged-jar browser test so Gradle can manage the jar process, disposable database, browser, and cleanup in one test lifecycle.
- Pin pgvector's multi-platform image digest for both development and tests. No domain tables or jOOQ code generation are needed for this milestone.
- Docker Desktop was stopped initially and was started for verification. The owner resolved the SSH-agent signing issue and published the initial foundation commit; SSH remote verification then succeeded.
- Set `BP_JVM_VERSION=25` explicitly for container builds, keeping the runtime requirement visible alongside the compile toolchain. The built image's actual runtime was verified as Java 25.0.4.

## Progress and blockers

Foundation implemented and verified. Product feature definition is the next milestone; Goal Mode remains disabled. Remote Actions status could not be inspected because the GitHub connector lacks permission for commit statuses. The self-hosted runner must have the documented Chromium system dependencies.

## Completion evidence

Verified on 2026-10-16:

- `./gradlew --no-daemon check bootJar`: passed; three Kotlin API/routing tests and two Angular loading/success/failure/retry tests, lint, formatting, production frontend build, and executable jar.
- `./gradlew --no-daemon integrationTest`: passed; two tests against isolated PostgreSQL 18.6, confirming startup, Flyway history, vector operations, and minimal health.
- `./gradlew --no-daemon e2eTest`: passed; Chromium exercised the executable jar, real API, direct `/about` navigation, mobile layout, unknown API JSON errors, and browser console errors. Desktop/mobile screenshots and a trace are generated under `backend/build/e2e/`.
- All three commands also passed together after the final dependency/tooling adjustments, with no skipped tests.
- Exported a clean copy from Git's index with no build artifacts or local configuration. `./scripts/setup.sh` passed. The default check/build passed with `DOCKER_HOST` deliberately pointing to an unavailable socket, confirming it requires no Docker service.
- Started `./scripts/dev.sh` from the clean copy. The frontend proxy returned HTTP 200 for real info and health responses, and direct `/about` navigation returned the Angular page. Ctrl-C stopped both application listeners; the local database volume was retained.
- `./gradlew --no-daemon bootBuildImage`: passed. The resulting image runs Java 25.0.4; a temporary container served the API and frontend successfully, then was stopped.
- Shell syntax, Compose configuration, workflow YAML, and Git whitespace checks passed.
- Initial foundation commit `049be613360f46ddc5def3f35012ed33df6ff31a` was committed/pushed by the owner and independently matched `refs/heads/master` through SSH. The follow-up runtime pin and this evidence are recorded in the commit containing this record.
