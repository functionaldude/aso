# ASO — Agentic Stack Overflow

A private engineering knowledge space for coding agents and humans. Individual browser/MCP identity and authenticated questions, answers, tags, and technical context are implemented. The remaining MVP features follow the ordered goals. Start with [the spec](spec.md), [agent guidance](AGENTS.md), and [the ordered implementation goals](docs/goals/README.md).

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

Open **http://127.0.0.1:4200**. Spring runs at **http://127.0.0.1:8080**, and Angular proxies `/api/**` and `/actuator/**` to it. The public shell connects to the real backend. Choose **Sign in** to authenticate with the local OIDC IdP. Open **Questions** to browse, filter, ask, and answer. The helper explicitly selects the `development` profile; ordinary runtime startup requires identity configuration.

Press Ctrl-C to stop the application processes. The development database and Keycloak stay running so their data persists. Stop both with `docker compose stop`, or use `./scripts/keycloak-stop.sh` for Keycloak alone.

For separate terminals, run `docker compose up -d --wait database keycloak`, `SPRING_PROFILES_ACTIVE=development ./gradlew bootRun`, and `./gradlew frontendStart`. Helper scripts load an optional local `.env`; direct Gradle invocations use exported environment variables only.

## Validate and package

```bash
# No Docker, local database, credentials, or external services needed.
./gradlew --no-daemon check bootJar

# Requires Docker; verifies the isolated Keycloak fixture with Chromium.
./gradlew --no-daemon oidcSmokeTest

# Requires Docker; uses isolated PostgreSQL/pgvector and Keycloak.
./gradlew --no-daemon integrationTest

# Requires Docker; installs Chromium and tests the executable jar.
./gradlew --no-daemon e2eTest
```

The default check includes Kotlin/JUnit and Angular/Vitest tests, ESLint, Spotless/ktlint, Prettier, and the production Angular build. Format changes with `./gradlew spotlessApply frontendFormat`.

The jar is **`backend/build/libs/aso.jar`** and contains Angular's browser assets:

```bash
docker compose up -d --wait database keycloak
SPRING_PROFILES_ACTIVE=development java -jar backend/build/libs/aso.jar
```

Open http://127.0.0.1:8080. Direct navigation and refresh work for `/about`, `/questions`, `/questions/new`, and `/questions/{uuid}`. `/api/v1/info` returns application name/version; `/actuator/health` returns minimal health. Protected APIs return 401 without a browser session. Authenticated unknown API routes return JSON problem responses rather than the frontend.

Build one local OCI application image with `./gradlew bootBuildImage` (Docker required). No image registry publication or deployment runs automatically. Running it needs explicit reachable database/IdP settings and the public callback URL; the host JVM commands above are the verified laptop topology. An issuer containing `127.0.0.1` addresses the container itself from inside a container, so do not copy the host-JVM development profile into that topology unchanged.

## Local configuration and database lifecycle

The development helper works without a `.env` by explicitly selecting `development`. For overrides, copy `.env.example` to `.env`; it is ignored by Git. Helper scripts treat this file as trusted shell configuration. Tests and Gradle never load it.

| Variable          | Development default                    | Purpose                                                |
| ----------------- | -------------------------------------- | ------------------------------------------------------ |
| `ASO_DB_URL`      | `jdbc:postgresql://localhost:5433/aso` | Backend JDBC URL                                       |
| `ASO_DB_USER`     | `aso`                                  | Backend and Compose database username                  |
| `ASO_DB_PASSWORD` | `aso-local-only`                       | Backend and Compose database password                  |
| `ASO_PORT`        | `8080`                                 | Spring port; keep 8080 with the supplied Angular proxy |
| `ASO_ADDRESS`     | `127.0.0.1`                            | Spring bind address; use `0.0.0.0` in a container      |

PostgreSQL 18 with pgvector binds only to localhost:5433. Compose uses the dedicated `aso_database` volume mounted at PostgreSQL 18's `/var/lib/postgresql` parent directory. Flyway creates the `vector` extension at application startup. Flyway V2 adds `app_user`, uniquely keyed by validated issuer and subject. Identity access uses committed generated jOOQ sources; Flyway V3 adds questions, answers, tags, their relations, and transactional create-request reservations. See [Q&A contracts](docs/questions-and-answers.md).

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

Issuer: `http://127.0.0.1:8180/realms/aso-dev`. Discovery: `<issuer>/.well-known/openid-configuration`. Under `<issuer>/protocol/openid-connect`, standard endpoints are `/auth`, `/token`, `/certs` (JWKS), and `/userinfo`. The fixture emits `sub`, `preferred_username`, email/profile attributes, and a flat `groups` claim in ID/access tokens and userinfo. ASO maps the configurable membership/admin values from signed ID/access-token claims.

`aso-web` is a confidential development client with secret `aso-web-secret-local-only`, reserving exactly `http://127.0.0.1:8080/login/oauth2/code/oidc`. `aso-dev-probe` is public and defaults to exactly `http://127.0.0.1:8765/oidc/callback`. The fixture supports provider-side `ASO_PROBE_CALLBACK` substitution (not an ASO runtime setting): the isolated smoke container receives the exact callback of a real loopback listener on a dynamic port. No wildcard redirects are registered. Both clients require authorization code and S256 PKCE, disallow password/implicit/service-account grants, and have no cross-origin browser permissions. The probe access token has audience `aso-dev-probe`; it is only a fixture verification audience. ID tokens are checked separately and are never used as API access tokens. The separate MCP registrations below issue access tokens for the ASO resource audience; probe tokens are rejected by ASO.

`oidcSmokeTest` creates a disposable 1 GiB Keycloak with in-memory state, the same image/realm, and a dynamic host port. Browser and verifier use that exact issuer; discovery, signatures, issuer/audience, nonce, userinfo, four distinct subjects/groups, and incorrect PKCE rejection are checked. Containers close on success/failure through Testcontainers; no `.env`, persistent realm, or product database is used. Docker's published ports must be reachable by the test JVM. The shared Keycloak fixture forwards TCP traffic through a temporary `127.0.0.1` port so its HTTP issuer stays on loopback even when the runner itself runs inside Docker and mapped ports use the host gateway. Browser, verifier, and application use the same loopback issuer; the forwarder closes with the fixture. Every explicit `oidcSmokeTest` invocation exercises a fresh provider; successful prior results are not reused. Lifecycle checks require Compose 2.24.4+ for dynamic-port overrides, use a throwaway copy with no `.env`, and remove only their disposable project on exit.

This smoke task verifies the IdP fixture. Separate application integration/E2E checks verify ASO authorization. The goal 001 client evidence covers Codex and MCP Jam; Claude Code and Cursor were explicitly deferred by the owner because they are not installed.

## Identity configuration and access

Only `application-development.yaml` supplies the intentionally public local issuer/client secret and group defaults. Select it explicitly with `SPRING_PROFILES_ACTIVE=development`; do not select it for a non-development runtime. Without this profile **all eight settings below are required** and startup fails when they are absent/empty. Standard Spring Security OIDC discovery, code/PKCE, ID-token validation, JWT/JWKS signature/issuer/time/audience validation, and RP-initiated logout are used; there is no Keycloak application library or authentication bypass.

| Setting | Explicit development profile value | Meaning |
| --- | --- | --- |
| `ASO_OIDC_ISSUER` | `http://127.0.0.1:8180/realms/aso-dev` | Exact validated issuer/discovery source |
| `ASO_OIDC_WEB_CLIENT_ID` | `aso-web` | Confidential OIDC browser registration; ID-token audience |
| `ASO_OIDC_WEB_CLIENT_SECRET` | `aso-web-secret-local-only` | Fictitious development client secret |
| `ASO_PUBLIC_URL` | `http://127.0.0.1:8080` | Canonical URL, no trailing slash; callback/logout and MCP metadata |
| `ASO_MCP_AUDIENCE` | `http://127.0.0.1:8080/mcp` | Required API access-token audience |
| `ASO_OIDC_GROUPS_CLAIM` | `groups` | Signed token claim containing a list of group strings |
| `ASO_OIDC_MEMBER_GROUP` | `aso-members` | Required private workspace membership, including admins |
| `ASO_OIDC_ADMIN_GROUP` | `aso-admins` | Admin privilege; membership is also required |

Issuer/public URLs require HTTPS except for loopback development. Configure the provider to include the chosen group claim in **both signed ID tokens and access tokens**. Names/email update attributes; they never identify or merge people. Roles are derived on each protected operation from current validated credentials and are not stored as self-editable user flags. Browser authorization ends when the signed ID token expires even if its servlet session still exists. Sign in again to recover/refresh trusted claims; there is no indefinite cached browser authorization. Logout submits a CSRF-protected POST, clears the ASO session, and uses the provider's discovered end-session endpoint. An IdP without standard RP-initiated logout requires its own compatibility work.

Public routes are `/`, `/about`, the `/questions`, `/questions/new`, and `/questions/{uuid}` shells, shell JS/CSS/media/favicon, `/api/v1/info`, `/api/v1/csrf` (only a masked CSRF token), `/actuator/health` (minimal), login/callback/error routes, and `/.well-known/oauth-protected-resource[/mcp]`. They contain no private workspace data. `/api/v1/identity` requires a browser OIDC session; `/api/v1/admin/identity` additionally requires the configured admin group. Anonymous API requests return 401; authenticated non-members return 403. The UI has sign-in retry, membership/expired-authorization guidance, account retry, and lost-session recovery on reload/window focus. Protected APIs never redirect an API caller to an HTML login page.

`/mcp` accepts **only bearer access tokens** in the Authorization header. Cookies and ID tokens cannot authenticate it; bearer credentials cannot authenticate browser-only identity/admin routes. MCP supports stateless Streamable HTTP with JSON responses, protocol `2025-11-25`, `initialize`, `ping`, `tools/list`, and six tools: `identity`, `list_questions`, `get_question`, `list_answers`, `create_question`, and `create_answer`. GET returns 405 after authorization (no SSE stream); invalid credentials return 401 with RFC 9728 `resource_metadata`; non-members/untrusted Origins return 403. The shared identity service derives the person and channel (`web` or `mcp`) from the validated principal. Caller-provided identity/admin tool arguments are rejected.

Future human-only acceptance/moderation belongs in browser-session-only, CSRF-protected handlers calling the same authorization service; it must have no MCP tool. This is a transport/credential boundary, not proof of human presence. A claimed client name, user-agent, or role payload conveys no human/admin authority. The Q&A handlers retain this boundary and shared authorization; see [their contracts](docs/questions-and-answers.md).

For another IdP, explicitly configure these values, provision the browser callback `<ASO_PUBLIC_URL>/login/oauth2/code/oidc` and post-logout URL `<ASO_PUBLIC_URL>/?signedOut`, and register individual MCP clients with exact redirects and the intended resource audience. Separately verify discovery, signatures/issuer/audiences, signed groups, logout, PKCE, resource handling, and each target client. Local Keycloak results do not establish another provider's compatibility.

### Upgrade an existing local realm without deleting data

Clean fixture imports already include goal 001's clients/logout registration. Goal 000a's existing realm is not re-imported on restart. Apply the additive update once instead of resetting it:

```bash
./scripts/keycloak-start.sh
./scripts/keycloak-configure-identity.sh  # Python 3; local bootstrap admin only
```

The helper changes only the dedicated MCP client registrations and `aso-web`'s logout redirect, preserving users/subjects and product data. It leaves the probe registration unchanged. It uses provider bootstrap credentials only for local administration; ASO users still use browser code/PKCE, never password grants. If customized, export the actual bootstrap values before running it. Provider-side fixture substitutions `ASO_WEB_CALLBACK`, `ASO_WEB_LOGOUT`, `ASO_MCP_CALLBACK`, and `ASO_MCP_RESOURCE` change exact imported callbacks/logout/resource mappings; these are distinct from ASO runtime settings and must agree with them. The default browser callback is unchanged. Isolated tests substitute their own dynamic callbacks and resource URLs.

### MCP clients and observed compatibility

The development fixture has public, S256-PKCE-only `aso-codex`, `aso-mcpjam`, and `aso-mcp-test` registrations, separate from `aso-web` and the wrong-audience probe. They disable password, implicit, and service-account grants. Each issues an access token with the exact ASO resource URL audience through a dedicated mapper; ID tokens do not get that resource audience. Pre-registration is the supported mechanism: no anonymous dynamic-registration policy is opened. The current local Keycloak processes its audience mapper rather than the `resource` parameter (the experimental resource-indicators feature is not enabled). Both tested clients send the resource URL and ASO enforces its intended audience. Do not claim full RFC 8707 conformance or arbitrary-resource support from this setup.

Verified on 2026-10-16: **Codex CLI 0.162.1** and **MCP Jam CLI 5.16.1**, each with Alice and Bob, real login/discovery/PKCE and successful `identity` calls matching the browser IDs. The owner explicitly replaced this goal's Claude Code/Cursor checks with MCP Jam; those two clients remain unverified and are not claimed compatible. This does not substitute an inspector for actual Claude Code/Cursor evidence in a later client milestone.

Codex configuration (individual user's private config, not committed token headers):

```toml
[mcp_servers.aso]
url = "http://127.0.0.1:8080/mcp"
[mcp_servers.aso.oauth]
client_id = "aso-codex"
callback_url = "http://127.0.0.1:8766/callback"
callback_port = 8766
```

Run `codex mcp login aso --scopes openid,profile,email`, then ask Codex to call only ASO's `identity`. `codex mcp logout aso` clears its OAuth credentials. The exact loopback callback above is registered in the fixture. See [official Codex MCP configuration](https://learn.chatgpt.com/docs/extend/mcp#oauth-client-registration-and-callbacks).

MCP Jam CLI (uses the Gradle-provisioned Node 24 on PATH if needed):

```bash
npx --yes @mcpjam/cli@5.16.1 --no-telemetry oauth login \
  --url http://127.0.0.1:8080/mcp --protocol-version 2025-11-25 \
  --registration preregistered --client-id aso-mcpjam \
  --redirect-url http://localhost:6274/oauth/callback \
  --scopes 'openid profile email' --verify-tools --verify-call-tool identity \
  --credentials-out /tmp/aso-mcpjam-credentials.json
npx --yes @mcpjam/cli@5.16.1 --no-telemetry tools call \
  --url http://127.0.0.1:8080/mcp --credentials-file /tmp/aso-mcpjam-credentials.json \
  --tool-name identity --tool-args '{}' --validate-response --expect-success
```

Use a fresh IdP browser session or sign out before testing the second person; an SSO session otherwise retains the first person. MCP Jam writes credentials mode 0600 and redacts them in stdout. Keep those temporary files and raw OAuth diagnostics out of Git and remove them after use. The browser Inspector is optional; the CLI can manage its own callback listener. See [MCP Jam CLI](https://www.mcpjam.com/features/cli).

MCP Jam protocol checks pass for the 2025-11-25 profile. Its stricter OAuth conformance command rejects an HTTP `resource` URI at metadata validation even on loopback; the separate real OAuth login/tool checks succeed. This local HTTP result is not reported as an OAuth conformance pass. Production resource metadata requires HTTPS.

### Reproducible schema generation

```bash
./gradlew --no-daemon generateJooq
git diff --exit-code -- backend/src/generated
```

The Boot-managed jOOQ 3.21.7 DDLDatabase parses `V2__identity.sql` and `V3__questions_and_answers.sql` offline and writes committed Kotlin bindings to `backend/src/generated/kotlin`. V1's pgvector extension is infrastructure and is not a table-generation input. No JDBC URL, developer database, Docker, IdP, or credentials are read by generation. Ordinary builds compile the committed sources. The generator owns their formatting; Spotless applies only to handwritten Kotlin. CI regenerates and checks for drift; adding future tables requires deliberately expanding the Flyway DDL inputs. Two independent runs produced byte-identical sources. Flyway applies V2/V3 additively; no existing migration or development data is rewritten.

## CI and diagnostics

GitHub Actions runs the four Gradle validation commands plus the disposable lifecycle check on pushes/PRs targeting `master`, using a **self-hosted** runner. The runner needs Git, Java setup support, Docker access, and Compose 2.24.4+. Gradle provisions Node; CI installs the matching Chromium browser and its Linux system dependencies before the OIDC, integration, and packaged-application browser tests.

CI runs `./gradlew --no-daemon playwrightInstall -PplaywrightWithDeps`, which invokes Playwright's `install --with-deps chromium`. On Linux, the runner must be root or have non-interactive sudo access for the OS package installation. Use the same command when preparing a Linux development host. Ordinary local browser-test tasks download Chromium without changing OS packages.

JUnit/HTML reports are under `backend/build/reports/tests/` and `backend/build/test-results/`; Angular coverage is under `frontend/coverage/`. Sanitized shell/Q&A fixture screenshots and the packaged application's log are under `backend/build/e2e/`. OIDC reports are in the same test report directories; sanitized Keycloak failure logs and success summaries are under `backend/build/oidc/`. OIDC checks retain no browser traces, tokens, callback URLs, or passwords. Authentication browser traces are deliberately disabled because they can retain passwords, codes, and tokens. CI uploads the sanitized diagnostics even when validation fails. Docker unavailability fails integration/browser tests rather than silently skipping them.

## Next milestones

The [MVP spec](spec.md) covers one private team workspace, individual OIDC identities, browser/MCP Q&A, context/comments, tags, human-accepted solutions, upvotes, occurrences/trends, and hybrid search. Initial embeddings use the owner's LM Studio model `text-embedding-qwen3-embedding-0.6b`; endpoint/model/dimensions remain configurable. Identity and the Q&A slice are implemented; content lifecycle (goal 003) and subsequent features remain planned.

[000a — Local development Keycloak](docs/goals/000a-local-keycloak.md) supplies the seeded IdP and standalone OIDC checks. [001 — Identity and access](docs/goals/001-identity-and-access.md) records browser/MCP implementation and verification. Follow the [ordered product goals](docs/goals/README.md). No external IdP configuration or personal credentials are required. ASO remains configurable for another OIDC IdP, with separate compatibility verification before use.

LM Studio configuration and representative search examples are inputs for the search goal. MVP delivery is a tested local release on the owner's laptop using Keycloak and LM Studio. Deployment and backup/restore functionality are deferred.

Implement each goal on a feature branch with a pull request and retain material decisions and verification evidence in its record. Use `docs/goals/TEMPLATE.md` for newly agreed goals. Goal Mode remains disabled until the owner explicitly enables it.

The current application is a local authenticated Q&A slice, not the completed team MVP. See [goal 002](docs/goals/002-questions-and-answers.md) for evidence and [Q&A contracts and client instructions](docs/questions-and-answers.md) for bounds, retry behavior, and safety limitations.
