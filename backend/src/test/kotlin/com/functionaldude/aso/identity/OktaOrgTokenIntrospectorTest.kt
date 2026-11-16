package com.functionaldude.aso.identity

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.server.resource.introspection.BadOpaqueTokenException
import tools.jackson.databind.json.JsonMapper
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OktaOrgTokenIntrospectorTest {
    private val json = JsonMapper.builder().build()
    private val now = Instant.parse("2026-11-16T12:00:00Z")
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val settings =
        IdentitySettings(
            "http://127.0.0.1:${server.address.port}",
            "aso-client",
            "https://aso.example.invalid",
            "https://aso.example.invalid/mcp",
            "groups",
            "aso-members",
            "Engineering",
            "okta-org",
        )
    private val introspector = OktaOrgTokenIntrospector(settings, Clock.fixed(now, ZoneOffset.UTC))
    private var introspection: Map<String, Any> = valid()
    private var userInfo: Map<String, Any> =
        mapOf(
            "sub" to "00u-alice",
            "name" to "Alice",
            "email" to "alice@example.invalid",
            "groups" to listOf("Engineering"),
        )
    private var introspectionBody: String? = null
    private var status = 200
    private var infoStatus = 200
    private var infoCalls = 0
    private var introspectionCalls = 0

    init {
        server.createContext("/oauth2/v1/introspect") { exchange ->
            introspectionCalls++
            assertEquals("POST", exchange.requestMethod)
            assertEquals("client_id=aso-client", exchange.requestURI.rawQuery)
            assertEquals(null, exchange.requestHeaders.getFirst("Authorization"))
            val form =
                exchange.requestBody.readAllBytes().toString(StandardCharsets.UTF_8).split('&').associate {
                    val parts = it.split('=', limit = 2)
                    parts[0] to URLDecoder.decode(parts[1], StandardCharsets.UTF_8)
                }
            assertEquals("opaque-token+/=", form["token"])
            assertEquals("access_token", form["token_type_hint"])
            val bytes = (introspectionBody ?: json.writeValueAsString(introspection)).toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/oauth2/v1/userinfo") { exchange ->
            infoCalls++
            assertEquals("GET", exchange.requestMethod)
            assertEquals("Bearer opaque-token+/=", exchange.requestHeaders.getFirst("Authorization"))
            val bytes = json.writeValueAsBytes(userInfo)
            exchange.sendResponseHeaders(infoStatus, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @AfterEach
    fun close() = server.stop(0)

    @Test
    fun `opaque access token uses public client introspection and canonical userinfo identity`() {
        val principal = introspector.introspect("opaque-token+/=")
        assertEquals("00u-alice", principal.name)
        assertEquals("00u-alice", principal.getAttribute("sub"))
        assertEquals(settings.issuer, principal.getAttribute("iss"))
        assertEquals(listOf("Engineering"), principal.getAttribute("groups"))
        assertEquals(now.plusSeconds(60), principal.getAttribute("exp"))
        assertEquals(1, infoCalls)
        // Neither JWT parsing nor local token claims supply the subject or groups.
        assertTrue(principal.authorities.any { it.authority == "SCOPE_openid" })
        introspector.introspect("opaque-token+/=")
        assertEquals(2, introspectionCalls, "No token cache hides revocation")
    }

    @Test
    fun `inactive wrong issuer foreign client expired premature malformed and non-access tokens fail before userinfo`() {
        val invalid =
            listOf(
                valid() + ("active" to false),
                valid() + ("active" to "true"),
                valid() - "active",
                valid() + ("iss" to "https://another.okta.example"),
                valid() - "iss",
                valid() + ("client_id" to "unrelated-client"),
                valid() - "client_id",
                valid() + ("exp" to now.epochSecond),
                valid() + ("exp" to now.plusSeconds(60).epochSecond.toString()),
                valid() + ("exp" to now.plusSeconds(60).epochSecond.toDouble() + 0.5),
                valid() - "exp",
                valid() + ("nbf" to now.plusSeconds(1).epochSecond),
                valid() + ("iat" to now.plusSeconds(1).epochSecond),
                valid() + ("scope" to "profile email groups"),
                valid() + ("scope" to "openid profile email"),
                valid() + ("scope" to "openid  groups"),
                valid() + ("scope" to listOf("openid", "groups")),
                valid() + ("token_type" to "ID"),
                valid() - "token_type",
                valid() - "uid", // Okta returns uid only for end-user access tokens, not ID/refresh tokens.
                valid() + ("uid" to ""),
                valid() - "sub",
            )
        invalid.forEach { candidate ->
            introspection = candidate
            assertFailsWith<BadOpaqueTokenException>(candidate.toString()) { introspector.introspect("opaque-token+/=") }
        }
        assertEquals(0, infoCalls)
    }

    @Test
    fun `userinfo must have matching stable subject and well formed configured groups`() {
        val validInfo = userInfo
        listOf(
            validInfo + ("sub" to "00u-bob"),
            validInfo - "sub",
            validInfo - "groups",
            validInfo + ("groups" to "Engineering"),
            validInfo + ("groups" to listOf("Engineering", 3)),
            validInfo + ("groups" to listOf("")),
        ).forEach { candidate ->
            userInfo = candidate
            assertFailsWith<BadOpaqueTokenException>(candidate.toString()) { introspector.introspect("opaque-token+/=") }
        }
    }

    @Test
    fun `unavailable endpoints redirects and malformed or oversized JSON fail closed`() {
        for (failure in listOf(302, 401, 429, 500)) {
            status = failure
            assertFailsWith<BadOpaqueTokenException> { introspector.introspect("opaque-token+/=") }
        }
        status = 200
        for (body in listOf("not json", "{}", "[]", "{\"active\":true,\"ignored\":\"${"a".repeat(66000)}\"}")) {
            introspectionBody = body
            assertFailsWith<BadOpaqueTokenException> { introspector.introspect("opaque-token+/=") }
        }
        introspectionBody = null
        infoStatus = 401
        assertFailsWith<BadOpaqueTokenException> { introspector.introspect("opaque-token+/=") }
    }

    @Test
    fun `only explicit org issuer configuration enables compatibility mode`() {
        assertFailsWith<IllegalArgumentException> { settings.copy(issuer = "https://example.okta.com/oauth2/default") }
        assertFailsWith<IllegalArgumentException> { settings.copy(issuer = "https://example.okta.com/") }
        assertFailsWith<IllegalArgumentException> { settings.copy(mcpTokenMode = "unchecked") }
        assertEquals(listOf("openid", "profile", "email", "groups"), settings.scopes)
        assertEquals(listOf("openid", "profile", "email"), settings.copy(mcpTokenMode = "jwt").scopes)
    }

    private fun valid(): Map<String, Any> =
        mapOf(
            "active" to true,
            "client_id" to "aso-client",
            "token_type" to "Bearer",
            "iss" to settings.issuer,
            "exp" to now.plusSeconds(60).epochSecond,
            "iat" to now.minusSeconds(60).epochSecond,
            "scope" to "openid profile email groups",
            "sub" to "mutable-login@example.invalid",
            "uid" to "00u-alice",
        )
}
