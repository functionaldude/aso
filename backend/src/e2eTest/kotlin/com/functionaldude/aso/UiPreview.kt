package com.functionaldude.aso

import com.functionaldude.aso.questions.AcceptanceInput
import com.functionaldude.aso.questions.CreateAnswerInput
import com.functionaldude.aso.questions.CreateCommentInput
import com.functionaldude.aso.questions.CreateQuestionInput
import com.functionaldude.aso.questions.EditQuestionInput
import com.functionaldude.aso.questions.TechnicalContextInput
import com.functionaldude.aso.questions.VisibilityInput
import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import com.microsoft.playwright.options.AriaRole
import com.microsoft.playwright.options.ReducedMotion
import com.microsoft.playwright.options.RequestOptions
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CountDownLatch

/** A real authenticated, disposable corpus. Nothing here is included in the application jar. */
class UiScenario(
    private val browser: Browser,
    private val base: String,
) : AutoCloseable {
    private val json = JsonMapper.builder().build()
    val alice = signIn("alice")
    private val bob = signIn("bob")
    private val admin = signIn("aso-admin")
    val questionId: String
    val openId: String
    val longId: String

    init {
        openId =
            question(
                bob,
                "OIDC callback succeeds but the browser returns to a signed-out session",
                "The authorization code exchange succeeds, but the next same-origin request receives a 401. What should I inspect in the session cookie and reverse-proxy configuration?",
                listOf("oidc", "spring-security", "cookies"),
            )["id"].asString()
        repeat(2) { post(bob, "/questions/$openId/occurrences", VisualEncounter(UUID.randomUUID().toString())) }
        question(
            alice,
            "PostgreSQL query slows down after adding a partial index",
            "The query plan changed after adding a partial index. I am collecting EXPLAIN output before proposing a fix.",
            listOf("postgresql", "performance"),
        )
        question(
            bob,
            "Angular signal effect reads stale state after route navigation",
            "An effect retains a previous route's state during rapid navigation. How can I distinguish a stale asynchronous response from signal scheduling?",
            listOf("angular", "signals"),
        )
        question(
            alice,
            "Container DNS lookup fails only on the first request",
            "A service recovers on its second request. I need to understand how startup readiness interacts with DNS resolution.",
            listOf("docker", "networking"),
        )
        longId =
            question(
                bob,
                "Long dependency identifiers wrap without losing their version or module coordinates in a multi-project Kotlin build",
                """
                Investigating `org.example.infrastructure.platform.observability:shared-runtime-instrumentation-conventions:2026.10.17-internal-build-candidate.4`.

                ```text
                ./gradlew :services:payments:dependencyInsight --dependency org.example.infrastructure.platform.observability:shared-runtime-instrumentation-conventions:2026.10.17-internal-build-candidate.4 --configuration runtimeClasspath
                ```
                """.trimIndent(),
                listOf("gradle", "kotlin"),
            )["id"].asString()
        val hidden =
            question(
                admin,
                "Retired diagnostic procedure",
                "This deliberately hidden fixture is available only in moderation.",
                listOf("operations"),
            )
        post(admin, "/admin/content/question/${hidden["id"].asString()}/visibility", VisibilityInput(hidden["version"].asInt(), true))
        val resolved =
            question(
                alice,
                "Gradle resolves a Java 21 variant in a Java 25 service",
                """
                After upgrading the service toolchain to Java 25, the shared library still publishes a Java 21 variant. The application builds locally, but dependency resolution fails in the clean CI environment.

                ### What I tried
                Both projects use the same Gradle wrapper. Clearing the dependency cache does not change the selected variant.

                ```kotlin
                java {
                    toolchain { languageVersion = JavaLanguageVersion.of(25) }
                }
                dependencies {
                    implementation("org.example.platform:shared-observability-conventions:2.4.0")
                }
                ```

                How should the producer and consumer declare their target JVM so this behaves consistently?
                """.trimIndent(),
                listOf("gradle", "java", "kotlin", "dependencies", "ci"),
                TechnicalContextInput(
                    errorText =
                        "No matching variant of org.example.platform:shared-observability-conventions:2.4.0 was found. " +
                            "The consumer needed a library compatible with JVM 25.",
                    dependencies = "org.example.platform:shared-observability-conventions:2.4.0",
                    versions = "Java 25 · Gradle 9.2.1 · Kotlin 2.3.21",
                    repository = "payments-service / build-logic",
                ),
            )
        questionId = resolved["id"].asString()
        val accepted =
            post(
                bob,
                "/questions/$questionId/answers",
                CreateAnswerInput(
                    UUID.randomUUID().toString(),
                    """
                    Set the toolchain and published target consistently in **both modules**, then republish the library. The consuming service cannot change the compatibility attributes of an already published variant.

                    ```kotlin
                    kotlin {
                        jvmToolchain(25)
                    }
                    tasks.withType<JavaCompile>().configureEach {
                        options.release.set(25)
                    }
                    ```

                    Inspect `outgoingVariants` on the producer before retrying dependency resolution. This makes the mismatch visible without deleting caches.
                    """.trimIndent(),
                    "Projects that intend to require Java 25 in both the shared library and consuming service.",
                    "Reproduced with a two-module fixture, ran ./gradlew clean check, and confirmed the published org.gradle.jvm.version attribute is 25.",
                ),
            )
        post(alice, "/questions/$questionId/acceptance", AcceptanceInput(resolved["version"].asInt(), accepted["id"].asString()))
        post(alice, "/content/answer/${accepted["id"].asString()}/vote", VisualVote(true))
        post(bob, "/content/question/$questionId/vote", VisualVote(true))
        post(admin, "/content/question/$questionId/vote", VisualVote(true))
        repeat(3) { post(alice, "/questions/$questionId/occurrences", VisualEncounter(UUID.randomUUID().toString())) }
        post(bob, "/questions/$questionId/occurrences", VisualEncounter(UUID.randomUUID().toString()))
        post(admin, "/questions/$questionId/occurrences", VisualEncounter(UUID.randomUUID().toString()))
        post(
            bob,
            "/content/question/$questionId/comments",
            CreateCommentInput(
                UUID.randomUUID().toString(),
                "Also check convention plugins: a module-level target can override the root toolchain setting.",
            ),
        )
        post(
            alice,
            "/content/answer/${accepted["id"].asString()}/comments",
            CreateCommentInput(
                UUID.randomUUID().toString(),
                "Confirmed in our clean build. Aligning the producer attributes resolved the mismatch.",
            ),
        )
        post(
            admin,
            "/questions/$questionId/answers",
            CreateAnswerInput(
                UUID.randomUUID().toString(),
                "If the library must retain Java 21 compatibility, keep its release target at 21 and check the consumer's requested variant attributes instead.",
                "Libraries shared with services that have not upgraded their runtime.",
                "Alternative approach; not tested in the original service.",
            ),
        )
    }

    private fun signIn(username: String): BrowserContext {
        val context = browser.newContext(Browser.NewContextOptions().setViewportSize(1440, 1000))
        try {
            val page = context.newPage()
            page.navigate("$base/oauth2/authorization/oidc")
            page.locator("#username").fill(username)
            page.locator("#password").fill("$username-local-only")
            page.locator("#kc-login").click()
            page.waitForURL("$base/")
            page.close()
            return context
        } catch (failure: Exception) {
            context.close()
            throw failure
        }
    }

    private fun question(
        context: BrowserContext,
        title: String,
        body: String,
        tags: List<String>,
        technical: TechnicalContextInput? = null,
    ) = post(context, "/questions", CreateQuestionInput(UUID.randomUUID().toString(), title, body, tags, technical))

    private fun post(
        context: BrowserContext,
        path: String,
        input: Any,
    ): JsonNode {
        val csrf = json.readTree(context.request().get("$base/api/v1/csrf").text())["token"].asString()
        val response =
            context.request().post(
                "$base/api/v1$path",
                RequestOptions
                    .create()
                    .setHeader(
                        "X-CSRF-TOKEN",
                        csrf,
                    ).setHeader("Content-Type", "application/json")
                    .setData(json.writeValueAsString(input)),
            )
        check(response.ok()) { "Visual corpus request failed: $path (${response.status()}) ${response.text()}" }
        return json.readTree(response.text())
    }

    fun capture(artifacts: Path) {
        Files.createDirectories(artifacts)
        val page = alice.newPage()
        for ((name, width, height) in listOf(Triple("desktop", 1440, 1000), Triple("mobile", 390, 844))) {
            page.setViewportSize(width, height)
            page.navigate("$base/questions?state=all")
            assertThat(page.locator(".question-row")).hasCount(6)
            page.screenshot(Page.ScreenshotOptions().setPath(artifacts.resolve("questions-$name-full.png")).setFullPage(true))
            page.screenshot(Page.ScreenshotOptions().setPath(artifacts.resolve("questions-$name.png")))
            page.navigate("$base/questions/$questionId")
            assertThat(page.locator(".answer")).hasCount(2)
            assertThat(page.locator(".accepted")).containsText("Accepted answer")
            page.screenshot(Page.ScreenshotOptions().setPath(artifacts.resolve("question-$name-full.png")).setFullPage(true))
            page.screenshot(Page.ScreenshotOptions().setPath(artifacts.resolve("question-$name.png")))
            page.locator(".accepted-answer").scrollIntoViewIfNeeded()
            page.screenshot(Page.ScreenshotOptions().setPath(artifacts.resolve("accepted-answer-$name.png")))
        }
        page.close()
    }

    fun inspectPilot(artifacts: Path) {
        Files.deleteIfExists(artifacts.resolve("pilot-checks.txt"))
        Files.deleteIfExists(artifacts.resolve("focus-diagnostic.txt"))
        val page = alice.newPage()
        val errors = mutableListOf<String>()
        page.onPageError { errors.add(it) }
        for (width in listOf(320, 390, 768, 1024, 1440)) {
            page.setViewportSize(width, 1000)
            page.navigate("$base/questions?state=all")
            assertThat(page.locator(".question-row")).hasCount(6)
            check(page.evaluate("document.documentElement.scrollWidth <= window.innerWidth") as Boolean) { "List overflow at $width" }
            page.navigate("$base/questions/$questionId")
            assertThat(page.locator(".answer")).hasCount(2)
            check(page.evaluate("document.documentElement.scrollWidth <= window.innerWidth") as Boolean) { "Detail overflow at $width" }
        }
        page.navigate("$base/questions?state=all")
        assertThat(page.locator(".question-row")).hasCount(6)
        val minimumContrast =
            page.evaluate(
                """
                () => {
                  const rgb = value => value.match(/[\d.]+/g).slice(0, 3).map(Number);
                  const luminance = color => rgb(color).map(c => c / 255).map(c => c <= .04045 ? c / 12.92 : ((c + .055) / 1.055) ** 2.4).reduce((sum, c, i) => sum + c * [.2126, .7152, .0722][i], 0);
                  const background = element => {
                    for (let current = element; current; current = current.parentElement) {
                      const value = getComputedStyle(current).backgroundColor;
                      if (value !== 'rgba(0, 0, 0, 0)' && value !== 'transparent') return value;
                    }
                    return 'rgb(11, 14, 17)';
                  };
                  const elements = [...document.querySelectorAll('.metadata, .metric-label, .row-metrics, .tags a, .nav-heading, .workspace-path, .status-badge')].filter(e => e.getClientRects().length);
                  return Math.min(...elements.map(e => {
                    const values = [luminance(getComputedStyle(e).color), luminance(background(e))].sort((a, b) => a - b);
                    return (values[1] + .05) / (values[0] + .05);
                  }));
                }
                """.trimIndent(),
            ) as Double
        check(minimumContrast >= 4.5) { "Rendered text contrast was $minimumContrast:1" }
        page.emulateMedia(Page.EmulateMediaOptions().setReducedMotion(ReducedMotion.REDUCE))
        page.setViewportSize(390, 844)
        page.navigate("$base/questions?state=all")
        val toggle = page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Open navigation"))
        toggle.focus()
        toggle.press("Enter")
        val navigation = page.getByRole(AriaRole.NAVIGATION, Page.GetByRoleOptions().setName("Main navigation"))
        assertThat(navigation).isVisible()
        assertThat(page.getByRole(AriaRole.LINK, Page.GetByRoleOptions().setName("ASO home"))).isFocused()
        navigation
            .getByRole(
                AriaRole.LINK,
                com.microsoft.playwright.Locator
                    .GetByRoleOptions()
                    .setName("About ASO"),
            ).focus()
        page.keyboard().press("Tab")
        assertThat(page.getByRole(AriaRole.LINK, Page.GetByRoleOptions().setName("ASO home"))).isFocused()
        page.screenshot(Page.ScreenshotOptions().setPath(artifacts.resolve("navigation-mobile.png")))
        page.keyboard().press("Escape")
        assertThat(toggle).isFocused()
        assertThat(navigation).not().isVisible()
        val account = page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Open account menu"))
        account.press("Enter")
        assertThat(page.getByRole(AriaRole.MENU)).isVisible()
        check(
            page.evaluate(
                "document.getAnimations().filter(a => a.playState === 'running').every(a => a.effect.getComputedTiming().duration <= 1)",
            ) as Boolean,
        ) { "The open menu did not respect reduced motion" }
        page.waitForFunction(
            "document.querySelector('[role=menu]').getBoundingClientRect().left >= 8 && document.querySelector('[role=menu]').getBoundingClientRect().top >= 40",
        )
        check(page.evaluate("document.querySelector('[role=menu]').getBoundingClientRect().right <= innerWidth") as Boolean)
        assertThat(page.getByRole(AriaRole.MENUITEM, Page.GetByRoleOptions().setName("Sign out"))).isFocused()
        check(
            page.evaluate(
                "getComputedStyle(document.activeElement, '::before').borderTopWidth === '2px' && getComputedStyle(document.activeElement, '::before').content !== 'none'",
            ) as Boolean,
        ) {
            "Keyboard menu focus indicator is not visible"
        }
        page.screenshot(Page.ScreenshotOptions().setPath(artifacts.resolve("account-mobile.png")))
        page.keyboard().press("Escape")
        assertThat(account).isFocused()
        val state = page.getByRole(AriaRole.COMBOBOX, Page.GetByRoleOptions().setName("State"))
        state.press("Space")
        assertThat(page.getByRole(AriaRole.LISTBOX)).isVisible()
        page.waitForFunction(
            "document.querySelector('[role=listbox]').getBoundingClientRect().left >= 8 && document.querySelector('[role=listbox]').getBoundingClientRect().top >= 40",
        )
        check(page.evaluate("document.querySelector('[role=listbox]').getBoundingClientRect().right <= innerWidth") as Boolean)
        page.screenshot(Page.ScreenshotOptions().setPath(artifacts.resolve("select-mobile.png")))
        page.keyboard().press("Escape")
        assertThat(state).isFocused()
        page.navigate("$base/questions/$questionId")
        assertThat(page.locator(".answer")).hasCount(2)
        val comments = page.locator("app-content-comments").first()
        val summary = comments.locator("summary")
        summary.click()
        comments.getByLabel("Add context (Markdown)").fill("A preserved context draft")
        summary.click()
        summary.click()
        assertThat(comments.getByLabel("Add context (Markdown)")).hasValue("A preserved context draft")
        page.navigate("$base/questions/$longId")
        val code = page.getByRole(AriaRole.REGION, Page.GetByRoleOptions().setName("Code block, scroll horizontally"))
        assertThat(code).isVisible()
        code.focus()
        page.keyboard().press("ArrowRight")
        page.waitForFunction("document.querySelector('pre[tabindex]').scrollLeft > 0")
        check(code.evaluate("element => element.scrollLeft > 0") as Boolean) { "Wide code did not scroll with the keyboard" }
        check(page.evaluate("document.documentElement.scrollWidth <= window.innerWidth") as Boolean) { "Long code overflowed the page" }
        page.screenshot(Page.ScreenshotOptions().setPath(artifacts.resolve("long-code-mobile.png")))
        check(errors.isEmpty()) { errors.joinToString("\n") }
        Files.writeString(
            artifacts.resolve("pilot-checks.txt"),
            "Passed: 320/390/768/1024/1440 list/detail overflow; keyboard drawer trapping and Escape focus; " +
                "account/select Escape focus; reduced-motion menu; context draft retention; long-code keyboard scrolling; " +
                "no browser page errors. Minimum sampled rendered text contrast: $minimumContrast:1.\n",
        )
        page.close()
    }

    fun inspectApplication(artifacts: Path) {
        Files.deleteIfExists(artifacts.resolve("application-checks.txt"))
        val page = alice.newPage()
        val errors = mutableListOf<String>()
        page.onPageError { errors.add(it) }
        val routes =
            listOf(
                "" to "Your engineering workspace",
                "about" to "Agentic Stack Overflow",
                "agent-setup" to "Agent setup",
                "questions/new" to "Ask a question",
                "questions/$openId" to "OIDC callback succeeds",
                "questions?state=all&view=unanswered" to "Questions",
                "questions?state=all&view=recurring" to "Questions",
            )
        for ((name, width) in listOf("desktop" to 1440, "mobile" to 390, "narrow" to 320)) {
            page.setViewportSize(width, 1000)
            for ((route, heading) in routes) {
                page.navigate("$base/$route")
                assertThat(page.locator("h1")).containsText(heading)
                page.reload()
                assertThat(page.locator("h1")).containsText(heading)
                if (route.isEmpty()) {
                    assertThat(page.locator(".question-row")).hasCount(5)
                    assertThat(page.locator(".question-row").first()).containsText("Resolved")
                    assertThat(page.locator("main")).not().containsText("Retired diagnostic procedure")
                }
                if (route == "agent-setup") assertThat(page.locator("#recommended-paragraph")).isVisible()
                check(
                    page.evaluate("document.documentElement.scrollWidth <= window.innerWidth") as Boolean,
                ) { "Overflow on $route at $width" }
                if (name !=
                    "narrow"
                ) {
                    page.screenshot(
                        Page
                            .ScreenshotOptions()
                            .setPath(
                                artifacts.resolve("${route.substringBefore('?').replace('/', '-').ifEmpty { "home" }}-$name-full.png"),
                            ).setFullPage(true),
                    )
                }
            }
        }
        page.setViewportSize(1440, 1000)
        page.navigate("$base/")
        assertThat(page.locator(".question-row")).hasCount(5)
        page.getByRole(AriaRole.LINK, Page.GetByRoleOptions().setName("About ASO")).click()
        assertThat(page.locator("main")).isFocused()
        check(page.title() == "About · ASO")
        // 200% text zoom, then the effective 360px viewport of 1440px at 400% browser zoom.
        for (route in listOf("questions?state=all", "questions/$longId", "questions/new", "agent-setup", "")) {
            page.setViewportSize(1440, 1000)
            page.navigate("$base/$route")
            assertThat(page.locator("h1")).isVisible()
            if (route.startsWith("questions?")) assertThat(page.locator(".question-row")).hasCount(6)
            if (route.isEmpty()) assertThat(page.locator(".question-row")).hasCount(5)
            if (route == "questions/new") assertThat(page.getByLabel("Title", Page.GetByLabelOptions().setExact(true))).isVisible()
            if (route == "agent-setup") assertThat(page.locator("#recommended-paragraph")).isVisible()
            page.addStyleTag(Page.AddStyleTagOptions().setContent("html { font-size: 32px !important; }"))
            check(
                page.evaluate("document.documentElement.scrollWidth <= window.innerWidth") as Boolean,
            ) { "200% text zoom overflow: $route" }
            for ((zoom, width) in listOf(200 to 720, 400 to 360)) {
                page.setViewportSize(width, 1000)
                page.reload()
                assertThat(page.locator("h1")).isVisible()
                if (route.startsWith("questions?")) assertThat(page.locator(".question-row")).hasCount(6)
                if (route.isEmpty()) assertThat(page.locator(".question-row")).hasCount(5)
                if (route == "questions/new") assertThat(page.getByLabel("Title", Page.GetByLabelOptions().setExact(true))).isVisible()
                if (route == "agent-setup") assertThat(page.locator("#recommended-paragraph")).isVisible()
                check(
                    page.evaluate("document.documentElement.scrollWidth <= window.innerWidth") as Boolean,
                ) { "$zoom% equivalent reflow overflow: $route" }
                if (route.startsWith("questions?")) page.screenshot(Page.ScreenshotOptions().setPath(artifacts.resolve("reflow-$zoom.png")))
            }
        }
        page.setViewportSize(390, 844)
        page.navigate("$base/questions?tag=fixture-no-match&state=all")
        assertThat(page.locator(".empty")).containsText("No questions match")
        page.screenshot(Page.ScreenshotOptions().setPath(artifacts.resolve("empty-mobile.png")))
        page.navigate("$base/questions/${UUID.randomUUID()}")
        assertThat(page.getByRole(AriaRole.ALERT)).containsText("Question not found")
        page.screenshot(Page.ScreenshotOptions().setPath(artifacts.resolve("missing-mobile.png")))
        page.route("**/api/v1/questions?**") { route ->
            route.fulfill(
                com.microsoft.playwright.Route
                    .FulfillOptions()
                    .setStatus(503)
                    .setContentType("application/problem+json")
                    .setBody("{}"),
            )
        }
        page.navigate("$base/questions?state=all")
        assertThat(page.getByRole(AriaRole.ALERT)).containsText("could not be completed")
        page.screenshot(Page.ScreenshotOptions().setPath(artifacts.resolve("unavailable-mobile.png")))
        page.unroute("**/api/v1/questions?**")
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Retry").setExact(true)).click()
        assertThat(page.locator(".question-row")).hasCount(6)
        val pending = mutableListOf<com.microsoft.playwright.Route>()
        page.route("**/api/v1/questions?**") { pending.add(it) }
        page.navigate("$base/questions?state=all")
        assertThat(page.locator(".loading-state")).containsText("Loading questions")
        page.screenshot(Page.ScreenshotOptions().setPath(artifacts.resolve("loading-mobile.png")))
        page.unroute("**/api/v1/questions?**")
        pending.forEach { it.resume() }
        assertThat(page.locator(".question-row")).hasCount(6)
        page.route("**/api/v1/identity") {
            it.fulfill(
                com.microsoft.playwright.Route
                    .FulfillOptions()
                    .setStatus(403)
                    .setContentType("application/problem+json")
                    .setBody("{}"),
            )
        }
        page.reload()
        assertThat(page.locator(".session-notices")).containsText("Workspace access is unavailable")
        page.screenshot(Page.ScreenshotOptions().setPath(artifacts.resolve("denied-mobile.png")))
        page.unroute("**/api/v1/identity")
        page.route("**/api/v1/identity") {
            it.fulfill(
                com.microsoft.playwright.Route
                    .FulfillOptions()
                    .setStatus(503)
                    .setContentType("application/problem+json")
                    .setBody("{}"),
            )
        }
        page.reload()
        assertThat(page.locator(".session-notices")).containsText("account is temporarily unavailable")
        page.screenshot(Page.ScreenshotOptions().setPath(artifacts.resolve("account-unavailable-mobile.png")))
        page.unroute("**/api/v1/identity")
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Retry account")).click()
        assertThat(page.locator(".question-row")).hasCount(6)
        val moderation = admin.newPage()
        for (width in listOf(1440, 390, 320)) {
            moderation.setViewportSize(width, 1000)
            moderation.navigate("$base/moderation")
            assertThat(moderation.locator(".question-row")).hasCount(7)
            val hidden =
                moderation
                    .locator(
                        ".question-row",
                    ).filter(
                        com.microsoft.playwright.Locator
                            .FilterOptions()
                            .setHasText("Retired diagnostic procedure"),
                    )
            assertThat(hidden).containsText("Hidden")
            moderation.screenshot(Page.ScreenshotOptions().setPath(artifacts.resolve("moderation-$width-full.png")).setFullPage(true))
            hidden
                .getByRole(
                    AriaRole.LINK,
                    com.microsoft.playwright.Locator
                        .GetByRoleOptions()
                        .setName("Retired diagnostic procedure"),
                ).click()
            moderation.waitForURL("$base/moderation/*")
            moderation.reload()
            assertThat(moderation.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Restore question"))).isVisible()
            check(moderation.evaluate("document.documentElement.scrollWidth <= window.innerWidth") as Boolean)
            moderation.screenshot(Page.ScreenshotOptions().setPath(artifacts.resolve("moderation-detail-$width.png")))
        }
        moderation.close()
        page.navigate("$base/questions/$questionId")
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Edit question")).click()
        page.getByLabel("Edit body (Markdown)").fill("My unsaved comparison draft")
        val saved = json.readTree(alice.request().get("$base/api/v1/questions/$questionId").text())["question"]
        post(
            alice,
            "/questions/$questionId/edit",
            EditQuestionInput(saved["version"].asInt(), saved["title"].asString(), "A concurrent saved revision", listOf("gradle")),
        )
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Save question")).click()
        assertThat(page.locator(".conflict-panel")).containsText("Your draft is preserved")
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Load latest content")).click()
        assertThat(page.locator(".latest-content")).containsText("A concurrent saved revision")
        assertThat(page.getByLabel("Edit body (Markdown)")).hasValue("My unsaved comparison draft")
        check(page.evaluate("document.documentElement.scrollWidth <= window.innerWidth") as Boolean) { "Mobile conflict editor overflow" }
        page.screenshot(Page.ScreenshotOptions().setPath(artifacts.resolve("edit-conflict-mobile-full.png")).setFullPage(true))
        page.locator(".conflict-panel").scrollIntoViewIfNeeded()
        page.screenshot(Page.ScreenshotOptions().setPath(artifacts.resolve("edit-conflict-mobile.png")))
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Cancel edit")).click()
        page.navigate("$base/questions/new")
        page.getByLabel("Title", Page.GetByLabelOptions().setExact(true)).focus()
        page.keyboard().press("Tab")
        assertThat(page.locator("mat-error")).containsText("A title is required")
        page.getByLabel("Title", Page.GetByLabelOptions().setExact(true)).fill("Rate limit preserves this draft")
        page.getByLabel("Problem description (Markdown)").fill("A retry should preserve the same question and request identifier.")
        val attempts = mutableListOf<String>()
        page.route("**/api/v1/questions") { route ->
            if (route.request().method() == "POST") {
                attempts.add(route.request().postData()!!)
                if (attempts.size ==
                    1
                ) {
                    route.fulfill(
                        com.microsoft.playwright.Route
                            .FulfillOptions()
                            .setStatus(
                                429,
                            ).setHeaders(
                                mapOf("Retry-After" to "7"),
                            ).setContentType("application/problem+json")
                            .setBody("{\"code\":\"rate_limited\"}"),
                    )
                } else {
                    route.resume()
                }
            } else {
                route.resume()
            }
        }
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Publish question")).click()
        assertThat(page.getByRole(AriaRole.ALERT)).containsText("Wait 7 seconds")
        assertThat(page.getByLabel("Title", Page.GetByLabelOptions().setExact(true))).hasValue("Rate limit preserves this draft")
        page.screenshot(Page.ScreenshotOptions().setPath(artifacts.resolve("rate-limit-mobile.png")))
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Publish question")).click()
        assertThat(page.locator("h1")).hasText("Rate limit preserves this draft")
        check(attempts.size == 2 && attempts[0] == attempts[1]) { "429 retry changed the content or request ID" }
        page.unroute("**/api/v1/questions")
        check(errors.isEmpty()) { errors.joinToString("\n") }
        page.close()
        browser.newContext(Browser.NewContextOptions().setViewportSize(390, 844)).use { signedOut ->
            val publicPage = signedOut.newPage()
            publicPage.navigate(base)
            assertThat(publicPage.locator(".session-notices")).containsText("Sign in to access")
            assertThat(publicPage.locator(".question-row")).hasCount(0)
            publicPage.screenshot(Page.ScreenshotOptions().setPath(artifacts.resolve("signed-out-mobile.png")))
        }
        Files.writeString(
            artifacts.resolve("application-checks.txt"),
            "Passed: authenticated Home five real questions across states; all route patterns direct navigation/refresh; 320/390/1440 layouts; 200% text zoom and 400% equivalent reflow; route focus/title; empty/missing/unavailable/retry; hidden moderation content; adjacent required validation; 429 Retry-After draft and request-ID preservation; signed-out privacy; no page errors.\n",
        )
    }

    override fun close() {
        admin.close()
        bob.close()
        alice.close()
    }
}

private data class VisualVote(
    val active: Boolean,
)

private data class VisualEncounter(
    val eventId: String,
)

fun main() {
    val artifacts = Path.of(System.getProperty("aso.test.artifacts"))
    val phase = System.getProperty("aso.ui.phase", "review")
    require(phase in listOf("before", "review", "after")) { "uiPhase must be before, review or after" }
    PackagedUiFixture(artifacts).use { fixture ->
        val shutdown = Thread { fixture.close() }
        Runtime.getRuntime().addShutdownHook(shutdown)
        try {
            Playwright.create().use { playwright ->
                playwright.chromium().launch(BrowserType.LaunchOptions().setHeadless(true)).use { browser ->
                    UiScenario(browser, fixture.baseUrl).use { scenario ->
                        scenario.capture(artifacts.resolve(phase))
                        println("UI preview: ${fixture.baseUrl}/questions?state=all")
                        println("Accepted question: ${fixture.baseUrl}/questions/${scenario.questionId}")
                        println("Open question: ${fixture.baseUrl}/questions/${scenario.openId}")
                        println("Disposable fixture accounts: alice, bob, aso-admin (documented development-only passwords).")
                        println("Sanitized screenshots: ${artifacts.resolve(phase)}")
                        if (!java.lang.Boolean.getBoolean("aso.ui.exit")) CountDownLatch(1).await()
                    }
                }
            }
        } finally {
            Runtime.getRuntime().removeShutdownHook(shutdown)
        }
    }
}
