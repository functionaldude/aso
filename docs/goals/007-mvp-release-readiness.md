# 007 — Tested laptop MVP release

Status: planned

## Dependencies and prerequisites

- [006 — Agent collaboration](006-agent-collaboration.md), including evidence for all three clients.
- Owner's laptop, seeded development Keycloak and goal 001's tested client configuration, and configured LM Studio embedding endpoint. No external IdP account is required for local MVP delivery.

## Objective

Deliver a reproducible, tested local release that completes the agreed collaboration loop on the owner's laptop. See [MVP acceptance and delivery](../../spec.md#mvp-acceptance-and-delivery).

## Included and excluded work

Include clean-checkout setup, packaged application verification, configuration/run instructions, security/regression review, CI diagnostics, and a release evidence checklist. Exclude deployment, PaaS setup, image publication, application backup/restore functionality, and production model-server provisioning. Existing jar/container packaging remains supported.

## Acceptance criteria

1. A clean checkout can install its pinned build tools, start the local database and seeded Keycloak, and run the documented browser/API/MCP workflows on the laptop. Instructions clearly distinguish disposable test IdP/data from persistent development Keycloak/data and the actual LM Studio runtime. Independent database/Keycloak reset commands preserve the other service's data.
2. `./gradlew --no-daemon check bootJar` needs no running Docker, IdP, database, or embedding service. Integration/E2E commands use isolated databases/authentication/embedding fixtures, never the owner's development data. Explicit real-client/model checks retain their own evidence and are not silently skipped.
3. The executable jar serves all MVP client routes and its real authenticated APIs. Login redirects, direct question/search navigation, session expiry, Markdown safety, missing API errors, mobile layouts, and loading/empty/failure states work in the packaged browser tests. Verify the existing container build and document local connectivity to configured dependencies without publishing it.
4. A regression/authorization matrix covers member/owner/admin/anonymous and MCP credentials across reads/writes, editing, acceptance, moderation, votes, and occurrences. Check cookie/CSRF/token handling as applicable, configured credential validation, secret-safe logging, and payload limits. Add configurable per-user request/write bounds with observable retry behavior; keep the single-application architecture.
5. Indexing retry/rebuild and model configuration changes are documented and demonstrated without losing product data. An embedding outage leaves exact/lexical search and ordinary content operations available. Missing authentication prerequisites fail closed with useful diagnostics.
6. The self-hosted CI runs the documented deterministic checks and retains failure reports. Record actual CI status if available; lack of access or runner failures remain explicit limitations.
7. Trace every MVP requirement to goal evidence, including actual laptop/IdP/LM Studio/all-client results. README describes the implemented behavior and all required non-secret configuration. Release notes identify remaining limitations; no feature or goal is marked complete solely because code exists.

## Verification

- Run the three root validation commands from a clean checkout and preserve reports.
- Run `./gradlew --no-daemon bootBuildImage` and a local application-container smoke check using explicit local configuration; no registry publication.
- Perform the full two-user laptop collaboration smoke scenario and link prior real-client/search-evaluation evidence. Confirm documented stop/restart behavior and database persistence.
- Review the spec-to-evidence checklist, CI status, actual reports, and remaining limitations before completion.

## Material decisions

- Owner defines MVP delivery as a tested runnable release on their laptop (2026-10-16).
- No backup/restore functionality is required; the owner expects a future PaaS platform to provide it. Do not add backup/restore scripts, drills, or deployment work as MVP completion gates.
- Production hosting and its embedding model are deferred; application configuration must allow a future locally hosted model change.
- The local MVP uses real authentication against a development Keycloak. Another OIDC IdP requires configuration and its own browser/all-client compatibility check before use; it is not an external prerequisite for this release.

## Progress and blockers

Not started. Depends on the previous goals' actual evidence and the real local configuration. Completion does not mean production deployment or permission to enable Goal Mode.

## Completion evidence

None yet. Record clean-checkout/package/container results, CI status, requirement-to-evidence links, actual laptop smoke results, commit, and PR when implemented.
