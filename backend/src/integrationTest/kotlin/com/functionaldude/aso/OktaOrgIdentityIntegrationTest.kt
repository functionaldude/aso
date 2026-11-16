package com.functionaldude.aso

import com.functionaldude.aso.identity.IdentityService
import com.functionaldude.aso.identity.IdentitySettings
import com.functionaldude.aso.identity.RateLimits
import com.functionaldude.aso.jooq.tables.references.APP_USER
import com.sun.net.httpserver.HttpServer
import org.jooq.DSLContext
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken
import org.springframework.security.oauth2.core.oidc.OidcIdToken
import org.springframework.security.oauth2.core.oidc.OidcUserInfo
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import tools.jackson.databind.json.JsonMapper
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A protocol fixture, not live Okta interoperability evidence. Actual MCP requests traverse Spring's opaque-token filter. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OktaOrgIdentityIntegrationTest {
    @LocalServerPort private var port: Int = 0

    @Autowired private lateinit var identities: IdentityService

    @Autowired private lateinit var database: DSLContext

    @Autowired private lateinit var settings: IdentitySettings

    @Autowired private lateinit var limits: RateLimits
    private val http = HttpClient.newHttpClient()

    @Test
    fun `Engineering admin has one persisted browser and MCP identity despite mutable login email`() {
        // Spring-validated browser principal fixture; the existing Keycloak suites exercise actual OIDC code and PKCE login.
        val browser = browser("00u-alice", "Before", "before@example.invalid")
        val web = identities.current(browser, human = true)
        val response = request("alice")
        assertEquals(200, response.statusCode(), response.body())
        val mcp = json.readTree(response.body())["result"]["structuredContent"]
        assertEquals(web.id.toString(), mcp["id"].asString())
        assertEquals(web.subject, mcp["subject"].asString())
        assertTrue(web.admin)
        assertTrue(mcp["admin"].asBoolean())
        assertEquals("mcp", mcp["channel"].asString())
        assertEquals(1, database.fetchCount(APP_USER, APP_USER.SUBJECT.eq("00u-alice")))
        val renamed = identities.current(browser("00u-alice", "After", "after@example.invalid"), human = true)
        assertEquals(web.id, renamed.id)
        val other = identities.current(browser("00u-bob", "After", "after@example.invalid"), human = true)
        assertNotEquals(web.id, other.id, "Email is never an identity key")
        assertFailsWith<AccessDeniedException> {
            identities.current(browser("00u-alice", "Mismatch", "a@example.invalid", userInfoSubject = "00u-bob"), human = true)
        }
        assertEquals(401, get("/api/v1/admin/identity", "alice").statusCode(), "Bearer cannot invoke browser-only actions")
    }

    @Test
    fun `invalid foreign ID and refresh tokens are rejected and outsiders never persist`() {
        for (token in listOf("inactive", "foreign-client", "wrong-issuer", "expired", "id-token", "refresh-token")) {
            assertEquals(401, request(token).statusCode(), token)
        }
        assertEquals(403, request("outsider").statusCode())
        assertEquals(0, database.fetchCount(APP_USER, APP_USER.SUBJECT.eq("00u-outsider")))
        val anonymous = request(null)
        assertEquals(401, anonymous.statusCode())
        assertTrue(
            anonymous
                .headers()
                .firstValue("WWW-Authenticate")
                .get()
                .contains("resource_metadata="),
        )
        val setup = json.readTree(get("/api/v1/agent-setup").body())
        assertTrue(setup["scopes"].toList().map { it.asString() }.contains("groups"))
        val metadata = json.readTree(get("/.well-known/oauth-protected-resource/mcp").body())
        assertTrue(metadata["scopes_supported"].toList().map { it.asString() }.contains("groups"))
        assertEquals(listOf("openid", "profile", "email", "groups"), settings.scopes)
    }

    @Test
    fun `org bearer requests consume the same per-person rate budget as the browser`() {
        val browser = browser("00u-budget", "Budget", "budget@example.invalid")
        repeat(2) { assertNull(limits.acquire(browser, write = false)) }
        assertEquals(200, request("budget").statusCode())
        assertEquals(429, request("budget").statusCode(), "Opaque principals must consume the shared browser budget")
    }

    private fun browser(
        subject: String,
        name: String,
        email: String,
        userInfoSubject: String = subject,
    ): OAuth2AuthenticationToken {
        val now = Instant.now()
        val token =
            OidcIdToken(
                "validated-id-token",
                now.minusSeconds(60),
                now.plusSeconds(300),
                mapOf("iss" to issuer, "sub" to subject, "aud" to listOf("aso-client")),
            )
        val info = OidcUserInfo(mapOf("sub" to userInfoSubject, "name" to name, "email" to email, "groups" to listOf("Engineering")))
        val user = DefaultOidcUser(emptyList(), token, info)
        return OAuth2AuthenticationToken(user, user.authorities, "oidc")
    }

    private fun request(token: String?): HttpResponse<String> {
        val request =
            HttpRequest
                .newBuilder(URI("http://127.0.0.1:$port/mcp"))
                .header("Content-Type", "application/json")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"identity","arguments":{}}}""",
                    ),
                )
        token?.let { request.header("Authorization", "Bearer $it") }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun get(
        path: String,
        token: String? = null,
    ): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path"))
        token?.let { request.header("Authorization", "Bearer $it") }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }

    companion object {
        private val json = JsonMapper.builder().build()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        private val issuer = "http://127.0.0.1:${server.address.port}"
        private val db =
            PostgreSQLContainer(
                DockerImageName.parse(System.getProperty("aso.test.database-image")).asCompatibleSubstituteFor("postgres"),
            ).also { it.start() }

        init {
            server.createContext("/.well-known/openid-configuration") { exchange ->
                val bytes =
                    json.writeValueAsBytes(
                        mapOf(
                            "issuer" to issuer,
                            "authorization_endpoint" to "$issuer/oauth2/v1/authorize",
                            "token_endpoint" to "$issuer/oauth2/v1/token",
                            "userinfo_endpoint" to "$issuer/oauth2/v1/userinfo",
                            "jwks_uri" to "$issuer/oauth2/v1/keys",
                            "response_types_supported" to listOf("code"),
                            "subject_types_supported" to listOf("public"),
                            "id_token_signing_alg_values_supported" to listOf("RS256"),
                        ),
                    )
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            server.createContext("/oauth2/v1/introspect") { exchange ->
                assertEquals("client_id=aso-client", exchange.requestURI.rawQuery)
                assertEquals(null, exchange.requestHeaders.getFirst("Authorization"))
                val fields =
                    exchange.requestBody.readAllBytes().toString(StandardCharsets.UTF_8).split('&').associate {
                        val pieces = it.split('=', limit = 2)
                        pieces[0] to URLDecoder.decode(pieces[1], StandardCharsets.UTF_8)
                    }
                val token = fields["token"]!!
                val now = Instant.now()
                val claims =
                    mutableMapOf<String, Any>(
                        "active" to (token != "inactive"),
                        "client_id" to if (token == "foreign-client") "other-client" else "aso-client",
                        "iss" to if (token == "wrong-issuer") "https://wrong.example.invalid" else issuer,
                        "exp" to now.plusSeconds(if (token == "expired") -60 else 300).epochSecond,
                        "iat" to now.minusSeconds(60).epochSecond,
                        "token_type" to "Bearer",
                        "scope" to "openid profile email groups",
                        "sub" to "mutable-login@example.invalid",
                        "uid" to "00u-$token",
                    )
                if (token in listOf("id-token", "refresh-token")) claims.remove("uid")
                val bytes = json.writeValueAsBytes(claims)
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            server.createContext("/oauth2/v1/userinfo") { exchange ->
                val token = exchange.requestHeaders.getFirst("Authorization").removePrefix("Bearer ")
                val bytes =
                    json.writeValueAsBytes(
                        mapOf(
                            "sub" to "00u-$token",
                            "name" to "Current name",
                            "email" to "current@example.invalid",
                            "groups" to if (token == "outsider") emptyList() else listOf("Engineering"),
                        ),
                    )
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            server.start()
        }

        @JvmStatic @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", db::getJdbcUrl)
            registry.add("spring.datasource.username", db::getUsername)
            registry.add("spring.datasource.password", db::getPassword)
            mapOf(
                "issuer" to issuer,
                "client-id" to "aso-client",
                "public-url" to "http://127.0.0.1:8080",
                "mcp-audience" to "http://127.0.0.1:8080/mcp",
                "groups-claim" to "groups",
                "member-group" to "aso-members",
                "admin-group" to "Engineering",
                "mcp-token-mode" to "okta-org",
            ).forEach { (key, value) -> registry.add("aso.identity.$key") { value } }
            registry.add("aso.rate-limit.requests") { 3 }
            registry.add("aso.rate-limit.writes") { 1 }
        }

        @JvmStatic @AfterAll
        fun cleanup() {
            server.stop(0)
            db.close()
        }
    }
}
