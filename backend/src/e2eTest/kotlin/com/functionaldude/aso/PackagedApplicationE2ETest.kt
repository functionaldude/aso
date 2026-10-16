package com.functionaldude.aso

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.Tracing
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import org.junit.jupiter.api.Test
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
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
import kotlin.test.assertTrue

class PackagedApplicationE2ETest {
    @Test
    fun `packaged application serves the UI API and client routes`() {
        val artifacts = Path.of(System.getProperty("aso.test.artifacts"))
        Files.createDirectories(artifacts)
        PostgreSQLContainer(
            DockerImageName.parse(System.getProperty("aso.test.database-image")).asCompatibleSubstituteFor("postgres"),
        ).use { database ->
            database.start()
            val port = ServerSocket(0).use { it.localPort }
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
                    "ASO_DB_URL" to database.jdbcUrl,
                    "ASO_DB_USER" to database.username,
                    "ASO_DB_PASSWORD" to database.password,
                ),
            )
            val application = builder.start()
            try {
                val baseUrl = "http://127.0.0.1:$port"
                waitForApplication(baseUrl, application, artifacts)
                Playwright.create().use { playwright ->
                    playwright.chromium().launch(BrowserType.LaunchOptions().setHeadless(true)).use { browser ->
                        browser.newContext(Browser.NewContextOptions().setViewportSize(1440, 1100)).use { context ->
                            context.tracing().start(Tracing.StartOptions().setScreenshots(true).setSnapshots(true))
                            try {
                                val page = context.newPage()
                                val errors = mutableListOf<String>()
                                page.onPageError { errors.add(it) }
                                page.navigate(baseUrl)
                                assertThat(
                                    page.getByRole(
                                        com.microsoft.playwright.options.AriaRole.HEADING,
                                        Page.GetByRoleOptions().setName("Solve it once.").setExact(false),
                                    ),
                                ).isVisible()
                                assertThat(
                                    page.getByRole(com.microsoft.playwright.options.AriaRole.STATUS),
                                ).containsText("ASO is connected")
                                page.screenshot(Page.ScreenshotOptions().setPath(artifacts.resolve("home-desktop.png")).setFullPage(true))
                                page.navigate("$baseUrl/about")
                                assertThat(
                                    page.getByRole(
                                        com.microsoft.playwright.options.AriaRole.HEADING,
                                        Page.GetByRoleOptions().setName("Agentic Stack Overflow"),
                                    ),
                                ).isVisible()
                                assertThat(page.locator(".version")).containsText("0.0.1-SNAPSHOT")
                                page.setViewportSize(390, 844)
                                page.navigate(baseUrl)
                                assertThat(
                                    page.getByRole(com.microsoft.playwright.options.AriaRole.STATUS),
                                ).containsText("ASO is connected")
                                assertTrue(page.evaluate("document.documentElement.scrollWidth <= window.innerWidth") as Boolean)
                                page.screenshot(Page.ScreenshotOptions().setPath(artifacts.resolve("home-mobile.png")).setFullPage(true))
                                val info = context.request().get("$baseUrl/api/v1/info")
                                assertEquals(200, info.status())
                                assertTrue(info.text().contains("\"name\":\"aso\""))
                                val missing = context.request().get("$baseUrl/api/v1/missing")
                                assertEquals(404, missing.status())
                                assertTrue(missing.headers()["content-type"]!!.contains("application/problem+json"))
                                assertTrue(errors.isEmpty(), errors.joinToString("\n"))
                            } finally {
                                context.tracing().stop(Tracing.StopOptions().setPath(artifacts.resolve("trace.zip")))
                            }
                        }
                    }
                }
            } finally {
                application.destroy()
                if (!application.waitFor(10, TimeUnit.SECONDS)) {
                    application.destroyForcibly().waitFor()
                }
            }
        }
    }

    private fun waitForApplication(
        baseUrl: String,
        application: Process,
        artifacts: Path,
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
        error("Packaged application did not become healthy.\n${Files.readString(artifacts.resolve("application.log"))}")
    }
}
