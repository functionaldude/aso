# 002b — CI verification repair

Status: in progress

## Dependencies and prerequisites

- Current `master` at `7ebcb57967c42f9e75d292dff88fc25911af426e`, following goal [002a — Backend type safety](002a-backend-type-safety.md).
- Authenticated GitHub CLI and the existing self-hosted Linux runner with Docker and OS package installation privileges.

## Objective

Repair the failing [CI job](https://github.com/functionaldude/aso/actions/runs/38971245349/job/117209866473) and verify the fix through a pull-request CI run. This preserves the [spec's automated verification requirement](../../spec.md#mvp-acceptance-and-delivery).

## Included and excluded work

Include diagnosis from retained CI reports, the necessary workflow setup and isolated fixture networking, accurate runner instructions, and iteration on a feature-branch PR until CI passes. No product feature, authentication relaxation, dependency upgrade, development-data reset, merge, or deployment is included.

## Acceptance criteria

1. Identify the concrete failure from CI diagnostics and address its cause without skipping or weakening tests.
2. Open a PR against `master` and confirm it triggers the existing CI workflow.
3. All workflow verification steps pass on the PR's final commit; record the run and any remaining limitations.

## Verification

- Inspect the failed job log and retained JUnit XML.
- Run `./gradlew --no-daemon check bootJar` locally and inspect the diff.
- Use the PR workflow to verify Linux browser dependencies, OIDC smoke tests, disposable reset isolation, database integration, and packaged browser tests.

## Material decisions

- The retained OIDC JUnit report shows Chromium exits with code 127 before browser login: `error while loading shared libraries: libnspr4.so: cannot open shared object file: No such file or directory`. Unit/build and offline schema checks passed; later verification steps were skipped after this failure.
- The existing `playwrightInstall` task supports `-PplaywrightWithDeps` but CI only invoked it indirectly without that property. Add an explicit setup step using that existing option before the first browser suite. [Playwright documents](https://playwright.dev/java/docs/browsers#install-system-dependencies) `install --with-deps chromium` for browser and OS dependency installation.
- Keep OS package installation explicit in CI; ordinary local browser-test invocations retain their existing behavior. Retain the `pull_request` trigger targeting `master` and all existing test assertions and failure reporting.
- The first PR run passed browser installation, OIDC smoke, and reset isolation, then exposed a second failure: all integration contexts reject the HTTP issuer at Docker host gateway `172.21.0.1`. The runner is containerized, and Testcontainers correctly detects the gateway for published ports. ASO correctly requires HTTPS outside loopback.
- Give the shared Keycloak fixture a disposable TCP forwarder bound only to `127.0.0.1` and use its issuer in all browser/integration suites. HTTP headers and bytes are preserved so Keycloak discovery and signed tokens advertise the same loopback issuer the application validates. Close all forwarding sockets/workers with the fixture. Production endpoint validation, issuer/audience/signature checks, and provider configuration remain unchanged.

## Progress and blockers

Two runner-specific causes confirmed on `codex/fix-ci-browser-dependencies`, published in [PR #5](https://github.com/functionaldude/aso/pull/5). The browser setup fix passed Linux CI, and the full local suite passes with the fixture-networking fix. Final PR CI evidence is pending.

## Completion evidence

- Downloaded `verification-reports` from run `38971245349` and inspected `TEST-com.functionaldude.aso.oidc.OidcSmokeTest.xml`. The failure is at Chromium launch in `OidcSmokeTest.kt:111`, before any identity assertions.
- `./gradlew --no-daemon check bootJar` passed locally on Java 25.0.4.1 and Gradle 9.2.1. Frontend tests (4), lint, and formatting executed; unchanged backend tests/build outputs were up to date. `git diff --check` passed.
- Linux browser setup and Docker-backed verification will be checked on the PR runner; Mac results do not establish Linux dependency installation.
- [First PR CI run](https://github.com/functionaldude/aso/actions/runs/38971892372): automatically triggered by `pull_request` at `dd5ae88`; browser dependency installation, OIDC smoke, and disposable reset isolation pass. Integration tests fail on the non-loopback HTTP issuer and E2E is skipped. This run is diagnostic evidence, not a passing result.
- `./gradlew --no-daemon spotlessApply check bootJar oidcSmokeTest integrationTest e2eTest` passed in 1m33s on the owner's ARM64 Mac with Java 25.0.4.1 and Gradle 9.2.1. Backend unit tests (15), frontend tests (4), OIDC smoke (2), integration (9), and packaged browser (1) all pass with zero failures/errors/skips. This includes new forwarder regression checks for concurrent HTTP requests preserving Host/path/query/body and cleanup of active sockets/listener. Test fixtures use isolated Docker state; no development data was reset.
