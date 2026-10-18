# 009 — CI container image publication

Status: in progress

## Dependencies and prerequisites

- Completed [goal 007 packaging](007-mvp-release-readiness.md) and [goal 008 UI](008-terminal-ui-redesign.md).
- Existing self-hosted Linux/X64 runners with Docker and outbound access to buildpack registries and GHCR.
- Repository Actions `GITHUB_TOKEN` permission to publish the private package.

## Objective

Extend GitHub CI to build and smoke-test ASO's Java 25 application image and publish successful master builds to private GHCR. The owner approved this post-MVP scope on 2026-10-18; see [spec](../../spec.md#ci-container-publication-goal-009) and [container instructions](../container.md).

## Included and excluded work

Include the existing buildpack image, CI verification, isolated image smoke tests, master-only publication, complete runtime environment template/documentation, and a feature-branch PR. Publish only `linux/amd64`, matching the runners. Deployment, a Compose application service, multi-architecture publication, database/identity migrations, and Goal Mode remain outside scope.

## Acceptance criteria

1. Existing CI checks remain required. All events build and smoke-test an image after checks; PRs and non-master manual runs cannot publish.
2. Only the master publishing job has `packages: write`. It publishes the tested image using `GITHUB_TOKEN`, first `sha-<full-commit>` then `latest`, with source/revision labels and digest output. The transfer artifact expires after one day.
3. Runtime defaults bind the container to all interfaces without changing host-JVM defaults. Required identity/database values and optional agent setup, embedding/search and rate-limit variables are documented; runtime secrets are never passed to the image build.
4. Docker-dependent tests use disposable pgvector/Keycloak and check actual image startup, overridden port, default address, health/SPA/API behavior, safe public metadata, and failure without required identity configuration. The container template covers every application environment variable.
5. Retain local/PR verification evidence and an unmerged PR. Actual registry publication remains unverified until an eligible master run; do not infer it from a passing PR or merge solely to test it.

## Verification

- `./gradlew --no-daemon check bootJar`, reproducible jOOQ generation, `oidcSmokeTest`, disposable lifecycle script, `integrationTest`, and `e2eTest`.
- `./gradlew --no-daemon bootBuildImage` and `./gradlew --no-daemon imageSmokeTest -PimageUnderTest=<local-image>`.
- Workflow/shell validation and PR CI; inspect the publication job's skipped state.
- After an eligible master run, record private package tags/digest and publication result.

## Material decisions

- Owner selected GHCR, a template/documentation rather than a Compose app service, and the runner's native architecture during planning; approved implementation on 2026-10-18.
- Verification retains read-only permissions. A separate master-only job loads the exact tested image artifact, with isolated temporary Docker credentials. No dependency on a shared runner's local image cache.
- `BPE_DEFAULT_ASO_ADDRESS=0.0.0.0` supplies an overridable image launch default; development and jar runtime still bind loopback.
- The isolated image test shares Keycloak's network namespace for one valid loopback issuer, using a private Docker network for its disposable database. It does not weaken HTTPS/issuer checks or touch owner data.
- The new image test exposed an existing missing-secret bug: Spring's configuration binder leaves an unresolved `${ASO_OIDC_WEB_CLIENT_SECRET}` as nonempty text, allowing startup. Give all eight required placeholders empty fallbacks so the existing nonblank identity validation fails closed when any is absent.

## Progress and blockers

Implementation and local verification pass on `codex/ci-container-image`; PR CI remains to be confirmed. No actual GHCR publication has been attempted; that requires an eligible master run after review/merge.

## Completion evidence

- `./gradlew --no-daemon spotlessApply check bootJar integrationTest e2eTest :backend:bootBuildImage --imageName=aso-ci:local-009 --environment=BP_OCI_REVISION=local-009 -PimageBuildId=local-009 imageSmokeTest -PimageUnderTest=aso-ci:local-009`: passed on 2026-10-18 with Java 25.0.4.1 / Gradle 9.2.1. JUnit: 21 backend unit tests, 19 integration tests, two packaged-browser suites, and two image-smoke tests; zero failures/errors/skips. Six frontend tests, lint, formatting and production packaging pass. The image scenario checks all eight missing-identity cases separately.
- `./gradlew --no-daemon generateJooq oidcSmokeTest integrationTest e2eTest`: passed. Offline generation changed no committed bindings; two OIDC smoke tests pass. Integration/browser gates were rerun after the required-identity configuration fix above.
- `./scripts/keycloak-lifecycle-test.sh`: passed confirmation guards, service-specific restart/persistence and independent reset checks using its disposable Compose project. Owner development containers remained running.
- The initial image negative test exposed the unresolved-secret placeholder bug and failed. After empty fallbacks were added, all eight omission cases produce the existing explicit-configuration failure, while fully configured image startup succeeds. Fixture-only logs are retained under `backend/build/image-smoke/`; no real credentials are used.
- Local native ARM64 image `aso-ci:local-009` built with Java 25, source/revision labels and user `1002:1001`; image ID `sha256:dbef285c1934c945b87faecb50f778bd03cdf1150a0f5ff43a43fad59c091222`. Docker save/load preserved its exact ID and metadata (367 MiB archive), and the temporary archive was removed. Linux/AMD64 verification belongs to PR CI.
- `actionlint` v1.7.12 and `bash -n` validation of every workflow shell block pass; `git diff --check`, generated-source diff and env-file ignore checks pass. Publication is gated to master push/manual events; only its separate job has package write permission.
- PR link and Linux CI evidence pending. Registry tags/digest/private-package verification remain pending the first eligible master publication run; this goal is not yet complete.
