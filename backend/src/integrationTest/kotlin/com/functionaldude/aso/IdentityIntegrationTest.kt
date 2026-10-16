package com.functionaldude.aso

import com.functionaldude.aso.identity.IdentityService
import com.functionaldude.aso.identity.IdentitySettings
import com.functionaldude.aso.oidc.KeycloakFixture
import com.functionaldude.aso.oidc.OidcBrowserLogin
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Playwright
import org.jooq.DSLContext
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IdentityIntegrationTest {
    @LocalServerPort private var port: Int = 0

    @Autowired private lateinit var identities: IdentityService

    @Autowired private lateinit var database: DSLContext

    @Autowired private lateinit var settings: IdentitySettings
    private val json = JsonMapper.builder().build()
    private val http = HttpClient.newHttpClient()
    private val body = """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"identity","arguments":{}}}"""

    @Test
    fun `real signed access tokens resolve distinct people and reject outsiders probe audience ID tokens and invalid signatures`() {
        Playwright.create().use { playwright ->
            playwright.chromium().launch(BrowserType.LaunchOptions().setHeadless(true)).use { browser ->
                val ids = mutableSetOf<String>()
                listOf("alice", "bob", "aso-admin", "outsider").forEach { username ->
                    val tokens = login.tokens(browser, issuer, username, resource = resource)
                    val access = tokens["access_token"].asString()
                    val response = request("/mcp", access)
                    assertEquals(403, request("/mcp", access, origin = "https://untrusted.example.invalid").statusCode())
                    assertEquals(403, request("/mcp", access, post = false, origin = "https://untrusted.example.invalid").statusCode())
                    assertEquals(if (username == "outsider") 403 else 200, response.statusCode(), username)
                    if (username != "outsider") {
                        val identity = json.readTree(response.body())["result"]["structuredContent"]
                        assertEquals(username == "aso-admin", identity["admin"].asBoolean())
                        assertTrue(ids.add(identity["id"].asString()))
                        assertEquals("mcp", identity["channel"].asString())
                    }
                    assertEquals(
                        401,
                        request("/api/v1/identity", access, post = false).statusCode(),
                        "Bearer cannot access browser-only identity",
                    )
                    assertEquals(
                        401,
                        request("/api/v1/admin/identity", access, post = false).statusCode(),
                        "Even an admin bearer cannot invoke human actions",
                    )
                    assertEquals(401, request("/mcp", tokens["id_token"].asString()).statusCode(), "ID token is not an access token")
                    val parts = access.split('.')
                    val corrupt = parts[0] + "." + parts[1] + "." + (if (parts[2].first() == 'A') "B" else "A") + parts[2].drop(1)
                    assertEquals(401, request("/mcp", corrupt).statusCode())
                }
                val probe = login.tokens(browser, issuer, "alice", client = "aso-dev-probe")
                assertEquals(401, request("/mcp", probe["access_token"].asString()).statusCode())
            }
        }
        val anonymous = request("/mcp")
        assertEquals(401, anonymous.statusCode())
        assertTrue(
            anonymous
                .headers()
                .firstValue("WWW-Authenticate")
                .get()
                .contains("resource_metadata="),
        )
        assertEquals(401, request("/api/v1/identity", post = false).statusCode())
        assertEquals(401, request("/mcp?access_token=untrusted", post = false).statusCode())
        assertEquals(200, request("/.well-known/oauth-protected-resource/mcp", post = false).statusCode())
        assertEquals(403, request("/logout").statusCode(), "Logout requires CSRF")
    }

    @Test
    fun `identity persistence uses issuer subject rather than mutable attributes and survives concurrent resolution`() {
        // Focused storage test with trusted principals; real credential validation is tested above.
        fun principal(
            subject: String,
            name: String,
            email: String,
            issuerValue: String = issuer,
        ) = JwtAuthenticationToken(
            Jwt
                .withTokenValue("storage-test")
                .header("alg", "RS256")
                .issuer(issuerValue)
                .subject(subject)
                .claim("name", name)
                .claim("email", email)
                .claim("groups", listOf("aso-members"))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build(),
        )
        val first = identities.current(principal("storage-a", "Before", "shared@example.invalid"))
        val renamed = identities.current(principal("storage-a", "After", "changed@example.invalid"))
        assertEquals(first.id, renamed.id)
        assertEquals("After", renamed.displayName)
        val other = identities.current(principal("storage-b", "After", "changed@example.invalid"))
        assertNotEquals(first.id, other.id)
        val alternate = IdentityService(database, settings.copy(issuer = "https://other.example.invalid"))
        assertNotEquals(
            first.id,
            alternate.current(principal("storage-a", "After", "changed@example.invalid", "https://other.example.invalid")).id,
        )
        Executors.newFixedThreadPool(6).use { executor ->
            val futures =
                (1..12).map {
                    executor.submit<java.util.UUID> { identities.current(principal("concurrent", "Concurrent", "c@example.invalid")).id }
                }
            assertEquals(1, futures.map { it.get() }.toSet().size)
        }
    }

    private fun request(
        path: String,
        token: String? = null,
        post: Boolean = true,
        origin: String? = null,
    ): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path"))
        if (token != null) request.header("Authorization", "Bearer $token")
        if (origin != null) request.header("Origin", origin)
        if (post) request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body))
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }

    companion object {
        private val resource = "http://127.0.0.1:8080/mcp"
        private val login = OidcBrowserLogin()
        private val keycloak = KeycloakFixture(login.callback, login.callback, resource).also { it.start() }
        private val issuer = "http://${keycloak.host}:${keycloak.getMappedPort(8080)}/realms/aso-dev"
        private val db =
            PostgreSQLContainer(
                DockerImageName.parse(System.getProperty("aso.test.database-image")).asCompatibleSubstituteFor("postgres"),
            ).also {
                it.start()
            }

        @JvmStatic @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", db::getJdbcUrl)
            registry.add("spring.datasource.username", db::getUsername)
            registry.add("spring.datasource.password", db::getPassword)
            mapOf(
                "issuer" to issuer,
                "web-client-id" to "aso-web",
                "web-client-secret" to "aso-web-secret-local-only",
                "public-url" to "http://127.0.0.1:8080",
                "mcp-audience" to resource,
                "groups-claim" to "groups",
                "member-group" to "aso-members",
                "admin-group" to "aso-admins",
            ).forEach { (key, value) ->
                registry.add("aso.identity.$key") { value }
            }
        }

        @JvmStatic @AfterAll
        fun cleanup() {
            keycloak.close()
            db.close()
            login.close()
        }
    }
}
