# ASO — Agentic Stack Overflow

A private engineering knowledge space for coding agents and humans. This repository contains the application foundation; product features will be specified next. Start with [the spec](spec.md), [agent guidance](AGENTS.md), and [the foundation milestone](docs/goals/000-foundation.md).

## Prerequisites

- **Java 25 JDK** on `PATH` (or selected by `JAVA_HOME`). Gradle 9.2.1 is included.
- **Docker** with a running engine and Compose v2 for development, integration tests, and browser tests.
- Internet access for the first dependency, toolchain, browser, and image downloads.

Gradle downloads **Node 24.22.0 with npm 11.19.1** into `.gradle/nodejs`; your global Node installation is not used or changed. Angular and Material are 22.2.3. The backend uses Kotlin 2.3.21, Spring Boot 4.1.1, Flyway, and jOOQ.

## Start from a clean checkout

```bash
git clone git@github.com:functionaldude/aso.git
cd aso
./scripts/setup.sh
./scripts/dev.sh
```

Open **http://127.0.0.1:4200**. Spring runs at **http://127.0.0.1:8080**, and Angular proxies `/api/**` and `/actuator/**` to it. The page connects to the real backend and has an empty workspace; questions and authentication are not implemented yet.

Press Ctrl-C to stop the application processes. The development database stays running so its data persists. Stop it separately with `docker compose down`.

For separate terminals, run `docker compose up -d --wait database`, `./gradlew bootRun`, and `./gradlew frontendStart`. Helper scripts load an optional local `.env`; direct Gradle invocations use exported environment variables only.

## Validate and package

```bash
# No Docker, local database, credentials, or external services needed.
./gradlew --no-daemon check bootJar

# Requires Docker; uses isolated disposable PostgreSQL/pgvector.
./gradlew --no-daemon integrationTest

# Requires Docker; installs Chromium and tests the executable jar.
./gradlew --no-daemon e2eTest
```

The default check includes Kotlin/JUnit and Angular/Vitest tests, ESLint, Spotless/ktlint, Prettier, and the production Angular build. Format changes with `./gradlew spotlessApply frontendFormat`.

The jar is **`backend/build/libs/aso.jar`** and contains Angular's browser assets:

```bash
docker compose up -d --wait database
java -jar backend/build/libs/aso.jar
```

Open http://127.0.0.1:8080. Direct navigation to `/about` works. `/api/v1/info` returns application name/version; `/actuator/health` returns minimal health. Unknown API routes return JSON problem responses rather than the frontend.

Build one local OCI application image with `./gradlew bootBuildImage` (Docker required). To run it against the Compose database:

```bash
docker run --rm --network aso_default -p 127.0.0.1:8080:8080 \
  -e ASO_ADDRESS=0.0.0.0 \
  -e ASO_DB_URL=jdbc:postgresql://database:5432/aso \
  -e ASO_DB_USER=aso -e ASO_DB_PASSWORD=aso-local-only \
  aso:0.0.1-SNAPSHOT
```

Substitute your configured database credentials if you changed the local defaults. No image registry publication or deployment runs automatically.

## Local configuration and database lifecycle

Defaults work without a `.env`. For overrides, copy `.env.example` to `.env`; it is ignored by Git. Helper scripts treat this file as trusted shell configuration. Tests and Gradle never load it.

| Variable | Development default | Purpose |
| --- | --- | --- |
| `ASO_DB_URL` | `jdbc:postgresql://localhost:5433/aso` | Backend JDBC URL |
| `ASO_DB_USER` | `aso` | Backend and Compose database username |
| `ASO_DB_PASSWORD` | `aso-local-only` | Backend and Compose database password |
| `ASO_PORT` | `8080` | Spring port; keep 8080 with the supplied Angular proxy |
| `ASO_ADDRESS` | `127.0.0.1` | Spring bind address; use `0.0.0.0` in a container |

PostgreSQL 18 with pgvector binds only to localhost:5433. Compose uses the dedicated `aso_database` volume mounted at PostgreSQL 18's `/var/lib/postgresql` parent directory. Flyway creates the `vector` extension at application startup. There are no domain tables or generated jOOQ sources yet.

Changing database credentials does not rewrite an initialized volume. For a deliberate, destructive reset, stop application processes, then run:

```bash
./scripts/db-reset.sh --confirm-delete-local-data
```

The next application start reapplies migrations. Integration/browser tests never use this development database. Their containers use dynamic ports, are disposed after tests, and are tracked by Testcontainers' cleanup process.

## CI and diagnostics

GitHub Actions runs the three validation commands on pushes/PRs targeting `master`, using a **self-hosted** runner. The runner needs Git, Java setup support, Docker access, and Chromium's OS libraries. Gradle provisions Node; the E2E task installs its browser version.

On Linux, have the runner administrator provision Chromium's runtime dependencies before running CI. Playwright can install them with `./gradlew playwrightInstall -PplaywrightWithDeps` (requires administrative privileges on Linux). This is one-time runner setup.

JUnit/HTML reports are under `backend/build/reports/tests/` and `backend/build/test-results/`; Angular coverage is under `frontend/coverage/`. Browser screenshots, traces, and the packaged application's log are under `backend/build/e2e/`. CI uploads these diagnostics even when validation fails. Docker unavailability fails integration/browser tests rather than silently skipping them.

## Next milestones

Define features with the project owner and update `spec.md`. Copy `docs/goals/TEMPLATE.md` into a numbered milestone, agree observable acceptance criteria, then implement on a feature branch with a pull request. Retain material decisions and verification evidence in that record. This bootstrap does not enable Goal Mode.

The foundation is local-only and unauthenticated. Individual user identity is required before product write operations are introduced.
