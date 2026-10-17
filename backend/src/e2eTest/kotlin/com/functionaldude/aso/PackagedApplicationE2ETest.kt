package com.functionaldude.aso

import com.functionaldude.aso.oidc.KeycloakFixture
import com.functionaldude.aso.oidc.OidcBrowserLogin
import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import com.microsoft.playwright.options.AriaRole
import com.microsoft.playwright.options.RequestOptions
import org.junit.jupiter.api.Test
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import tools.jackson.databind.json.JsonMapper
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PackagedApplicationE2ETest {
    private val json = JsonMapper.builder().build()
    private val identityCall =
        """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"identity"}}"""

    @Test
    fun `packaged application authenticates browser users and protects the session bearer boundary`() {
        val artifacts = Path.of(System.getProperty("aso.test.artifacts"))
        Files.createDirectories(artifacts)
        // Authentication traces could contain credentials/callback codes. Keep only sanitized shell screenshots.
        Files.deleteIfExists(artifacts.resolve("trace.zip"))
        PostgreSQLContainer(
            DockerImageName.parse(System.getProperty("aso.test.database-image")).asCompatibleSubstituteFor("postgres"),
        ).use { database ->
            database.start()
            val port = ServerSocket(0).use { it.localPort }
            val baseUrl = "http://127.0.0.1:$port"
            OidcBrowserLogin().use { login ->
                KeycloakFixture(login.callback, login.callback, "$baseUrl/mcp", "$baseUrl/login/oauth2/code/oidc").use { keycloak ->
                    keycloak.start()
                    val issuer = keycloak.issuer
                    val builder =
                        ProcessBuilder(
                            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                            "-jar",
                            System.getProperty("aso.test.jar"),
                            "--server.port=$port",
                            "--server.address=127.0.0.1",
                        ).redirectErrorStream(true).redirectOutput(artifacts.resolve("application.log").toFile())
                    builder.environment().putAll(
                        mapOf(
                            "ASO_OIDC_ISSUER" to issuer,
                            "ASO_OIDC_WEB_CLIENT_ID" to "aso-web",
                            "ASO_OIDC_WEB_CLIENT_SECRET" to "aso-web-secret-local-only",
                            "ASO_PUBLIC_URL" to baseUrl,
                            "ASO_MCP_AUDIENCE" to "$baseUrl/mcp",
                            "ASO_OIDC_GROUPS_CLAIM" to "groups",
                            "ASO_OIDC_MEMBER_GROUP" to "aso-members",
                            "ASO_OIDC_ADMIN_GROUP" to "aso-admins",
                            "ASO_DB_URL" to database.jdbcUrl,
                            "ASO_DB_USER" to database.username,
                            "ASO_DB_PASSWORD" to database.password,
                        ),
                    )
                    val application = builder.start()
                    try {
                        waitForApplication(baseUrl, application)
                        Playwright.create().use { playwright ->
                            playwright.chromium().launch(BrowserType.LaunchOptions().setHeadless(true)).use { browser ->
                                shell(browser, baseUrl, artifacts)
                                val persisted =
                                    listOf("alice", "bob", "aso-admin", "outsider").mapNotNull {
                                        account(browser, baseUrl, issuer, login, it)
                                    }
                                assertEquals(3, persisted.toSet().size)
                                loginFailure(browser, baseUrl)
                                questions(browser, baseUrl, issuer, login, artifacts)
                                lifecycle(browser, baseUrl, artifacts)
                                engagement(browser, baseUrl, issuer, login, artifacts)
                            }
                        }
                    } finally {
                        application.destroy()
                        if (!application.waitFor(10, TimeUnit.SECONDS)) application.destroyForcibly().waitFor()
                    }
                }
            }
        }
    }

    private fun shell(
        browser: Browser,
        baseUrl: String,
        artifacts: Path,
    ) {
        browser.newContext(Browser.NewContextOptions().setViewportSize(1440, 1100)).use { context ->
            val page = context.newPage()
            val errors = mutableListOf<String>()
            page.onPageError { errors.add(it) }
            page.navigate(baseUrl)
            assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Solve it once.").setExact(false))).isVisible()
            assertThat(page.getByRole(AriaRole.STATUS)).containsText("ASO is connected")
            assertThat(page.locator(".identity-panel")).containsText("Sign in to access")
            page.screenshot(Page.ScreenshotOptions().setPath(artifacts.resolve("home-desktop.png")).setFullPage(true))
            page.navigate("$baseUrl/about")
            assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Agentic Stack Overflow"))).isVisible()
            assertThat(page.locator(".version")).containsText("0.0.1-SNAPSHOT")
            page.setViewportSize(390, 844)
            page.navigate(baseUrl)
            assertTrue(page.evaluate("document.documentElement.scrollWidth <= window.innerWidth") as Boolean)
            page.screenshot(Page.ScreenshotOptions().setPath(artifacts.resolve("home-mobile.png")).setFullPage(true))
            assertEquals(200, context.request().get("$baseUrl/api/v1/info").status())
            assertEquals(401, context.request().get("$baseUrl/api/v1/missing").status())
            assertEquals(401, context.request().post("$baseUrl/mcp", RequestOptions.create().setData("{}")).status())
            assertTrue(errors.isEmpty(), errors.joinToString("\n"))
        }
    }

    private fun account(
        browser: Browser,
        baseUrl: String,
        issuer: String,
        login: OidcBrowserLogin,
        username: String,
    ): String? {
        browser.newContext().use { context ->
            val page = context.newPage()
            page.navigate(baseUrl)
            page.getByRole(AriaRole.LINK, Page.GetByRoleOptions().setName("Sign in").setExact(true)).click()
            assertTrue(page.url().contains("code_challenge_method=S256"))
            page.locator("#username").fill(username)
            page.locator("#password").fill("$username-local-only")
            page.locator("#kc-login").click()
            page.waitForURL("$baseUrl/")
            var persisted: String? = null
            if (username == "outsider") {
                assertThat(page.getByRole(AriaRole.ALERT)).containsText("Workspace access is unavailable")
                assertEquals(403, context.request().get("$baseUrl/api/v1/identity").status())
            } else {
                assertThat(page.locator(".identity-panel")).containsText("Signed in as")
                val response = context.request().get("$baseUrl/api/v1/identity")
                assertEquals(200, response.status())
                val identity = json.readTree(response.text())
                persisted = identity["id"].asString()
                assertEquals(username == "aso-admin", identity["admin"].asBoolean())
                assertEquals(if (username == "aso-admin") 200 else 403, context.request().get("$baseUrl/api/v1/admin/identity").status())
                assertEquals(401, context.request().post("$baseUrl/mcp", RequestOptions.create().setData("{}")).status())
                val tokens = login.tokens(browser, issuer, username, resource = "$baseUrl/mcp")
                val mcp =
                    HttpClient.newHttpClient().send(
                        HttpRequest
                            .newBuilder(URI("$baseUrl/mcp"))
                            .header("Authorization", "Bearer " + tokens["access_token"].asString())
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(identityCall))
                            .build(),
                        HttpResponse.BodyHandlers.ofString(),
                    )
                assertEquals(200, mcp.statusCode())
                assertEquals(persisted, json.readTree(mcp.body())["result"]["structuredContent"]["id"].asString())
                assertEquals(
                    403,
                    context
                        .request()
                        .post(
                            "$baseUrl/api/v1/admin/identity",
                            RequestOptions.create().setData(
                                mapOf("admin" to true),
                            ),
                        ).status(),
                )
                val missing = context.request().get("$baseUrl/api/v1/missing")
                assertEquals(404, missing.status())
                assertTrue(missing.headers()["content-type"]!!.contains("application/problem+json"))
                assertEquals(404, context.request().get("$baseUrl/missing.js").status())
                // Removing an expired/lost session must recover in the UI without retaining a stale person.
                if (username == "alice") {
                    context.clearCookies()
                    page.reload()
                    assertThat(page.locator(".identity-panel")).containsText("Sign in to access")
                    page.getByRole(AriaRole.LINK, Page.GetByRoleOptions().setName("Sign in").setExact(true)).click()
                    page.locator("#username").fill(username)
                    page.locator("#password").fill("$username-local-only")
                    page.locator("#kc-login").click()
                    page.waitForURL("$baseUrl/")
                    assertEquals(persisted, json.readTree(context.request().get("$baseUrl/api/v1/identity").text())["id"].asString())
                }
            }
            page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Sign out").setExact(true)).click()
            page.waitForURL("$baseUrl/?signedOut")
            assertThat(page.locator(".identity-panel")).containsText("Sign in to access")
            assertEquals(401, context.request().get("$baseUrl/api/v1/identity").status())
            page.navigate("$baseUrl/oauth2/authorization/oidc")
            assertThat(page.locator("#username")).isVisible()
            return persisted
        }
    }

    private fun questions(
        browser: Browser,
        baseUrl: String,
        issuer: String,
        login: OidcBrowserLogin,
        artifacts: Path,
    ) {
        val aliceToken = login.tokens(browser, issuer, "alice", resource = "$baseUrl/mcp")["access_token"].asString()
        val bobToken = login.tokens(browser, issuer, "bob", resource = "$baseUrl/mcp")["access_token"].asString()

        fun mcp(
            token: String,
            name: String,
            arguments: Map<String, Any>,
        ): tools.jackson.databind.JsonNode {
            val response =
                HttpClient.newHttpClient().send(
                    HttpRequest
                        .newBuilder(
                            URI("$baseUrl/mcp"),
                        ).header("Authorization", "Bearer $token")
                        .header("Content-Type", "application/json")
                        .POST(
                            HttpRequest.BodyPublishers.ofString(
                                json.writeValueAsString(
                                    mapOf(
                                        "jsonrpc" to "2.0",
                                        "id" to 1,
                                        "method" to "tools/call",
                                        "params" to mapOf("name" to name, "arguments" to arguments),
                                    ),
                                ),
                            ),
                        ).build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
            assertEquals(200, response.statusCode())
            val result = json.readTree(response.body())["result"]
            assertEquals(false, result["isError"].asBoolean(), result.toString())
            return result["structuredContent"]
        }
        val alice = mcp(aliceToken, "identity", emptyMap())
        val bob = mcp(bobToken, "identity", emptyMap())
        val unsafe =
            """
            A dependency fails to resolve.

            <script>window.asoExecuted = true</script>
            <img src=x onerror="window.asoExecuted = true">

            [unsafe](javascript:alert%281%29)
            [documentation](https://example.invalid/docs)
            ![remote image](https://example.invalid/tracker.png)

            ```kotlin
            println("<script>code text</script>")
            ```
            """.trimIndent()
        val created =
            mcp(
                aliceToken,
                "create_question",
                mapOf(
                    "requestId" to
                        java.util.UUID
                            .randomUUID()
                            .toString(),
                    "title" to "Java target variant mismatch",
                    "body" to unsafe,
                    "tags" to listOf("gradle", "jvm"),
                    "context" to mapOf("versions" to "Java 25", "errorText" to "No matching variant"),
                ),
            )
        val id = created["id"].asString()
        browser.newContext(Browser.NewContextOptions().setViewportSize(1440, 1100)).use { context ->
            val page = context.newPage()
            val errors = mutableListOf<String>()
            page.onPageError { errors.add(it) }
            page.navigate("$baseUrl/oauth2/authorization/oidc")
            page.locator("#username").fill("bob")
            page.locator("#password").fill("bob-local-only")
            page.locator("#kc-login").click()
            page.waitForURL("$baseUrl/")
            page.navigate("$baseUrl/questions")
            assertThat(page.getByRole(AriaRole.LINK, Page.GetByRoleOptions().setName("Java target variant mismatch"))).isVisible()
            page.getByRole(AriaRole.LINK, Page.GetByRoleOptions().setName("Java target variant mismatch")).click()
            page.waitForURL("$baseUrl/questions/$id")
            page.reload()
            assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Java target variant mismatch"))).isVisible()
            assertThat(page.locator(".markdown pre")).containsText("<script>code text</script>")
            assertEquals(
                0,
                page
                    .locator(
                        ".markdown script, .markdown img, .markdown iframe, .markdown [onerror], .markdown a[href^='javascript:']",
                    ).count(),
            )
            assertEquals(null, page.evaluate("window.asoExecuted"))
            assertThat(page.locator("article").first()).containsText(alice["displayName"].asString())
            page.getByLabel("Answer (Markdown)").fill("Align the target JVM version in **both modules**.")
            page.getByLabel("Applicability", Page.GetByLabelOptions().setExact(true)).fill("Java 25 consumers")
            page.getByLabel("Verification evidence", Page.GetByLabelOptions().setExact(true)).fill("Verified with a clean compile")
            page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Publish answer").setExact(true)).click()
            assertThat(page.locator(".answer")).containsText(bob["displayName"].asString())
            assertThat(page.locator(".answer strong")).containsText("both modules")
            val shared = mcp(aliceToken, "get_question", mapOf("questionId" to id))
            assertEquals(alice["id"], shared["question"]["author"]["id"])
            assertEquals(bob["id"], shared["answers"]["items"][0]["author"]["id"])
            page.screenshot(Page.ScreenshotOptions().setPath(artifacts.resolve("question-desktop.png")).setFullPage(true))
            page.setViewportSize(390, 844)
            assertTrue(page.evaluate("document.documentElement.scrollWidth <= window.innerWidth") as Boolean)
            page.screenshot(Page.ScreenshotOptions().setPath(artifacts.resolve("question-mobile.png")).setFullPage(true))
            page.navigate("$baseUrl/questions/new")
            assertThat(page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Publish question"))).isDisabled()
            page.getByLabel("Title", Page.GetByLabelOptions().setExact(true)).fill("Runtime mismatch from browser")
            page.getByLabel("Problem description (Markdown)").fill("Authorization: Bearer private-test-fixture-value")
            page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Publish question")).click()
            assertThat(page.getByRole(AriaRole.ALERT)).containsText("remove credentials")
            page.getByLabel("Problem description (Markdown)").fill("The runtime differs from the configured target.")
            page.getByLabel("Tags (comma separated, up to 5)").fill("runtime")
            // Simulate a lost response after the server committed; retry must return the same question.
            var committedId: String? = null
            page.route("**/api/v1/questions") { route ->
                if (route.request().method() == "POST") {
                    val result = route.fetch()
                    assertEquals(200, result.status())
                    committedId = json.readTree(result.text())["id"].asString()
                    route.abort()
                } else {
                    route.resume()
                }
            }
            page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Publish question")).click()
            assertThat(page.getByRole(AriaRole.ALERT)).containsText("Retry with the same content")
            page.unroute("**/api/v1/questions")
            page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Publish question")).click()
            page.waitForURL("$baseUrl/questions/$committedId")
            val reply =
                mcp(
                    aliceToken,
                    "create_answer",
                    mapOf(
                        "questionId" to committedId!!,
                        "requestId" to
                            java.util.UUID
                                .randomUUID()
                                .toString(),
                        "body" to "Use a consistent runtime.",
                        "verification" to "Not tested; proposed fix.",
                    ),
                )
            assertEquals(alice["id"], reply["author"]["id"])
            page.reload()
            assertThat(page.locator(".answer")).containsText("Not tested; proposed fix.")
            assertThat(page.locator(".answer")).containsText(alice["displayName"].asString())
            val reverse = mcp(bobToken, "get_question", mapOf("questionId" to committedId))
            assertEquals(bob["id"], reverse["question"]["author"]["id"])
            page.navigate("$baseUrl/questions/${java.util.UUID.randomUUID()}")
            assertThat(page.getByRole(AriaRole.ALERT)).containsText("Question not found")
            assertEquals(404, context.request().get("$baseUrl/api/v1/questions/${java.util.UUID.randomUUID()}").status())
            assertEquals(
                403,
                context.request().get("$baseUrl/questions/missing.js").status(),
                "Unlisted nested assets remain denied, never forwarded",
            )
            // A controlled outage is visible and recoverable.
            page.route("**/api/v1/questions?*") {
                it.fulfill(
                    com.microsoft.playwright.Route
                        .FulfillOptions()
                        .setStatus(503)
                        .setContentType("application/json")
                        .setBody("{}"),
                )
            }
            page.navigate("$baseUrl/questions")
            assertThat(page.getByRole(AriaRole.ALERT)).containsText("could not be completed")
            page.unroute("**/api/v1/questions?*")
            page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Retry").setExact(true)).click()
            assertThat(page.getByRole(AriaRole.LINK, Page.GetByRoleOptions().setName("Java target variant mismatch"))).isVisible()
            page.getByLabel("Tag filter").fill("no-match")
            page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Apply filters")).click()
            assertThat(page.locator(".empty")).containsText("No questions match")
            page.getByLabel("Tag filter").fill("runtime")
            page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Apply filters")).click()
            assertThat(page.getByRole(AriaRole.LINK, Page.GetByRoleOptions().setName("Runtime mismatch from browser"))).isVisible()
            assertEquals(1, page.locator(".question-row").count())
            page.getByRole(AriaRole.COMBOBOX, Page.GetByRoleOptions().setName("State")).click()
            page.getByRole(AriaRole.OPTION, Page.GetByRoleOptions().setName("Resolved").setExact(true)).click()
            page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Apply filters")).click()
            assertThat(page.locator(".empty")).containsText("No questions match")
            repeat(21) {
                mcp(
                    aliceToken,
                    "create_question",
                    mapOf(
                        "requestId" to
                            java.util.UUID
                                .randomUUID()
                                .toString(),
                        "title" to "Pagination example $it",
                        "body" to "Focused isolated pagination fixture",
                        "tags" to listOf("paging"),
                    ),
                )
            }
            page.navigate("$baseUrl/questions?tag=paging")
            assertThat(page.locator(".question-row")).hasCount(20)
            page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Next page")).click()
            assertThat(page.locator(".question-row")).hasCount(1)
            page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Previous page")).click()
            assertThat(page.locator(".question-row")).hasCount(20)
            assertTrue(errors.isEmpty(), errors.joinToString("\n"))
        }
    }

    private fun lifecycle(
        browser: Browser,
        base: String,
        artifacts: Path,
    ) {
        fun signedIn(username: String): com.microsoft.playwright.BrowserContext {
            val context = browser.newContext(Browser.NewContextOptions().setViewportSize(1440, 1100))
            val page = context.newPage()
            page.navigate("$base/oauth2/authorization/oidc")
            page.locator("#username").fill(username)
            page.locator("#password").fill("$username-local-only")
            page.locator("#kc-login").click()
            page.waitForURL("$base/")
            return context
        }
        signedIn("alice").use { alice ->
            signedIn("bob").use { bob ->
                signedIn("aso-admin").use { admin ->
                    val owner = alice.pages().first()
                    val member = bob.pages().first()
                    val moderator = admin.pages().first()
                    val errors = mutableListOf<String>()
                    listOf(owner, member, moderator).forEach { page -> page.onPageError { errors.add(it) } }
                    owner.navigate("$base/questions/new")
                    owner.getByLabel("Title", Page.GetByLabelOptions().setExact(true)).fill("Lifecycle browser fixture")
                    owner.getByLabel("Problem description (Markdown)").fill("A focused reproducible problem.")
                    owner
                        .locator(
                            "body",
                        ).getByRole(
                            AriaRole.BUTTON,
                            com.microsoft.playwright.Locator
                                .GetByRoleOptions()
                                .setName("Publish question"),
                        ).click()
                    owner.waitForURL("$base/questions/*")
                    assertThat(
                        owner
                            .locator(
                                "body",
                            ).getByRole(
                                AriaRole.HEADING,
                                com.microsoft.playwright.Locator
                                    .GetByRoleOptions()
                                    .setName("Lifecycle browser fixture"),
                            ),
                    ).isVisible()
                    val id = owner.url().substringAfterLast('/')
                    val q = json.readTree(alice.request().get("$base/api/v1/questions/$id").text())["question"]
                    // A real concurrent update while the first editor retains its unsaved draft.
                    owner
                        .locator(
                            "body",
                        ).getByRole(
                            AriaRole.BUTTON,
                            com.microsoft.playwright.Locator
                                .GetByRoleOptions()
                                .setName("Edit question")
                                .setExact(true),
                        ).click()
                    owner.getByLabel("Edit body (Markdown)").fill("My preserved browser draft")
                    val csrf = json.readTree(alice.request().get("$base/api/v1/csrf").text())["token"].asString()
                    assertEquals(
                        200,
                        alice
                            .request()
                            .post(
                                "$base/api/v1/questions/$id/edit",
                                RequestOptions
                                    .create()
                                    .setHeader("X-CSRF-TOKEN", csrf)
                                    .setData(
                                        mapOf(
                                            "expectedVersion" to q["version"].asInt(),
                                            "title" to "Lifecycle browser fixture",
                                            "body" to "Concurrent saved version",
                                        ),
                                    ),
                            ).status(),
                    )
                    owner
                        .locator(
                            "body",
                        ).getByRole(
                            AriaRole.BUTTON,
                            com.microsoft.playwright.Locator
                                .GetByRoleOptions()
                                .setName("Save question")
                                .setExact(true),
                        ).click()
                    assertThat(owner.locator("body").getByRole(AriaRole.ALERT)).containsText("Content changed")
                    assertThat(owner.getByLabel("Edit body (Markdown)")).hasValue("My preserved browser draft")
                    owner
                        .locator(
                            "body",
                        ).getByRole(
                            AriaRole.BUTTON,
                            com.microsoft.playwright.Locator
                                .GetByRoleOptions()
                                .setName("Load latest content"),
                        ).click()
                    assertThat(owner.locator(".latest-content")).containsText("Concurrent saved version")
                    owner
                        .locator(
                            "body",
                        ).getByRole(
                            AriaRole.BUTTON,
                            com.microsoft.playwright.Locator
                                .GetByRoleOptions()
                                .setName("Use latest version with my draft"),
                        ).click()
                    owner
                        .locator(
                            "body",
                        ).getByRole(
                            AriaRole.BUTTON,
                            com.microsoft.playwright.Locator
                                .GetByRoleOptions()
                                .setName("Save question")
                                .setExact(true),
                        ).click()
                    assertThat(owner.locator("article").first()).containsText("My preserved browser draft")
                    val ownerComments = owner.locator("app-content-comments").first()
                    ownerComments.getByLabel("Add context (Markdown)").fill("Question **context** from owner")
                    ownerComments
                        .getByRole(
                            AriaRole.BUTTON,
                            com.microsoft.playwright.Locator
                                .GetByRoleOptions()
                                .setName("Publish context"),
                        ).click()
                    assertThat(ownerComments.locator(".comment")).containsText("Question context from owner")
                    ownerComments
                        .getByRole(
                            AriaRole.BUTTON,
                            com.microsoft.playwright.Locator
                                .GetByRoleOptions()
                                .setName("Edit comment"),
                        ).click()
                    ownerComments.getByLabel("Edit body (Markdown)").fill("Corrected question context")
                    ownerComments
                        .getByRole(
                            AriaRole.BUTTON,
                            com.microsoft.playwright.Locator
                                .GetByRoleOptions()
                                .setName("Save comment"),
                        ).click()
                    assertThat(ownerComments.locator(".comment")).containsText("Corrected question context")
                    member.navigate("$base/questions/$id")
                    assertThat(
                        member
                            .locator(
                                "body",
                            ).getByRole(
                                AriaRole.BUTTON,
                                com.microsoft.playwright.Locator
                                    .GetByRoleOptions()
                                    .setName("Edit question")
                                    .setExact(true),
                            ),
                    ).hasCount(0)
                    assertThat(
                        member
                            .locator(
                                "body",
                            ).getByRole(
                                AriaRole.BUTTON,
                                com.microsoft.playwright.Locator
                                    .GetByRoleOptions()
                                    .setName("Accept answer")
                                    .setExact(true),
                            ),
                    ).hasCount(0)
                    member.getByLabel("Answer (Markdown)").fill("First proposed fix")
                    member
                        .locator(
                            "body",
                        ).getByRole(
                            AriaRole.BUTTON,
                            com.microsoft.playwright.Locator
                                .GetByRoleOptions()
                                .setName("Publish answer"),
                        ).click()
                    assertThat(member.locator(".answer")).containsText("First proposed fix")
                    val firstAnswer = member.locator(".answer").first()
                    firstAnswer
                        .getByRole(
                            AriaRole.BUTTON,
                            com.microsoft.playwright.Locator
                                .GetByRoleOptions()
                                .setName("Edit answer")
                                .setExact(true),
                        ).click()
                    firstAnswer.getByLabel("Edit body (Markdown)").fill("Edited proposed fix")
                    firstAnswer
                        .getByRole(
                            AriaRole.BUTTON,
                            com.microsoft.playwright.Locator
                                .GetByRoleOptions()
                                .setName("Save answer"),
                        ).click()
                    assertThat(firstAnswer).containsText("Edited proposed fix")
                    firstAnswer.getByLabel("Add context (Markdown)").fill("Answer context from Bob")
                    firstAnswer
                        .getByRole(
                            AriaRole.BUTTON,
                            com.microsoft.playwright.Locator
                                .GetByRoleOptions()
                                .setName("Publish context"),
                        ).click()
                    assertThat(firstAnswer.locator(".comment")).containsText("Answer context from Bob")
                    member.getByLabel("Answer (Markdown)").fill("Second proposed fix")
                    member
                        .locator(
                            "body",
                        ).getByRole(
                            AriaRole.BUTTON,
                            com.microsoft.playwright.Locator
                                .GetByRoleOptions()
                                .setName("Publish answer"),
                        ).click()
                    assertThat(member.locator(".answer")).hasCount(2)
                    owner.reload()
                    owner
                        .locator(".answer")
                        .first()
                        .getByRole(
                            AriaRole.BUTTON,
                            com.microsoft.playwright.Locator
                                .GetByRoleOptions()
                                .setName("Accept answer")
                                .setExact(true),
                        ).click()
                    assertThat(owner.locator(".eyebrow")).containsText("Resolved question")
                    assertThat(owner.locator(".answer").first()).containsText("Accepted answer")
                    owner
                        .locator(".answer")
                        .nth(1)
                        .getByRole(
                            AriaRole.BUTTON,
                            com.microsoft.playwright.Locator
                                .GetByRoleOptions()
                                .setName("Accept answer")
                                .setExact(true),
                        ).click()
                    assertThat(owner.locator(".answer").nth(1)).containsText("Accepted answer")
                    owner
                        .locator(
                            "body",
                        ).getByRole(
                            AriaRole.BUTTON,
                            com.microsoft.playwright.Locator
                                .GetByRoleOptions()
                                .setName("Reopen question"),
                        ).click()
                    assertThat(owner.locator(".eyebrow")).containsText("Open question")
                    owner
                        .locator(".answer")
                        .first()
                        .getByRole(
                            AriaRole.BUTTON,
                            com.microsoft.playwright.Locator
                                .GetByRoleOptions()
                                .setName("Accept answer")
                                .setExact(true),
                        ).click()
                    assertThat(owner.locator(".eyebrow")).containsText("Resolved question")
                    moderator.navigate("$base/moderation")
                    moderator
                        .locator(
                            "body",
                        ).getByRole(
                            AriaRole.LINK,
                            com.microsoft.playwright.Locator
                                .GetByRoleOptions()
                                .setName("Lifecycle browser fixture"),
                        ).click()
                    moderator.waitForURL("$base/moderation/$id")
                    moderator.reload()
                    assertThat(
                        moderator
                            .locator(
                                "body",
                            ).getByRole(
                                AriaRole.HEADING,
                                com.microsoft.playwright.Locator
                                    .GetByRoleOptions()
                                    .setName("Lifecycle browser fixture"),
                            ),
                    ).isVisible()
                    moderator
                        .locator(".answer")
                        .first()
                        .getByRole(
                            AriaRole.BUTTON,
                            com.microsoft.playwright.Locator
                                .GetByRoleOptions()
                                .setName("Hide answer")
                                .setExact(true),
                        ).click()
                    assertThat(moderator.locator(".eyebrow")).containsText("Open question")
                    member.reload()
                    assertThat(member.locator(".answer")).hasCount(1)
                    moderator
                        .locator(".answer")
                        .first()
                        .getByRole(
                            AriaRole.BUTTON,
                            com.microsoft.playwright.Locator
                                .GetByRoleOptions()
                                .setName("Restore answer")
                                .setExact(true),
                        ).click()
                    assertThat(
                        moderator.locator(".answer").first().getByRole(
                            AriaRole.BUTTON,
                            com.microsoft.playwright.Locator
                                .GetByRoleOptions()
                                .setName("Hide answer")
                                .setExact(true),
                        ),
                    ).isVisible()
                    assertThat(moderator.locator(".accepted")).hasCount(0)
                    moderator
                        .locator(".answer")
                        .first()
                        .getByRole(
                            AriaRole.BUTTON,
                            com.microsoft.playwright.Locator
                                .GetByRoleOptions()
                                .setName("Edit answer")
                                .setExact(true),
                        ).click()
                    moderator
                        .locator(".answer")
                        .first()
                        .getByLabel("Edit body (Markdown)")
                        .fill("Admin corrected fix")
                    moderator
                        .locator(".answer")
                        .first()
                        .getByRole(
                            AriaRole.BUTTON,
                            com.microsoft.playwright.Locator
                                .GetByRoleOptions()
                                .setName("Save answer"),
                        ).click()
                    assertThat(moderator.locator(".answer").first()).containsText("Admin corrected fix")
                    moderator
                        .locator(
                            "body",
                        ).getByRole(
                            AriaRole.BUTTON,
                            com.microsoft.playwright.Locator
                                .GetByRoleOptions()
                                .setName("Hide comment")
                                .setExact(true),
                        ).first()
                        .click()
                    assertThat(
                        moderator
                            .locator(
                                "body",
                            ).getByRole(
                                AriaRole.BUTTON,
                                com.microsoft.playwright.Locator
                                    .GetByRoleOptions()
                                    .setName("Restore comment")
                                    .setExact(true),
                            ),
                    ).hasCount(1)
                    owner.reload()
                    assertThat(owner.locator("app-content-comments").first().locator(".comment")).hasCount(0)
                    moderator
                        .locator(
                            "body",
                        ).getByRole(
                            AriaRole.BUTTON,
                            com.microsoft.playwright.Locator
                                .GetByRoleOptions()
                                .setName("Restore comment")
                                .setExact(true),
                        ).click()
                    assertThat(
                        moderator
                            .locator(
                                "body",
                            ).getByRole(
                                AriaRole.BUTTON,
                                com.microsoft.playwright.Locator
                                    .GetByRoleOptions()
                                    .setName("Restore comment")
                                    .setExact(true),
                            ),
                    ).hasCount(0)
                    moderator
                        .locator(
                            "body",
                        ).getByRole(
                            AriaRole.BUTTON,
                            com.microsoft.playwright.Locator
                                .GetByRoleOptions()
                                .setName("Hide question")
                                .setExact(true),
                        ).click()
                    assertThat(
                        moderator
                            .locator(
                                "body",
                            ).getByRole(
                                AriaRole.BUTTON,
                                com.microsoft.playwright.Locator
                                    .GetByRoleOptions()
                                    .setName("Restore question")
                                    .setExact(true),
                            ),
                    ).isVisible()
                    owner.reload()
                    assertThat(owner.locator("body").getByRole(AriaRole.ALERT)).containsText("Question not found")
                    moderator.screenshot(Page.ScreenshotOptions().setPath(artifacts.resolve("moderation-desktop.png")).setFullPage(true))
                    moderator
                        .locator(
                            "body",
                        ).getByRole(
                            AriaRole.BUTTON,
                            com.microsoft.playwright.Locator
                                .GetByRoleOptions()
                                .setName("Restore question")
                                .setExact(true),
                        ).click()
                    assertThat(
                        moderator
                            .locator(
                                "body",
                            ).getByRole(
                                AriaRole.BUTTON,
                                com.microsoft.playwright.Locator
                                    .GetByRoleOptions()
                                    .setName("Hide question")
                                    .setExact(true),
                            ),
                    ).isVisible()
                    owner.reload()
                    assertThat(owner.locator(".eyebrow")).containsText("Open question")
                    assertThat(owner.locator(".answer")).hasCount(2)
                    owner.setViewportSize(390, 844)
                    assertTrue(owner.evaluate("document.documentElement.scrollWidth <= window.innerWidth") as Boolean)
                    owner.screenshot(Page.ScreenshotOptions().setPath(artifacts.resolve("lifecycle-mobile.png")).setFullPage(true))
                    assertTrue(errors.isEmpty(), errors.joinToString("\n"))
                }
            }
        }
    }

    private fun engagement(
        browser: Browser,
        base: String,
        issuer: String,
        login: OidcBrowserLogin,
        artifacts: Path,
    ) {
        fun signedIn(username: String): com.microsoft.playwright.BrowserContext {
            val context = browser.newContext(Browser.NewContextOptions().setViewportSize(1440, 1100))
            val page = context.newPage()
            page.navigate("$base/oauth2/authorization/oidc")
            page.locator("#username").fill(username)
            page.locator("#password").fill("$username-local-only")
            page.locator("#kc-login").click()
            page.waitForURL("$base/")
            return context
        }
        signedIn("alice").use { alice ->
            signedIn("bob").use { bob ->
                fun post(
                    context: com.microsoft.playwright.BrowserContext,
                    path: String,
                    payload: Any,
                ): tools.jackson.databind.JsonNode {
                    val csrf = json.readTree(context.request().get("$base/api/v1/csrf").text())["token"].asString()
                    val response =
                        context.request().post(
                            "$base/api/v1$path",
                            RequestOptions.create().setHeader("X-CSRF-TOKEN", csrf).setData(payload),
                        )
                    assertEquals(200, response.status(), response.text())
                    return json.readTree(response.text())
                }
                val tag = "browser-engagement"

                fun question(title: String) =
                    post(
                        alice,
                        "/questions",
                        mapOf(
                            "requestId" to
                                java.util.UUID
                                    .randomUUID()
                                    .toString(),
                            "title" to title,
                            "body" to "An isolated recurring problem.",
                            "tags" to listOf(tag),
                        ),
                    )["id"].asString()
                val q = question("Many developers encounter this")
                val repeat = question("One developer encounters this often")
                val answer =
                    post(
                        bob,
                        "/questions/$q/answers",
                        mapOf(
                            "requestId" to
                                java.util.UUID
                                    .randomUUID()
                                    .toString(),
                            "body" to "Align JVM targets.",
                        ),
                    )["id"].asString()
                val owner = alice.pages().first()
                val member = bob.pages().first()
                val errors = mutableListOf<String>()
                listOf(owner, member).forEach { page -> page.onPageError { errors.add(it) } }
                owner.navigate("$base/questions/$q")
                val ownerQuestion = owner.locator("app-engagement-controls").first()
                assertThat(ownerQuestion).containsText("All time: 0 encounters · 0 distinct developers")
                assertEquals(
                    0,
                    ownerQuestion
                        .getByRole(
                            AriaRole.BUTTON,
                            com.microsoft.playwright.Locator
                                .GetByRoleOptions()
                                .setName("Upvote")
                                .setExact(true),
                        ).count(),
                )
                // The server commits, but the browser loses the response. Its explicit retry reuses eventId.
                val requests = mutableListOf<String>()
                var loseResponse = true
                owner.route("**/api/v1/questions/$q/occurrences") { route ->
                    requests.add(route.request().postData()!!)
                    if (loseResponse) {
                        loseResponse = false
                        route.fetch()
                        route.abort()
                    } else {
                        route.resume()
                    }
                }
                ownerQuestion
                    .getByRole(
                        AriaRole.BUTTON,
                        com.microsoft.playwright.Locator
                            .GetByRoleOptions()
                            .setName("Record this encounter"),
                    ).click()
                assertThat(ownerQuestion.getByRole(AriaRole.ALERT)).isVisible()
                assertEquals(
                    1,
                    json
                        .readTree(
                            alice.request().get("$base/api/v1/questions/$q").text(),
                        )["question"]["occurrences"]["allTime"]["encounters"]
                        .asInt(),
                )
                ownerQuestion
                    .getByRole(
                        AriaRole.BUTTON,
                        com.microsoft.playwright.Locator
                            .GetByRoleOptions()
                            .setName("Record this encounter"),
                    ).click()
                assertThat(ownerQuestion).containsText("All time: 1 encounters · 1 distinct developers")
                assertEquals(2, requests.size)
                assertEquals(requests[0], requests[1])
                owner.reload()
                assertThat(ownerQuestion.getByRole(AriaRole.STATUS)).containsText("Encounter recorded")
                ownerQuestion
                    .getByRole(
                        AriaRole.BUTTON,
                        com.microsoft.playwright.Locator
                            .GetByRoleOptions()
                            .setName("Start a later independent encounter"),
                    ).click()
                ownerQuestion
                    .getByRole(
                        AriaRole.BUTTON,
                        com.microsoft.playwright.Locator
                            .GetByRoleOptions()
                            .setName("Record this encounter"),
                    ).click()
                assertThat(ownerQuestion).containsText("All time: 2 encounters · 1 distinct developers")
                assertTrue(requests[0] != requests[2])
                member.navigate("$base/questions/$q")
                val memberQuestion = member.locator("app-engagement-controls").first()
                memberQuestion
                    .getByRole(
                        AriaRole.BUTTON,
                        com.microsoft.playwright.Locator
                            .GetByRoleOptions()
                            .setName("Upvote")
                            .setExact(true),
                    ).click()
                assertThat(memberQuestion).containsText("1 upvotes")
                memberQuestion
                    .getByRole(
                        AriaRole.BUTTON,
                        com.microsoft.playwright.Locator
                            .GetByRoleOptions()
                            .setName("Undo upvote"),
                    ).click()
                assertThat(memberQuestion).containsText("0 upvotes")
                memberQuestion
                    .getByRole(
                        AriaRole.BUTTON,
                        com.microsoft.playwright.Locator
                            .GetByRoleOptions()
                            .setName("Record this encounter"),
                    ).click()
                assertThat(memberQuestion).containsText("All time: 3 encounters · 2 distinct developers")
                assertThat(memberQuestion).containsText("Last 7 days (UTC): 3 encounters · 2 distinct developers")
                val token = login.tokens(browser, issuer, "alice", resource = "$base/mcp")["access_token"].asString()
                val rpc =
                    mapOf(
                        "jsonrpc" to "2.0",
                        "id" to 1,
                        "method" to "tools/call",
                        "params" to
                            mapOf(
                                "name" to "set_vote",
                                "arguments" to
                                    mapOf(
                                        "kind" to "answer",
                                        "targetId" to answer,
                                        "active" to true,
                                        "verification" to "Applied the fix and the previously failing JVM build passes.",
                                    ),
                            ),
                    )
                val response =
                    HttpClient.newHttpClient().send(
                        HttpRequest
                            .newBuilder(
                                URI("$base/mcp"),
                            ).header(
                                "Authorization",
                                "Bearer $token",
                            ).header(
                                "Content-Type",
                                "application/json",
                            ).POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(rpc)))
                            .build(),
                        HttpResponse.BodyHandlers.ofString(),
                    )
                assertEquals(200, response.statusCode())
                assertFalse(json.readTree(response.body())["result"]["isError"].asBoolean())
                owner.reload()
                val answerControls = owner.locator("app-engagement-controls").nth(1)
                assertThat(answerControls).containsText("1 upvotes")
                answerControls
                    .getByRole(
                        AriaRole.BUTTON,
                        com.microsoft.playwright.Locator
                            .GetByRoleOptions()
                            .setName("Read upvote evidence"),
                    ).click()
                assertThat(answerControls).containsText("Caller-reported evidence")
                assertThat(answerControls).containsText("No independent server verification or human acceptance")
                assertThat(answerControls).containsText("Applied the fix and the previously failing JVM build passes.")
                assertEquals(0, owner.locator(".accepted").count())
                repeat(4) {
                    post(
                        bob,
                        "/questions/$repeat/occurrences",
                        mapOf(
                            "eventId" to
                                java.util.UUID
                                    .randomUUID()
                                    .toString(),
                        ),
                    )
                }
                member.navigate("$base/questions?tag=$tag&state=all&view=recurring")
                assertThat(member.locator(".question-row")).hasCount(2)
                assertThat(member.locator(".question-row").first()).containsText("Many developers encounter this")
                assertThat(member.locator(".question-row").nth(1)).containsText("All time: 4 encounters · 1 distinct developers")
                member.navigate("$base/questions?tag=$tag&state=all&view=unanswered")
                assertThat(member.locator(".question-row")).hasCount(1)
                assertThat(member.locator(".question-row").first()).containsText("One developer encounters this often")
                member.navigate("$base/questions?tag=$tag&state=all&view=recent")
                assertThat(member.locator(".question-row")).hasCount(2)
                assertThat(member.locator(".question-row").first()).containsText("One developer encounters this often")
                owner.screenshot(Page.ScreenshotOptions().setPath(artifacts.resolve("engagement-desktop.png")).setFullPage(true))
                member.navigate("$base/questions?tag=$tag&state=all&view=recurring")
                member.setViewportSize(390, 844)
                assertThat(member.locator(".question-row")).hasCount(2)
                assertTrue(member.evaluate("document.documentElement.scrollWidth <= window.innerWidth") as Boolean)
                member.screenshot(Page.ScreenshotOptions().setPath(artifacts.resolve("recurring-mobile.png")).setFullPage(true))
                assertTrue(errors.isEmpty(), errors.joinToString("\n"))
            }
        }
    }

    private fun loginFailure(
        browser: Browser,
        baseUrl: String,
    ) {
        browser.newContext().use { context ->
            val page = context.newPage()
            page.navigate("$baseUrl/login/oauth2/code/oidc?code=invalid&state=invalid")
            assertThat(page.getByRole(AriaRole.ALERT)).containsText("Sign-in failed")
            assertThat(page.getByRole(AriaRole.LINK, Page.GetByRoleOptions().setName("Sign in").setExact(true))).isVisible()
        }
    }

    private fun waitForApplication(
        baseUrl: String,
        application: Process,
    ) {
        val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build()
        val deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos()
        while (System.nanoTime() < deadline && application.isAlive) {
            val response =
                runCatching {
                    http.send(
                        HttpRequest.newBuilder(URI("$baseUrl/actuator/health")).timeout(Duration.ofSeconds(2)).build(),
                        HttpResponse.BodyHandlers.ofString(),
                    )
                }.getOrNull()
            if (response?.statusCode() == 200) return
            Thread.sleep(250)
        }
        error("Packaged application did not become healthy; inspect the sanitized application.log")
    }
}
