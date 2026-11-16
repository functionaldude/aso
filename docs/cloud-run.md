# Cloud Run deployment

This deployment is restricted to project **`agentic-stack-overfl-mka5b8`**, display name `agentic-stack-overflow`, in `europe-west1`. Terraform rejects another project ID. Run every Terraform command from this checkout through `scripts/gcp-terraform.sh`; it uses the authenticated gcloud account with a short-lived token and does not change gcloud's active project.

## Infrastructure

`infra/gcp` defines Cloud Run, PostgreSQL 17/pgvector, Artifact Registry, a private build staging bucket, Secret Manager, service accounts and a dependency-check job. The database accepts only authenticated connector connections, with no authorized public networks. The password is generated and injected from Secret Manager. The runtime identity can connect to Cloud SQL, read that one secret and invoke Vertex predictions; no service-account keys are created.

The Terraform backend is `gs://agentic-stack-overfl-mka5b8-tfstate/aso`. Bootstrap it once with uniform bucket access, public-access prevention and versioning before `init`; do not recreate it for ordinary deployments. State contains the generated database password, so restrict bucket access. Local state, plans, populated tfvars and image staging files are ignored by Git.

The service uses one 1-CPU/1-GiB instance with CPU always allocated and session affinity. This supports scheduled indexing between requests and the app's in-memory sessions/rate limits. Deployment/restarts can still end sessions. Scaling past one instance requires shared sessions and shared rate limiting. Cloud SQL uses a small zonal `db-f1-micro` instance with 10 GiB SSD, automatic growth, daily backups and point-in-time recovery; it is not an HA configuration. Cloud SQL and the minimum Cloud Run instance incur ongoing charges.

## Build and deploy

Use Java 25, the checked-in Gradle wrapper, Terraform and an authenticated gcloud account with permissions in this project. `frontend/.npmrc` explicitly selects the public npm registry. If the local gcloud installation needs a particular compatible Python, set `CLOUDSDK_PYTHON` before running the scripts; the repository does not change global tooling.

```bash
./gradlew --no-daemon check bootJar
./scripts/gcp-terraform.sh init
./scripts/gcp-terraform.sh validate
./scripts/gcp-terraform.sh plan -out=core.tfplan
./scripts/gcp-terraform.sh apply core.tfplan
./scripts/gcp-build.sh
```

The build uploads only the jar and Dockerfile in ignored `.gcp-build`, builds Linux/AMD64 on Cloud Build and pushes to this project's Artifact Registry. No runtime secrets are build inputs. Obtain the built image's immutable digest from Cloud Build, then copy `infra/gcp/terraform.tfvars.example` to ignored `infra/gcp/terraform.tfvars` and set `image` to the full `europe-west1-docker.pkg.dev/agentic-stack-overfl-mka5b8/aso/aso@sha256:…` reference.

Leave the OIDC values blank to provision and verify dependencies first:

```bash
./scripts/gcp-terraform.sh plan -out=dependencies.tfplan
./scripts/gcp-terraform.sh apply dependencies.tfplan
gcloud run jobs execute aso-deployment-check \
  --project=agentic-stack-overfl-mka5b8 --region=europe-west1 --wait
```

This explicit job runs the packaged Java code under the application's runtime identity, applies pending Flyway migrations and checks pgvector plus real Vertex query/document embeddings. It does not start the web application or bypass OIDC. It uses synthetic diagnostic text and does not insert product content. Do not run it against a different database.

Set the final OIDC registration values in the ignored tfvars and apply a reviewed plan to create the application service. OIDC is mandatory; Terraform does not create the web service while the issuer or client ID is empty. Use the Terraform `public_url`, `browser_callback` and `logout_callback` outputs for the registration. The service is reachable without GCP IAM credentials so browser login and MCP discovery work; application OIDC still protects private data.

## Runtime configuration

`local.runtime_environment` in `infra/gcp/main.tf` is the shared environment definition for the service and dependency check. Key deployment settings are:

- `ASO_DB_URL` selects the Cloud SQL PostgreSQL JDBC connector with lazy credential refresh; `ASO_DB_USER=aso`, and `ASO_DB_PASSWORD` references a pinned Secret Manager version. Hikari uses at most five connections.
- `ASO_ADDRESS=0.0.0.0`, `ASO_PORT=8080`, framework forwarded-header handling and secure session cookies configure Cloud Run's HTTPS proxy.
- `ASO_PUBLIC_URL` is the canonical HTTPS origin; `ASO_MCP_AUDIENCE` is that origin plus `/mcp`. The explicit issuer and public client ID configure identity. Set `oidc_member_group` and `oidc_admin_group` to the groups emitted by your provider. `runtime_environment` allows additional installation settings without editing the Terraform resources; keep secrets in Secret Manager. Development mode is not enabled.
- `ASO_EMBEDDING_PROVIDER=vertex`, `ASO_EMBEDDING_VERTEX_PROJECT=agentic-stack-overfl-mka5b8`, `ASO_EMBEDDING_VERTEX_LOCATION=europe-west1`, `ASO_EMBEDDING_MODEL=gemini-embedding-001` and `ASO_EMBEDDING_DIMENSIONS=768` select Vertex. Requests use the runtime identity's automatically refreshed credentials, not `ASO_EMBEDDING_API_KEY`.
- Vertex requests contain one input and use `RETRIEVAL_QUERY` or `RETRIEVAL_DOCUMENT`. Query/document text prefixes are empty, truncation is disabled, and responses are bounded and normalized. Query timeout is five seconds; indexing timeout is 30 seconds. Exact/lexical search remains available on embedding failure.

The Vertex adapter uses the documented [`:predict` embedding API](https://cloud.google.com/vertex-ai/generative-ai/docs/embeddings/get-text-embeddings). Google documents [Vertex OpenAI compatibility](https://cloud.google.com/vertex-ai/generative-ai/docs/start/openai) for chat completions; the OpenAI-compatible embedding example in the [Gemini Developer API](https://ai.google.dev/gemini-api/docs/openai#embeddings) uses a different service and API-key configuration. The native adapter keeps only IAM authentication, request/response translation and retrieval task selection provider-specific; HTTP limits, normalization, indexing and fallback are shared. Cloud SQL [supports the `vector` extension](https://cloud.google.com/sql/docs/postgres/extensions), installed by the existing Flyway migration.

Changing provider/model/dimensions/preparation creates a different search generation. Use the application's existing administrator rebuild operation for a populated installation; never reset product data to change embeddings. The default `local` provider keeps the original private-host restrictions and does not acquire Google credentials.

## Verification

After deployment check `/actuator/health`, `/api/v1/info`, `/agent-setup` and the safe public setup metadata. Anonymous protected APIs and `/mcp` must return 401. Check actual browser login, group authorization, logout and MCP access with the configured identity provider. A passing dependency job does not establish OIDC interoperability. Run a final Terraform plan to check for drift. Deployment evidence and outstanding registration inputs belong in [goal 015](goals/015-gcp-deployment.md).
