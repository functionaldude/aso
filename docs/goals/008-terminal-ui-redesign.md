# 008 — Terminal-inspired UI redesign

Status: Complete — visually approved, locally and CI verified; PR #11 open for review

## Dependencies and prerequisites

- [007 — Tested laptop MVP](007-mvp-release-readiness.md), merged through [PR #10](https://github.com/functionaldude/aso/pull/10) as `d8e37f8e1e5057d532c3fbfb55a5ed21d1b90962`.
- Java 25, checked-in Gradle 9.2.1, provisioned Node/npm, Angular/Material/CDK and Docker.

## Objective and scope

Deliver a compact, polished dark developer workbench throughout the completed laptop MVP. [The spec](../../spec.md#terminal-inspired-browser-ui-goal-008) owns the agreed requirements. Include the shell, all existing routes/forms/states, supported Material overlays, local typography, accessible interactions, isolated visual fixtures and before/after evidence. Exclude a light theme, new product features, production backend/schema changes, deployment, image publication and owner data/configuration changes.

## Acceptance criteria

1. Near-black/charcoal/off-white/emerald design uses clear hierarchy, aligned rows, deliberate spacing, local monospace identity and readable prose. No marketing hero, excessive cards or decorative terminal effects.
2. A 224px rail at >=1024px becomes a keyboard-accessible drawer below it. Home shows five real recent questions across resolution states; account/session/footer and every existing page/form/state receive complete treatment.
3. Authorship, resolution, accepted answers and permitted actions remain explicit. Upvotes, encounters and distinct developers stay separate, retaining seven-day/all-time labels. Forms preserve drafts, conflict review and retry IDs.
4. The real shell and populated list/detail receive desktop/mobile visual review before page-specific work continues. The owner approves the pilot once; requested revisions belong to that checkpoint.
5. Keyboard/focus/names, contrast, reduced motion, narrow layouts, zoom/reflow, long code/identifiers and overlays are verified against the executable jar with disposable data. Authentication and Markdown safety remain intact.
6. Required checks pass with meaningful original assertions and focused coverage. Handoff includes visual evidence, a committed/pushed branch and a PR with available CI status.

## Material decisions and approval

- The owner approved the implementation plan on 2026-10-17, selecting the desktop rail and Home workspace overview.
- The owner approved the populated desktop/mobile pilot on 2026-10-17: “Looks good, approved. Apply this design throughout the app, fix the drawer focus issue and bundle-size warning, then finish verification, commit and push the branch, and open the PR.” This satisfied the single visual checkpoint before remaining page composition work.
- Palette: canvas `#0B0E11`, surfaces `#12171B`/`#1A2126`, text `#E7EEEB`/`#9AA9A4`, emerald `#5ED9A3`, panel borders `#2B353C`, control boundaries `#63736B`, textual amber/red feedback. Shared tokens and presentation patterns cover every feature.
- Bundle JetBrains Mono 2.304 Regular/Medium WOFF2 and OFL license locally. Prose uses system sans-serif; programming ligatures are disabled. No new runtime third-party requests or npm dependencies.
- Use public Material theme/override, strong-focus-indicator and animation configuration APIs. No private Material selectors or `::ng-deep`. The drawer uses public CDK focus trapping/initial capture, background inertness and post-dismissal focus restoration, replacing timing-sensitive Material sidenav focus while retaining the approved composition.
- Defer the account menu and keep shell controls lightweight. Final initial bundle: **499.62 kB**, within the unchanged **500 kB** warning budget. Shared Q&A styles are emitted once, removing duplicated component CSS and the component-style warning.
- Preserve DTOs, defaults, query parameters, answer order/pagination, OIDC/CSRF, safe Markdown, authorization and business logic. No fabricated answer/total-result counts or runtime sample content. Home uses the existing list API with `state=all` and five items.
- Context composers use native expandable details without removing their drafts. Rendered overflowing code/tables receive named keyboard-scroll regions without bypassing HTML sanitization. Forms retain their existing request/version behavior.
- Goal Mode remains disabled. No merge, force push, deployment or image publication is authorized.

## Implementation and isolated review

The real pilot established the shell, theme, question rows, search/views/filters, detail/accepted answers, separate engagement controls, comments and readable technical content. After approval, Home, About, setup/configuration/copy blocks, creation/editing/conflicts, moderation and feedback states received the same design.

`./gradlew --no-daemon uiPreview` runs the executable jar on dynamic loopback ports with disposable PostgreSQL, Keycloak and embedding fixtures. It seeds through authenticated APIs: six visible questions, a hidden moderation question, multiple authors, accepted/unaccepted answers, comments, contrasting engagement and long content. `-PuiPhase=before|review|after` chooses an ignored capture directory; `-PuiPreviewExit` captures and exits. Ctrl-C cleanup was exercised. The fixture never reads `.env`; its subprocess drops inherited ASO/Spring overrides before explicit fixture settings. Production backend code, migrations and service configuration are unchanged.

## Verification evidence

All required commands use the repository toolchain and isolated fixtures; JUnit results and browser diagnostics remain under ignored build output.

| Command | Result |
| --- | --- |
| `./gradlew --no-daemon check bootJar` | Passed: 21 backend tests, 6 Angular tests, ESLint, Spotless, Prettier and production packaging; no bundle-size warning. |
| `./gradlew --no-daemon integrationTest` | Passed: 19 tests, zero failures/errors/skips, including real server throttling and authorization. |
| `./gradlew --no-daemon e2eTest` | Passed: 2 packaged tests, zero failures/errors/skips, including the explicit keyboard menu focus-indicator assertion. |
| `git diff --check` | Passed. |

The original comprehensive packaged suite retains identity/session/bearer/CSRF boundaries, Markdown safety, creation and uncertain-response retry IDs, comments, optimistic editing conflicts, acceptance/reopening, moderation, votes/encounter replay, search/degradation/pagination, restart persistence and setup copy fallback. Presentation-dependent selectors now target the corresponding real controls/content.

The additional packaged UI suite verifies populated list/detail overflow at 320/390/768/1024/1440px, five real Home questions across states, all existing route patterns via direct navigation and refresh, route focus/titles, drawer trapping/initial focus/Escape return, menu/select keyboard return and placement, reduced motion, context draft collapse/reopen, long-code keyboard scrolling, hidden moderation, required validation, mobile editing conflicts and explicit `429`/`Retry-After` feedback with the same draft/request ID. It checks 200% text enlargement plus effective 720px/360px reflow viewports for 1440px at 200%/400% zoom, waiting for loaded content. No browser page errors were recorded.

Rendered text samples have a minimum **6.658:1** contrast ratio. The control boundary color against the raised surface is **3.253:1**; emerald focus against the standard surface is **10.248:1**. These are measured palette/rendered checks, supplemented by keyboard and visual inspection, not a claim of exhaustive accessibility certification.

Earlier development runs exposed reduced-motion drawer focus, component/initial bundle warnings and presentation-dependent E2E selectors. These failures drove the CDK focus handling, lazy menu/shared stylesheet and scoped assertion changes. Captures initially sampled overlays before CDK positioning; live browser inspection confirmed correct placement, and captures now wait for positioned geometry. Failed runs were not counted as successful verification.

## Visual evidence

Sanitized, real packaged-app fixture captures are retained locally under ignored `backend/build/e2e/goal-008/`:

- `before/`: unchanged frontend with the same corpus, desktop/mobile list/detail and accepted-answer captures.
- `review/`: owner-approved pilot, `questions-{desktop,mobile}.png`, `question-{desktop,mobile}.png`, accepted answers and full-page variants.
- `after/`: final list/detail/Home/About/setup/creation/moderation desktop/mobile captures; drawer, account/select overlays, long code, empty/loading/missing/unavailable/denied/account recovery, conflicts, rate limits, signed-out state and 200%/400% equivalent reflow.
- `after/pilot-checks.txt` and `after/application-checks.txt`: compact successful browser evidence; `after/{check-bootJar,integrationTest,e2eTest}.log` retain actual local command output; HTML/JUnit reports are in `backend/build/reports/tests/` and `backend/build/test-results/`.

Desktop is 1440×1000; mobile is 390×844 or a 1000px-tall full-page capture. Screenshots were visually inspected after the owner checkpoint, including corrected mobile navigation, aligned overlays, long identifiers/code, validation/conflict notices and accepted-answer hierarchy. Existing CI diagnostic upload includes the generated screenshots/reports; screenshots are not committed or published to an external image host.

## Completion and handoff

Branch `codex/goal-008-terminal-ui` is based on completed goal 007 master `d8e37f8`. Implementation commit `3abf2a2d2cad1a0f2af395c17416e05f9b3cdc56` is pushed and [PR #11](https://github.com/functionaldude/aso/pull/11) is open against master. The self-hosted [CI run 39040789740](https://github.com/functionaldude/aso/actions/runs/39040789740) passed on that implementation commit, including check/packaging, reproducible jOOQ generation, Chromium dependencies, OIDC smoke tests, service reset isolation, integration tests, packaged browser tests and diagnostics upload. [Verification reports and final screenshots](https://github.com/functionaldude/aso/actions/runs/39040789740/artifacts/12104352971) are available in its `verification-reports` artifact. No owner data, `.env`, Keycloak state, embedding settings or service configuration was changed. No merge, deployment or image publication occurred. The post-CI completion commit changes only goal status, PR links and verification documentation; current follow-up checks are visible on [PR #11](https://github.com/functionaldude/aso/pull/11/checks).
