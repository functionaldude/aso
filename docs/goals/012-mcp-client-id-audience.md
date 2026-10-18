# 012 — MCP client-ID audience compatibility

Status: complete

## Dependencies and prerequisites

[Goal 011](011-codex-onboarding.md), Java 25, Docker, and the existing shared public OIDC registration. Live diagnostic credentials stay outside the repository.

## Objective

Allow Codex to connect when its provider issues access tokens for the shared OIDC client ID instead of the MCP resource URL, preserving the [identity boundary](../../spec.md#identity-and-private-access).

## Included and excluded work

Accept either configured audience, retain ID-token rejection, update administrator documentation, and verify the packaged application. No provider registration changes, client secret, database migration or data reset.

## Acceptance criteria

- Signed access tokens with either exact configured audience authenticate the same individual; unrelated audiences still fail.
- ID tokens cannot authenticate MCP even with an accepted audience. Missing, malformed or unrelated scope claims fail.
- Issuer, signature, lifetime, subject, membership, browser-only actions and CSRF checks remain enforced.
- Real provider-issued client-ID tokens pass the application boundary, and the existing resource-audience workflows remain covered.

## Verification

Run `check bootJar`, `oidcSmokeTest`, `integrationTest`, `e2eTest`, and an application image build/smoke test. Check actual Codex discovery/identity against the live installation when available. Record unavailable checks honestly.

## Material decisions

On 2026-10-18 the owner explicitly requested accepting both `ASO_MCP_AUDIENCE` and `ASO_OIDC_CLIENT_ID`. Authentik issued a valid access token whose audience was only the configured shared client ID; the previous validator returned 401 despite successful CLI OAuth login. Codex consequently exposed the configured server in settings but no usable tools.

Audience matching remains exact. Because browser ID tokens also name the shared client ID, MCP additionally requires `openid` in a signed, space-delimited access-token `scope` string. The provider contract keeps this claim out of ID tokens. Keycloak and the observed Authentik tokens provide this distinction. This change accepts the shared application's access tokens; it does not identify agent software or prove human presence. Browser-session authorization remains separate.

## Progress and blockers

Delivered in [PR #15](https://github.com/functionaldude/aso/pull/15), merged as `c5a2946`. Local checks, master CI and live MCP authentication verification pass.

## Completion evidence

The pre-fix live MCP `initialize` request returned HTTP 401 with a legitimately signed Authentik access token containing the configured client-ID audience, `openid` scope and member group. No token or personal claims are retained. Unit tests cover both accepted audiences and arrays, unrelated/prefix audiences, invalid issuer/signature/expiry/subject, and missing/malformed/unrelated scopes. The real Keycloak integration fixture now issues client-ID-only access tokens for two members, an admin and a non-member; successful identities, outsider rejection, ID-token rejection and browser-only boundaries pass. Other integration/browser fixtures retain the resource audience.

### Automated verification

- `./gradlew --no-daemon spotlessApply check bootJar oidcSmokeTest integrationTest e2eTest`: passed with Java 25 / Gradle 9.2.1. Backend reports contain 25 unit tests, 2 OIDC tests, 19 integration tests and 2 packaged browser tests, with no failures or skips.
- Linux/amd64 `bootBuildImage` and `imageSmokeTest`: passed, including both image tests. The verified repair image records implementation revision `9037d6e85d3df1e81fcef769fc1e90321941716c`.
- [Master CI for the merged revision](https://github.com/functionaldude/aso/actions/runs/39148035083): passed, including publication. `latest` and `sha-c5a2946b11be1184d38f6a26ee74ff05747bb7d6` resolved to the same digest.

### Live deployment and authentication verification — 2026-10-18

The owner's first `latest` redeployment reused cached revision `dd7fb7e`; checking the tag alone did not identify this. Refreshed the manager's cached image using the existing registry credentials and updated only the ASO service with registry authentication and the published digest `sha256:e46b25bce0a6c4c946351599520d05eca1d286f882c179132ba151bace3fd390`. Swarm completed its rollout; the running container reports merged revision `c5a2946b11be1184d38f6a26ee74ff05747bb7d6`, zero restarts and HTTP health `UP`. Portainer's saved registry credentials and a subsequent Portainer redeployment were not changed or verified; future redeployments must pull the image with registry authentication rather than rely on a cached mutable tag.

Codex CLI **0.162.1**, using the existing configuration and OAuth credential store against Authentik **2026.8**, successfully initialized ASO and discovered all **15** tools with no discovery error. Its native app-server transport called `identity` successfully in an ephemeral diagnostic session, returning a persisted identity on channel `mcp`; no model turn was used. The saved access token had the configured client-ID audience and `openid` scope. The unexpired ID token had the same audience and no scope claim.

Direct HTTPS checks passed: MCP initialization, tool discovery and `identity` returned **200**; the unexpired ID token, tampered signature and anonymous request returned **401**; an untrusted Origin returned **403**; bearer requests to both browser-only identity/admin routes returned **401**. Tokens and personal claims were kept out of logs and goal records. No Q&A mutation, IdP registration change, database reset or schema migration was required.

Live browser reauthentication and a second Authentik user were not exercised in this operational check; the isolated browser and multi-user regression suites passed. Other providers still require their own verification.
