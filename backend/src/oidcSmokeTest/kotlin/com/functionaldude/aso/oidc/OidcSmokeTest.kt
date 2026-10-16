package com.functionaldude.aso.oidc

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Playwright
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Test
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import org.testcontainers.utility.MountableFile
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.regex.Pattern
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** No application context, development realm, .env, or product database is used. */
class OidcSmokeTest {
    private val json = JsonMapper.builder().build()
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    private val fixtureCallback = "\${ASO_PROBE_CALLBACK:http://127.0.0.1:8765/oidc/callback}"
    private val client = "aso-dev-probe"
    private val decoder = Base64.getUrlDecoder()

    @Test
    fun `fixture pin and client security contract`() {
        val pin = System.getProperty("aso.test.keycloak-image")
        assertTrue(Regex("quay.io/keycloak/keycloak:[^@]+@sha256:[a-f0-9]{64}").matches(pin))
        assertTrue(Files.readString(Path.of(System.getProperty("aso.test.compose"))).contains("image: $pin"))
        val realm = json.readTree(Files.readString(Path.of(System.getProperty("aso.test.realm"))))
        assertEquals("aso-dev", realm["realm"].asString())
        val clients = realm["clients"].associateBy { it["clientId"].asString() }
        assertEquals(setOf("aso-web", client), clients.keys)
        clients.values.forEach {
            assertEquals(
                setOf("basic", "profile", "email"),
                it["defaultClientScopes"]
                    .asSequence()
                    .map { scope ->
                        scope.asString()
                    }.toSet(),
            )
            assertTrue(it["standardFlowEnabled"].asBoolean())
            assertFalse(it["directAccessGrantsEnabled"].asBoolean())
            assertFalse(it["implicitFlowEnabled"].asBoolean())
            assertFalse(it["serviceAccountsEnabled"].asBoolean())
            assertEquals("S256", it["attributes"]["pkce.code.challenge.method"].asString())
        }
        assertEquals(
            listOf(fixtureCallback),
            clients
                .getValue(client)["redirectUris"]
                .asSequence()
                .map { it.asString() }
                .toList(),
        )
        assertFalse(clients.getValue("aso-web")["publicClient"].asBoolean())
        assertEquals(
            listOf("http://127.0.0.1:8080/login/oauth2/code/oidc"),
            clients
                .getValue("aso-web")["redirectUris"]
                .asSequence()
                .map { it.asString() }
                .toList(),
        )
    }

    @Test
    fun `real browser code PKCE login verifies all identities and rejects incorrect verifier`() {
        val artifacts = Path.of(System.getProperty("aso.test.oidc-artifacts"))
        Files.createDirectories(artifacts)
        Files.deleteIfExists(artifacts.resolve("keycloak.log"))
        Files.deleteIfExists(artifacts.resolve("result.txt"))
        LoopbackCallback().use { callback ->
            KeycloakFixture(callback.url).use { keycloak ->
                try {
                    keycloak.start()
                    val issuer = "http://${keycloak.host}:${keycloak.getMappedPort(8080)}/realms/aso-dev"
                    val discovery = get("$issuer/.well-known/openid-configuration")
                    assertEquals(issuer, discovery["issuer"].asString())
                    val protocol = "$issuer/protocol/openid-connect"
                    mapOf(
                        "authorization_endpoint" to "$protocol/auth",
                        "token_endpoint" to "$protocol/token",
                        "userinfo_endpoint" to "$protocol/userinfo",
                        "jwks_uri" to "$protocol/certs",
                    ).forEach { (name, expected) -> assertEquals(expected, discovery[name].asString()) }
                    assertTrue(discovery["code_challenge_methods_supported"].any { it.asString() == "S256" })
                    val keys = get(discovery["jwks_uri"].asString())["keys"]
                    Playwright.create().use { playwright ->
                        playwright.chromium().launch(BrowserType.LaunchOptions().setHeadless(true)).use { browser ->
                            val subjects = mutableSetOf<String>()
                            listOf("alice", "bob", "aso-admin", "outsider").forEachIndexed { index, username ->
                                val login = authorize(browser, discovery, username, callback.url)
                                val response = exchange(discovery, login, login.verifier)
                                assertEquals(200, response.statusCode(), "Code exchange failed for $username")
                                val tokens = json.readTree(response.body())
                                assertEquals("Bearer", tokens["token_type"].asString())
                                val access = verify(tokens["access_token"].asString(), keys, issuer, client)
                                val id = verify(tokens["id_token"].asString(), keys, issuer, client)
                                assertEquals(login.nonce, id["nonce"].asString())
                                assertEquals(client, access["azp"].asString())
                                val subject = access["sub"].asString()
                                assertEquals("00000000-0000-4000-8000-${(index + 1).toString().padStart(12, '0')}", subject)
                                assertEquals(subject, id["sub"].asString())
                                assertTrue(subjects.add(subject), "Users must have distinct subjects")
                                val expectedGroups =
                                    when (username) {
                                        "aso-admin" -> setOf("aso-members", "aso-admins")
                                        "outsider" -> emptySet()
                                        else -> setOf("aso-members")
                                    }
                                val userinfo = get(discovery["userinfo_endpoint"].asString(), tokens["access_token"].asString())
                                assertEquals(subject, userinfo["sub"].asString())
                                listOf(access, id, userinfo).forEach {
                                    assertEquals(username, it["preferred_username"].asString())
                                    assertEquals(
                                        expectedGroups,
                                        it
                                            .path("groups")
                                            .asSequence()
                                            .map { group -> group.asString() }
                                            .toSet(),
                                    )
                                }
                            }
                            val bad = authorize(browser, discovery, "alice", callback.url)
                            val rejected = exchange(discovery, bad, UUID.randomUUID().toString().repeat(2))
                            assertEquals(400, rejected.statusCode())
                            assertEquals("invalid_grant", json.readTree(rejected.body())["error"].asString())
                            assertTrue(json.readTree(rejected.body())["error_description"].asString().contains("PKCE"))
                            assertFalse(json.readTree(rejected.body()).has("access_token"))
                        }
                    }
                    Files.writeString(
                        artifacts.resolve("result.txt"),
                        "Four distinct seeded subjects; member/admin/outsider claims; discovery, JWKS signatures, issuer, audience, nonce, userinfo and incorrect PKCE verified.\n",
                    )
                } catch (failure: Throwable) {
                    // No browser traces, callback URLs, token responses or HTTP headers are retained.
                    Files.writeString(artifacts.resolve("keycloak.log"), keycloak.logs)
                    throw failure
                }
            }
        }
    }

    private data class Login(
        val callback: String,
        val code: String,
        val verifier: String,
        val nonce: String,
    )

    private fun authorize(
        browser: Browser,
        discovery: JsonNode,
        username: String,
        callback: String,
    ): Login {
        val verifier = UUID.randomUUID().toString().repeat(2)
        val challenge =
            Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()),
            )
        val state = UUID.randomUUID().toString()
        val nonce = UUID.randomUUID().toString()
        browser.newContext().use { context ->
            val callbackPattern = Pattern.compile(Pattern.quote(callback) + "\\?.*")
            val page = context.newPage()
            val networkFailures = mutableListOf<String>()
            page.onRequestFailed { request ->
                val url = URI(request.url())
                networkFailures.add("${url.scheme}://${url.authority}${url.path}: ${request.failure()}")
            }
            page.navigate(
                discovery["authorization_endpoint"].asString() + "?" +
                    form(
                        mapOf(
                            "client_id" to client,
                            "redirect_uri" to callback,
                            "response_type" to "code",
                            "scope" to "openid profile email",
                            "state" to state,
                            "nonce" to nonce,
                            "code_challenge" to challenge,
                            "code_challenge_method" to "S256",
                        ),
                    ),
            )
            page.locator("#username").fill(username)
            page.locator("#password").fill("$username-local-only")
            page.locator("#kc-login").click()
            try {
                page.waitForURL(callbackPattern)
            } catch (_: com.microsoft.playwright.TimeoutError) {
                error(
                    "Login did not reach callback for $username: ${URI(
                        page.url(),
                    ).scheme}://${URI(page.url()).authority}${URI(page.url()).path}; ${page.locator("body").innerText()}; $networkFailures",
                )
            }
            val query =
                URI(page.url()).rawQuery.split("&").associate {
                    val parts = it.split("=", limit = 2)
                    parts[0] to URLDecoder.decode(parts[1], Charsets.UTF_8)
                }
            assertEquals(state, query["state"])
            assertFalse(query.containsKey("error"), "Authorization rejected for $username")
            return Login(callback, requireNotNull(query["code"]), verifier, nonce)
        }
    }

    private fun exchange(
        discovery: JsonNode,
        login: Login,
        verifier: String,
    ): HttpResponse<String> =
        http.send(
            HttpRequest
                .newBuilder(URI(discovery["token_endpoint"].asString()))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        form(
                            mapOf(
                                "grant_type" to "authorization_code",
                                "client_id" to client,
                                "redirect_uri" to login.callback,
                                "code" to login.code,
                                "code_verifier" to verifier,
                            ),
                        ),
                    ),
                ).build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun verify(
        token: String,
        keys: JsonNode,
        issuer: String,
        audience: String,
    ): JsonNode {
        val parts = token.split('.')
        assertEquals(3, parts.size)
        val header = json.readTree(decoder.decode(parts[0]))
        assertEquals("RS256", header["alg"].asString())
        val jwk = keys.single { it["kid"].asString() == header["kid"].asString() }
        assertEquals("RSA", jwk["kty"].asString())
        assertEquals("sig", jwk["use"].asString())
        val key =
            KeyFactory
                .getInstance(
                    "RSA",
                ).generatePublic(
                    RSAPublicKeySpec(
                        BigInteger(1, decoder.decode(jwk["n"].asString())),
                        BigInteger(1, decoder.decode(jwk["e"].asString())),
                    ),
                )
        val signature = Signature.getInstance("SHA256withRSA")
        signature.initVerify(key)
        signature.update("${parts[0]}.${parts[1]}".toByteArray(Charsets.US_ASCII))
        assertTrue(signature.verify(decoder.decode(parts[2])), "JWT signature must match discovery JWKS")
        val claims = json.readTree(decoder.decode(parts[1]))
        assertEquals(issuer, claims["iss"].asString())
        val aud = claims["aud"]
        assertTrue(if (aud.isArray) aud.any { it.asString() == audience } else aud.asString() == audience, "JWT audience")
        val now = Instant.now().epochSecond
        assertTrue(claims["exp"].asLong() > now, "Token expired")
        assertTrue(claims["iat"].asLong() <= now + 5, "Future token")
        if (claims.has("nbf")) assertTrue(claims["nbf"].asLong() <= now + 5)
        return claims
    }

    private fun get(
        url: String,
        accessToken: String? = null,
    ): JsonNode {
        val request = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(10))
        if (accessToken != null) request.header("Authorization", "Bearer $accessToken")
        val response = http.send(request.build(), HttpResponse.BodyHandlers.ofString())
        assertEquals(200, response.statusCode(), "OIDC endpoint failed")
        return json.readTree(response.body())
    }

    private fun form(values: Map<String, String>) =
        values.entries.joinToString("&") { (key, value) -> "$key=${URLEncoder.encode(value, Charsets.UTF_8)}" }
}

/** Shared image and realm are independent of Compose and persistent development state. */
class KeycloakFixture(
    callback: String,
) : GenericContainer<KeycloakFixture>(DockerImageName.parse(System.getProperty("aso.test.keycloak-image"))) {
    init {
        withExposedPorts(8080)
        withCopyFileToContainer(
            MountableFile.forHostPath(System.getProperty("aso.test.realm")),
            "/opt/keycloak/data/import/aso-dev-realm.json",
        )
        withEnv("KC_DB", "dev-mem")
        withEnv("ASO_PROBE_CALLBACK", callback)
        withCommand("start-dev", "--import-realm", "--hostname-strict=false")
        withCreateContainerCmdModifier { it.hostConfig!!.withMemory(1024L * 1024 * 1024) }
        waitingFor(Wait.forHttp("/realms/aso-dev/.well-known/openid-configuration").forPort(8080).withStartupTimeout(Duration.ofMinutes(3)))
    }
}

/** Real loopback callback with an OS-allocated port, closed even when login fails. */
private class LoopbackCallback : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val url = "http://127.0.0.1:${server.address.port}/oidc/callback"

    init {
        server.createContext("/oidc/callback") { exchange ->
            val body = "Login complete".toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/plain")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
            exchange.close()
        }
        server.start()
    }

    override fun close() = server.stop(0)
}
