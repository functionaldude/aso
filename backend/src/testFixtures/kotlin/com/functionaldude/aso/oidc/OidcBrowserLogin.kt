package com.functionaldude.aso.oidc

import com.microsoft.playwright.Browser
import com.sun.net.httpserver.HttpServer
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.regex.Pattern

/** Real code + PKCE exchange helper. Tokens stay in memory and are never logged. */
class OidcBrowserLogin : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val callback = "http://127.0.0.1:${server.address.port}/oidc/callback"

    init {
        server.createContext("/oidc/callback") { exchange ->
            val body = "Login complete".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
            exchange.close()
        }
        server.start()
    }

    fun tokens(
        browser: Browser,
        issuer: String,
        username: String,
        client: String = "aso",
        resource: String? = null,
    ): JsonNode {
        val verifier = UUID.randomUUID().toString().repeat(2)
        val challenge =
            Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()),
            )
        val state = UUID.randomUUID().toString()
        val params =
            mutableMapOf(
                "client_id" to client,
                "redirect_uri" to callback,
                "response_type" to "code",
                "scope" to "openid profile email",
                "state" to state,
                "code_challenge" to challenge,
                "code_challenge_method" to "S256",
            )
        if (resource != null) params["resource"] = resource
        val code =
            browser.newContext().use { context ->
                val page = context.newPage()
                page.navigate("$issuer/protocol/openid-connect/auth?" + form(params))
                page.locator("#username").fill(username)
                page.locator("#password").fill("$username-local-only")
                page.locator("#kc-login").click()
                page.waitForURL(Pattern.compile(Pattern.quote(callback) + "\\?.*"))
                val query =
                    URI(page.url()).rawQuery.split("&").associate {
                        val pair = it.split("=", limit = 2)
                        pair[0] to URLDecoder.decode(pair[1], Charsets.UTF_8)
                    }
                check(query["state"] == state) { "OIDC state mismatch" }
                requireNotNull(query["code"]) { "Authorization code missing" }
            }
        val exchange =
            mutableMapOf(
                "grant_type" to "authorization_code",
                "client_id" to client,
                "redirect_uri" to callback,
                "code" to code,
                "code_verifier" to verifier,
            )
        if (resource != null) exchange["resource"] = resource
        val response =
            HttpClient.newHttpClient().send(
                HttpRequest
                    .newBuilder(URI("$issuer/protocol/openid-connect/token"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form(exchange)))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        check(response.statusCode() == 200) { "Code exchange failed: HTTP ${response.statusCode()}" }
        return JsonMapper.builder().build().readTree(response.body())
    }

    private fun form(params: Map<String, String>) =
        params.entries.joinToString("&") { (key, value) -> "$key=${URLEncoder.encode(value, Charsets.UTF_8)}" }

    override fun close() = server.stop(0)
}
