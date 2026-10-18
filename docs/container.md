# Application container

CI builds and smoke-tests the application image after the existing checks. Successful pushes to `master`, and manual workflow runs targeting `master`, publish **`ghcr.io/functionaldude/aso:sha-<full-commit>`** and update **`:latest`**. Pull requests and manual runs on other branches build and test without registry publication. The published platform is **`linux/amd64`**. Local `./gradlew --no-daemon bootBuildImage` builds `aso:0.0.1-SNAPSHOT` for the Docker engine's native architecture, including ARM64 on Apple Silicon.

The GHCR package is private. Workflow publication uses its temporary `GITHUB_TOKEN` with `packages: write`; no personal registry token or application secret is needed in CI. A package that already exists must grant this repository Actions access. Publication failure fails the workflow. Publication does not deploy the application.

## Pull and run

Authenticate with a GitHub account that can read the private package and a personal access token (classic) with `read:packages`. Supply the token through your shell's secret mechanism; do not put its value in a command, file in Git, or the application env-file.

```bash
printf '%s' "$GHCR_READ_TOKEN" | docker login ghcr.io --username "$GHCR_USER" --password-stdin
unset GHCR_READ_TOKEN
docker pull ghcr.io/functionaldude/aso:latest
cp .env.container.example .env.container
# Edit .env.container: replace every REPLACE_ME value and configure reachable services.
docker run --detach --name aso \
  --env-file .env.container \
  --publish 127.0.0.1:8080:8080 \
  ghcr.io/functionaldude/aso:latest
curl --fail http://127.0.0.1:8080/actuator/health
# Expected: {"status":"UP"}
docker stop aso
docker rm aso
docker logout ghcr.io
```

Use a commit tag or the published digest (`ghcr.io/functionaldude/aso@sha256:…`) when a fixed version is required. `latest` changes with each successful master publication. On ARM64, use the native local build or explicitly request `--platform linux/amd64` with a Docker engine that supports emulation; the registry image is not multi-architecture.

The image runs as a non-root user with Java 25 and serves Angular and `/api/v1` from the same application. It defaults to `ASO_ADDRESS=0.0.0.0`, `ASO_PORT=8080`; explicitly supplied environment variables can override these. A different port, such as `ASO_PORT=9090`, requires `--publish 127.0.0.1:9090:9090`. Ordinary jar/development startup still defaults to loopback.

## Required configuration

The checked-in [container template](../.env.container.example) lists every application environment variable. Its populated `.env.container` is ignored by Git. Docker's `--env-file` uses plain `KEY=value`: no `export`, shell quotes, `$VARIABLE` interpolation, command substitution, or decoded `\n`. Quotation marks would become part of a value. Runtime secrets belong only in the populated private file or another runtime secret mechanism; the image build does not receive them. Do not select `SPRING_PROFILES_ACTIVE=development` for this configuration.

| Variable | Required runtime value |
| --- | --- |
| `ASO_DB_URL` | JDBC URL for reachable PostgreSQL with pgvector, e.g. `jdbc:postgresql://your-private-db:5432/aso` |
| `ASO_DB_USER`, `ASO_DB_PASSWORD` | Your database credentials; do not use the local-development defaults |
| `ASO_OIDC_ISSUER` | Exact issuer URL used by discovery and signed tokens, reachable from both application and browser |
| `ASO_OIDC_CLIENT_ID` | Your shared public browser/MCP OIDC registration (no client secret) |
| `ASO_PUBLIC_URL` | Canonical browser origin, without a trailing slash |
| `ASO_MCP_AUDIENCE` | Required audience in MCP access tokens, normally `<ASO_PUBLIC_URL>/mcp` |
| `ASO_OIDC_GROUPS_CLAIM` | Signed ID/access-token claim containing group strings, e.g. `groups` |
| `ASO_OIDC_MEMBER_GROUP`, `ASO_OIDC_ADMIN_GROUP` | Distinct workspace membership and admin groups; admins must also be members |

All seven identity variables must be nonempty; missing identity configuration fails startup. Register the exact browser callback `<ASO_PUBLIC_URL>/login/oauth2/code/oidc` and post-logout URL `<ASO_PUBLIC_URL>/?signedOut` with the provider. Issuer/public URLs require HTTPS except on loopback. Follow the [identity configuration and interoperability requirements](../README.md#identity-configuration-and-access), including signed groups and mandatory S256 PKCE; register Codex’s default `http://127.0.0.1:8766/callback` on the same public client. This callback targets Codex on the user’s computer, even when ASO runs in a container. The setup page generates configuration from application settings; discovery does not verify the registered redirect list.

Container `127.0.0.1` addresses the container itself. A host's loopback JDBC URL, Keycloak issuer, or LM Studio address cannot be copied into an ordinary bridged container. Use reachable private addresses, or attach the app to an existing private Docker network with `--network` when using container DNS aliases. Keep the issuer identical for browser and application; a separate internal issuer URL would fail validation. The [goal 007 smoke topology](goals/007-evidence.md#live-laptop-collaboration-and-persistence) and the automated image test use a shared namespace for their isolated loopback IdP. They do not configure your external IdP or networking.

For an existing installation, follow the [clean shared-client migration](agent-setup.md#administrator-setup-and-migration): convert the existing browser registration in place, replace the old settings with `ASO_OIDC_CLIENT_ID`, remove the client secret/per-tool IDs, and reauthenticate. Verify stable subjects before switching.

## Optional configuration

| Variables | Defaults and behavior |
| --- | --- |
| `ASO_ADDRESS`, `ASO_PORT` | Image: `0.0.0.0`, `8080`; jar: `127.0.0.1`, `8080` |
| `ASO_CODEX_CALLBACK_URL` | `http://127.0.0.1:8766/callback` when unset or blank; register once on the shared public client. Override for custom/remote Codex setups |
| `ASO_RATE_LIMIT_REQUESTS`, `ASO_RATE_LIMIT_WRITES`, `ASO_RATE_LIMIT_WINDOW` | `600`, `120`, `1m`; shared per-user browser/MCP budgets, with `0 < writes <= requests`, window `1s`–`1h` |
| `ASO_EMBEDDING_ENABLED` | `false`; exact/lexical search remains available with visible degradation |
| `ASO_EMBEDDING_BASE_URL`, `ASO_EMBEDDING_ALLOWED_HOSTS` | `http://127.0.0.1:1234/v1`, `127.0.0.1,localhost,::1`; configure a container-reachable private model endpoint and its matching allowlist before enabling |
| `ASO_EMBEDDING_MODEL`, `ASO_EMBEDDING_DIMENSIONS` | `text-embedding-qwen3-embedding-0.6b`, `1024`; must match the actual model response |
| `ASO_EMBEDDING_API_KEY` | Empty; optional private model-service bearer token |
| `ASO_EMBEDDING_PREPARATION_VERSION` | `qwen3-retrieval-v1`; version model-specific preprocessing explicitly |
| `ASO_EMBEDDING_QUERY_PREFIX`, `ASO_EMBEDDING_QUERY_SUFFIX` | Qwen retrieval instruction plus newline and `Query: `; empty suffix. Preserve defaults by leaving these unset |
| `ASO_EMBEDDING_DOCUMENT_PREFIX`, `ASO_EMBEDDING_DOCUMENT_SUFFIX` | Empty |
| `ASO_EMBEDDING_MAX_INPUT_CHARACTERS`, `ASO_EMBEDDING_MAX_QUERY_CHARACTERS` | `4000`, `1000` UTF-16 units, including preparation for model input |
| `ASO_EMBEDDING_MAX_DOCUMENT_CHUNKS`, `ASO_EMBEDDING_BATCH_SIZE` | `64` chunks per question, `8` inputs per request |
| `ASO_EMBEDDING_MAX_RESPONSE_BYTES` | `4000000` |
| `ASO_EMBEDDING_CONNECT_TIMEOUT`, `ASO_EMBEDDING_REQUEST_TIMEOUT`, `ASO_EMBEDDING_INDEXING_TIMEOUT` | `300ms`, `1200ms`, `30s` |
| `ASO_EMBEDDING_WORKER_BATCH_SIZE`, `ASO_EMBEDDING_POLL_MILLIS` | `10` questions, `1000` milliseconds |
| `ASO_EMBEDDING_RETRY_DELAY`, `ASO_SEARCH_MIN_SIMILARITY` | `5s` initial retry delay, `0.45` cosine threshold |

Embeddings must remain on locally hosted infrastructure: the allowlisted endpoint must resolve exclusively to loopback/private/link-local addresses. There is no cloud fallback. Model/dimension/preparation changes require a compatible reindex; see [local hybrid search](../README.md#local-hybrid-search). Counters are in-memory and reset on restart; run one application instance.

For multiline preparation, mount a private Spring configuration directory. For example, put this in that directory's `application.yaml`:

```yaml
aso:
  embedding:
    query-prefix: "Instruct: Retrieve related engineering questions.\nQuery: "
```

Add these arguments to `docker run`, leaving the prefix variables unset in `.env.container`:

```bash
--mount type=bind,source=/absolute/path/private-config,target=/etc/aso,readonly \
--env SPRING_CONFIG_ADDITIONAL_LOCATION=file:/etc/aso/
```

Increment `ASO_EMBEDDING_PREPARATION_VERSION` and reindex when changing preparation. Keycloak bootstrap/import variables from the development `.env.example` configure that separate provider and are not ASO container runtime settings.

## Verify an image locally

```bash
./gradlew --no-daemon bootBuildImage
./gradlew --no-daemon imageSmokeTest -PimageUnderTest=aso:0.0.1-SNAPSHOT
```

The explicit task requires Docker and an already-built local image; it never pulls an application image implicitly. It uses disposable pgvector and seeded Keycloak, no `.env`, persistent development services, or live embedding provider. It verifies the env-template inventory, non-root runtime, default container bind address, overridden port, health, packaged routes, public configuration, anonymous API rejection, and startup rejection when each of the seven required identity variables is omitted. Reports are under `backend/build/reports/tests/imageSmokeTest/`; fixture-only application logs are under `backend/build/image-smoke/` and are retained by CI. The task is separate from ordinary `check`.
