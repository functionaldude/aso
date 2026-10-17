package com.functionaldude.aso

import com.functionaldude.aso.oidc.KeycloakFixture
import com.functionaldude.aso.oidc.OidcBrowserLogin
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Playwright
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import tools.jackson.databind.json.JsonMapper
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@Import(RateLimitIntegrationTest.TestClockConfiguration::class)
class RateLimitIntegrationTest {
    @org.springframework.boot.test.context.TestConfiguration
    class TestClockConfiguration {
        @Bean
        @Primary
        fun testClock(): Clock = clock
    }

    private val http = HttpClient.newHttpClient()
    private val json = JsonMapper.builder().build()

    @Test
    fun `REST and MCP share a user limit with distinct write budget and retry header`() {
        Playwright.create().use { playwright ->
            playwright.chromium().launch(BrowserType.LaunchOptions().setHeadless(true)).use { browser ->
                browser.newContext().use { context ->
                    val page = context.newPage()
                    page.navigate("$base/oauth2/authorization/oidc")
                    page.locator("#username").fill("alice")
                    page.locator("#password").fill("alice-local-only")
                    page.locator("#kc-login").click()
                    page.waitForURL("$base/")
                    val cookie = context.cookies(base).joinToString("; ") { "${it.name}=${it.value}" }
                    val csrf = json.readTree(context.request().get("$base/api/v1/csrf").text())["token"].asString()
                    val alice = login.tokens(browser, issuer, "alice", resource = "$base/mcp")["access_token"].asString()
                    val bob = login.tokens(browser, issuer, "bob", resource = "$base/mcp")["access_token"].asString()
                    clock.moveSeconds(10) // Exclude the shell's startup requests from this measured window.

                    fun request(
                        path: String,
                        body: String? = null,
                        token: String? = null,
                    ): HttpResponse<String> {
                        val builder = HttpRequest.newBuilder(URI("$base$path"))
                        if (token != null) builder.header("Authorization", "Bearer $token")
                        if (token == null) builder.header("Cookie", cookie).header("X-CSRF-TOKEN", csrf)
                        if (body != null) builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body))
                        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
                    }

                    fun rpc(
                        token: String,
                        name: String,
                        arguments: Map<String, Any> = emptyMap(),
                    ) = request(
                        "/mcp",
                        json.writeValueAsString(
                            mapOf(
                                "jsonrpc" to "2.0",
                                "id" to 1,
                                "method" to "tools/call",
                                "params" to mapOf("name" to name, "arguments" to arguments),
                            ),
                        ),
                        token,
                    )

                    val post =
                        mapOf(
                            "requestId" to UUID.randomUUID().toString(),
                            "title" to "Bounded request fixture",
                            "body" to "A reproducible isolated limit test.",
                            "tags" to listOf("limits"),
                        )
                    assertEquals(200, request("/api/v1/identity").statusCode())
                    assertEquals(200, rpc(alice, "create_question", post).statusCode())
                    val retryBody = json.writeValueAsString(post + ("requestId" to UUID.randomUUID().toString()))
                    val deniedWrite = request("/api/v1/questions", retryBody)
                    assertEquals(429, deniedWrite.statusCode())
                    assertEquals("rate_limited", json.readTree(deniedWrite.body())["code"].asString())
                    assertEquals("10", deniedWrite.headers().firstValue("Retry-After").orElseThrow())
                    val deniedMcpWrite = rpc(alice, "create_question", post + ("requestId" to UUID.randomUUID().toString()))
                    assertEquals(429, deniedMcpWrite.statusCode())
                    assertEquals(-32029, json.readTree(deniedMcpWrite.body())["error"]["code"].asInt())
                    assertEquals(200, rpc(alice, "identity").statusCode())
                    val deniedRequest = rpc(alice, "identity")
                    assertEquals(429, deniedRequest.statusCode())
                    assertEquals(-32029, json.readTree(deniedRequest.body())["error"]["code"].asInt())
                    assertEquals("10", deniedRequest.headers().firstValue("Retry-After").orElseThrow())
                    assertEquals(200, rpc(bob, "identity").statusCode())
                    clock.moveSeconds(10)
                    val retried = request("/api/v1/questions", retryBody)
                    assertEquals(200, retried.statusCode(), retried.body())
                    assertTrue(retried.body().contains("Bounded request fixture"))
                }
            }
        }
    }

    companion object {
        private class MovingClock : Clock() {
            private var current = Instant.parse("2026-01-01T00:00:00Z")

            override fun getZone(): ZoneId = ZoneId.of("UTC")

            override fun withZone(zone: ZoneId): Clock = this

            override fun instant(): Instant = current

            fun moveSeconds(seconds: Long) {
                current = current.plusSeconds(seconds)
            }
        }

        private val clock = MovingClock()
        private val port = ServerSocket(0).use { it.localPort }
        private val base = "http://127.0.0.1:$port"
        private val login = OidcBrowserLogin()
        private val keycloak =
            KeycloakFixture(
                login.callback,
                login.callback,
                "$base/mcp",
                "$base/login/oauth2/code/oidc",
            ).also { it.start() }
        private val issuer = keycloak.issuer
        private val db =
            PostgreSQLContainer(
                DockerImageName.parse(System.getProperty("aso.test.database-image")).asCompatibleSubstituteFor("postgres"),
            ).also { it.start() }

        @JvmStatic @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("server.port") { port }
            registry.add("spring.datasource.url", db::getJdbcUrl)
            registry.add("spring.datasource.username", db::getUsername)
            registry.add("spring.datasource.password", db::getPassword)
            registry.add("aso.rate-limit.requests") { 3 }
            registry.add("aso.rate-limit.writes") { 1 }
            registry.add("aso.rate-limit.window") { "10s" }
            mapOf(
                "issuer" to issuer,
                "web-client-id" to "aso-web",
                "web-client-secret" to "aso-web-secret-local-only",
                "public-url" to base,
                "mcp-audience" to "$base/mcp",
                "groups-claim" to "groups",
                "member-group" to "aso-members",
                "admin-group" to "aso-admins",
            ).forEach { (key, value) -> registry.add("aso.identity.$key") { value } }
        }

        @JvmStatic @AfterAll
        fun cleanup() {
            keycloak.close()
            db.close()
            login.close()
        }
    }
}
