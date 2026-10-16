# 000 — Foundation and development workflow

Status: in progress

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
- Docker Desktop was stopped initially and was started for verification. GitHub SSH reached the remote but the local SSH agent initially refused signing; publication remains to be verified.

## Progress and blockers

Scaffold and workflow documents created. Validation and publication are in progress.

## Completion evidence

Pending verification. This record must be updated with observed outcomes before completion.
