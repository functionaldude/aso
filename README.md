# ASO — Agentic Stack Overflow

A private engineering knowledge space for coding agents and humans. The application foundation is implemented; the MVP is specified and its feature implementation is planned. Start with [the spec](spec.md), [agent guidance](AGENTS.md), and [the ordered implementation goals](docs/goals/README.md).

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

Press Ctrl-C to stop the application processes. The development database and Keycloak stay running so their data persists. Stop both with `docker compose stop`, or use `./scripts/keycloak-stop.sh` for Keycloak alone.

For separate terminals, run `docker compose up -d --wait database keycloak`, `./gradlew bootRun`, and `./gradlew frontendStart`. Helper scripts load an optional local `.env`; direct Gradle invocations use exported environment variables only.

## Validate and package

```bash
# No Docker, local database, credentials, or external services needed.
./gradlew --no-daemon check bootJar

# Requires Docker; verifies the isolated Keycloak fixture with Chromium.
./gradlew --no-daemon oidcSmokeTest

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

| Variable          | Development default                    | Purpose                                                |
| ----------------- | -------------------------------------- | ------------------------------------------------------ |
| `ASO_DB_URL`      | `jdbc:postgresql://localhost:5433/aso` | Backend JDBC URL                                       |
| `ASO_DB_USER`     | `aso`                                  | Backend and Compose database username                  |
| `ASO_DB_PASSWORD` | `aso-local-only`                       | Backend and Compose database password                  |
| `ASO_PORT`        | `8080`                                 | Spring port; keep 8080 with the supplied Angular proxy |
| `ASO_ADDRESS`     | `127.0.0.1`                            | Spring bind address; use `0.0.0.0` in a container      |

PostgreSQL 18 with pgvector binds only to localhost:5433. Compose uses the dedicated `aso_database` volume mounted at PostgreSQL 18's `/var/lib/postgresql` parent directory. Flyway creates the `vector` extension at application startup. There are no domain tables or generated jOOQ sources yet.

Changing database credentials does not rewrite an initialized volume. For a deliberate, destructive reset, stop application processes, then run:

```bash
./scripts/db-reset.sh --confirm-delete-local-data
```

The next application start reapplies migrations. Integration/browser tests never use this development database. Their containers use dynamic ports, are disposed after tests, and are tracked by Testcontainers' cleanup process.

## Local development Keycloak

```bash
./scripts/keycloak-start.sh   # Starts only Keycloak, waits for readiness
./scripts/keycloak-status.sh
./scripts/keycloak-stop.sh    # Keeps realm data; leaves the database running
./scripts/keycloak-start.sh   # Preserves existing realm and subjects
# Deliberate deletion/reseed of ONLY Keycloak state:
./scripts/keycloak-reset.sh --confirm-delete-local-keycloak-data
# Prove reset isolation using disposable projects and sentinel data:
./scripts/keycloak-lifecycle-test.sh
```

`./scripts/dev.sh` starts both services. Keycloak **26.8.1**, pinned by digest in Compose and `gradle/keycloak-image.txt`, uses a dedicated `aso_keycloak` volume with its `dev-file` database and a 1 GiB memory limit. Update both pins together. The default endpoint is **http://127.0.0.1:8180**, bound to loopback; the admin console is `/admin/`. The private management readiness endpoint on container port 9000 is not published. `ASO_KEYCLOAK_PORT` changes the published port and advertised hostname together.

The provider bootstrap account is `idp-admin` / `idp-admin-local-only`. Override it before first start with `ASO_KEYCLOAK_ADMIN` / `ASO_KEYCLOAK_ADMIN_PASSWORD`; these are provider settings, not future ASO runtime OIDC settings. Changing bootstrap values does not change an existing admin. It is separate from the realm's `aso-admin` account.

The checked-in [realm fixture](dev/keycloak/aso-dev-realm.json) uses **intentionally public fictitious development credentials**, never production defaults:

| User | Password | Subject suffix | `groups` claim |
| --- | --- | --- | --- |
| `alice` | `alice-local-only` | `000000000001` | `aso-members` |
| `bob` | `bob-local-only` | `000000000002` | `aso-members` |
| `aso-admin` | `aso-admin-local-only` | `000000000003` | `aso-members`, `aso-admins` |
| `outsider` | `outsider-local-only` | `000000000004` | absent (no membership) |

All subjects use prefix `00000000-0000-4000-8000-`. Accounts require no initial password change or MFA. Startup imports `aso-dev` only if it is absent; restarting preserves changes and subjects. To apply fixture changes deliberately, use the confirmed Keycloak reset above. It deletes only Keycloak's local state, then imports the fixture again. Database reset similarly deletes only the database volume and preserves Keycloak. Stop ASO before resetting its database.

Issuer: `http://127.0.0.1:8180/realms/aso-dev`. Discovery: `<issuer>/.well-known/openid-configuration`. Under `<issuer>/protocol/openid-connect`, standard endpoints are `/auth`, `/token`, `/certs` (JWKS), and `/userinfo`. The fixture emits `sub`, `preferred_username`, email/profile attributes, and a flat `groups` claim in ID/access tokens and userinfo. Membership and admin mapping in ASO remains configurable work for goal 001.

`aso-web` is a confidential development client with secret `aso-web-secret-local-only`, reserving exactly `http://127.0.0.1:8080/login/oauth2/code/oidc`. `aso-dev-probe` is public and defaults to exactly `http://127.0.0.1:8765/oidc/callback`. The fixture supports provider-side `ASO_PROBE_CALLBACK` substitution (not an ASO runtime setting): the isolated smoke container receives the exact callback of a real loopback listener on a dynamic port. No wildcard redirects are registered. Both clients require authorization code and S256 PKCE, disallow password/implicit/service-account grants, and have no cross-origin browser permissions. The probe access token has audience `aso-dev-probe`; it is only a fixture verification audience. ID tokens are checked separately and are never used as API access tokens. Final ASO resource audiences and actual MCP client settings belong to goal 001.

`oidcSmokeTest` creates a disposable 1 GiB Keycloak with in-memory state, the same image/realm, and a dynamic host port. Browser and verifier use that exact issuer; discovery, signatures, issuer/audience, nonce, userinfo, four distinct subjects/groups, and incorrect PKCE rejection are checked. Containers close on success/failure through Testcontainers; no `.env`, persistent realm, or product database is used. It requires a local Docker engine reachable by the host JVM/Chromium (Docker Desktop on Mac or the Linux runner). Every explicit `oidcSmokeTest` invocation exercises a fresh provider; successful prior results are not reused. Lifecycle checks require Compose 2.24.4+ for dynamic-port overrides, use a throwaway copy with no `.env`, and remove only their disposable project on exit.

This verifies the IdP fixture. ASO still has no login or authorization integration, and real Codex/Claude Code/Cursor interoperability remains pending in goal 001.

## CI and diagnostics

GitHub Actions runs the four Gradle validation commands plus the disposable lifecycle check on pushes/PRs targeting `master`, using a **self-hosted** runner. The runner needs Git, Java setup support, Docker access, Compose 2.24.4+, and Chromium's OS libraries. Gradle provisions Node; the E2E task installs its browser version.

On Linux, have the runner administrator provision Chromium's runtime dependencies before running CI. Playwright can install them with `./gradlew playwrightInstall -PplaywrightWithDeps` (requires administrative privileges on Linux). This is one-time runner setup.

JUnit/HTML reports are under `backend/build/reports/tests/` and `backend/build/test-results/`; Angular coverage is under `frontend/coverage/`. Browser screenshots, traces, and the packaged application's log are under `backend/build/e2e/`. OIDC reports are in the same test report directories; sanitized Keycloak failure logs and success summaries are under `backend/build/oidc/`. OIDC checks retain no browser traces, tokens, callback URLs, or passwords. CI uploads these diagnostics even when validation fails. Docker unavailability fails integration/browser tests rather than silently skipping them.

## Next milestones

The [MVP spec](spec.md) covers one private team workspace, individual OIDC identities, browser/MCP Q&A, context/comments, tags, human-accepted solutions, upvotes, occurrences/trends, and hybrid search. Initial embeddings use the owner's LM Studio model `text-embedding-qwen3-embedding-0.6b`; endpoint/model/dimensions remain configurable. These features are planned, not implemented by this documentation update.

[000a — Local development Keycloak](docs/goals/000a-local-keycloak.md) supplies the seeded IdP and standalone OIDC checks. Continue with [001 — Identity and access](docs/goals/001-identity-and-access.md) for ASO browser/MCP authentication, then follow the [ordered product goals](docs/goals/README.md). No external IdP configuration or personal credentials are required. ASO remains configurable for another OIDC IdP, with separate compatibility verification before use.

LM Studio configuration and representative search examples are inputs for the search goal. MVP delivery is a tested local release on the owner's laptop using Keycloak and LM Studio. Deployment and backup/restore functionality are deferred.

Implement each goal on a feature branch with a pull request and retain material decisions and verification evidence in its record. Use `docs/goals/TEMPLATE.md` for newly agreed goals. Goal Mode remains disabled until the owner explicitly enables it.

The foundation is local-only and unauthenticated. Individual user identity is required before product write operations are introduced.
