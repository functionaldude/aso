# 015 — Vertex embeddings and GCP deployment

Status: ready for review

## Objective and scope

Add an opt-in Vertex embedding provider and reviewable Cloud Run/Cloud SQL deployment for project `agentic-stack-overfl-mka5b8` (`agentic-stack-overflow`). Preserve the local provider, its private-host restrictions, existing pgvector schema, bounded requests and lexical fallback. The owner requested separate Vertex/deployment and Okta PRs on 2026-11-16; authentication changes belong to goal 016.

## Acceptance criteria

1. Query and document embeddings use the runtime service account's automatically refreshed Application Default Credentials, without static keys.
2. Keep provider handling limited to the fixed Vertex endpoint, request/response format and retrieval task types. Share HTTP bounds, normalization and indexing behavior with local embeddings.
3. Preserve compatible index generations and require an explicit rebuild when provider/model/preparation changes.
4. Define Cloud Run, persistent PostgreSQL/pgvector, secrets, image builds and least-privilege service identities in the authorized project only. Keep generated state, plans and credentials out of Git.
5. Verify unit, isolated database and packaged browser regressions; distinguish provider fixtures from live service checks.

## Decisions

- `gemini-embedding-001` in `europe-west1`, 768 dimensions, separate `RETRIEVAL_QUERY`/`RETRIEVAL_DOCUMENT`, no silent truncation. The documented Vertex embedding API is `:predict`; the Gemini Developer API's OpenAI-compatible embedding endpoint is a different service. Keep a small native translation rather than introduce a proxy or API key. See sources in [deployment instructions](../cloud-run.md).
- Cloud SQL PostgreSQL 17 supports the existing `vector` extension and Flyway migrations; no alternate vector store or schema is needed.
- One always-allocated-CPU Cloud Run instance supports scheduled indexing and the current process-local sessions/rate limits. Database credentials are generated in Terraform and delivered through Secret Manager; state contains secrets and stays private in the versioned project bucket.
- The web service is gated on an immutable image, issuer and client ID. OIDC is mandatory. The explicit dependency-check job can check SQL/migrations and Vertex before a provider registration is available.

## Verification evidence

- Before the PR split, Cloud Build `b9ccc0e9-7f05-47c2-97cd-c5915b7a840f` built image digest `sha256:25bfa98ad480d597ad7df69100e02026ce8656bd7ce71b58945581a1e462e569` in this project's private registry.
- Cloud Run execution `aso-deployment-check-4z2ss` previously succeeded under `aso-runtime@agentic-stack-overfl-mka5b8.iam.gserviceaccount.com`: Cloud SQL PostgreSQL 17.11, seven Flyway migrations, pgvector and actual 768-dimensional Vertex query/document embeddings. It inserted no product content. This is evidence for the earlier image, not a claim that a newly reviewed commit is deployed.
- Initial Terraform applies provisioned the dependencies and check job without deletions. The web service was not provisioned while OIDC inputs were absent.
- Fresh `spotlessApply check bootJar integrationTest e2eTest` passed on 2026-11-16: 30 backend unit tests, 20 isolated PostgreSQL/Keycloak integration tests, two packaged browser tests and 13 frontend tests; zero failures/errors/skips. Terraform `fmt -check` and `validate`, shell syntax and Git whitespace checks passed. No UI behavior changed.
- Fresh live GCP checks and an authenticated drift plan require gcloud reauthentication; both configured accounts failed token refresh on 2026-11-16.

## Deployment boundary

This PR prepares and tests the deployment. It does not claim browser/Okta login or live MCP interoperability. Apply only reviewed plans in this repository and only to the authorized project. The current task is to prepare draft PRs, not to publish a new deployment.
