package com.functionaldude.aso

import com.functionaldude.aso.jooq.tables.references.CONTENT_REQUEST
import com.functionaldude.aso.jooq.tables.references.QUESTION
import com.functionaldude.aso.oidc.KeycloakFixture
import com.functionaldude.aso.oidc.OidcBrowserLogin
import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Playwright
import org.jooq.DSLContext
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
class QuestionIntegrationTest {
    @Autowired private lateinit var database: DSLContext
    private val json = JsonMapper.builder().build()
    private val http = HttpClient.newHttpClient()

    private data class Actor(
        val cookie: String,
        val csrf: String,
        val token: String,
        val id: String,
    )

    private data class Result(
        val status: Int,
        val body: JsonNode,
    )

    private fun web(
        actor: Actor?,
        path: String,
        payload: Any? = null,
    ): Result {
        val request = HttpRequest.newBuilder(URI("$base$path"))
        if (actor != null) request.header("Cookie", actor.cookie)
        if (payload != null) {
            if (actor != null) request.header("X-CSRF-TOKEN", actor.csrf)
            request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload)))
        }
        val response = http.send(request.build(), HttpResponse.BodyHandlers.ofString())
        return Result(response.statusCode(), json.readTree(response.body()))
    }

    private fun mcp(
        actor: Actor,
        name: String,
        payload: Any,
    ): Result {
        val response =
            rpc(
                actor,
                mapOf(
                    "jsonrpc" to "2.0",
                    "id" to 1,
                    "method" to "tools/call",
                    "params" to mapOf("name" to name, "arguments" to payload),
                ),
            )
        val body = response.body
        if (response.status != 200) return response
        assertFalse(body.has("error"), body.toString())
        val result = body["result"]
        val content = result["structuredContent"]
        return Result(if (result["isError"].asBoolean()) content["status"].asInt() else 200, content)
    }

    private fun rpc(
        actor: Actor,
        payload: Any,
    ): Result = rawRpc(actor, json.writeValueAsString(payload))

    private fun rawRpc(
        actor: Actor,
        payload: String,
    ): Result {
        val response =
            http.send(
                HttpRequest
                    .newBuilder(URI("$base/mcp"))
                    .header("Authorization", "Bearer ${actor.token}")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        return Result(response.statusCode(), json.readTree(response.body().ifEmpty { "null" }))
    }

    private fun actor(
        browser: Browser,
        username: String,
        contexts: MutableList<BrowserContext>,
    ): Actor {
        val context = browser.newContext().also { contexts.add(it) }
        val page = context.newPage()
        page.navigate("$base/oauth2/authorization/oidc")
        page.locator("#username").fill(username)
        page.locator("#password").fill("$username-local-only")
        page.locator("#kc-login").click()
        page.waitForURL("$base/")
        val csrf = json.readTree(context.request().get("$base/api/v1/csrf").text())["token"].asString()
        val identity = json.readTree(context.request().get("$base/api/v1/identity").text())
        return Actor(
            context.cookies(base).joinToString("; ") {
                "${it.name}=${it.value}"
            },
            csrf,
            login.tokens(browser, issuer, username, resource = "$base/mcp")["access_token"].asString(),
            identity["id"].asString(),
        )
    }

    private fun withActors(work: (Actor, Actor) -> Unit) {
        Playwright.create().use { playwright ->
            playwright.chromium().launch(BrowserType.LaunchOptions().setHeadless(true)).use { browser ->
                val contexts = mutableListOf<BrowserContext>()
                try {
                    work(actor(browser, "alice", contexts), actor(browser, "bob", contexts))
                } finally {
                    contexts.forEach { it.close() }
                }
            }
        }
    }

    private fun question(
        request: UUID = UUID.randomUUID(),
        title: String = "Dependency resolution fails",
    ) = mapOf(
        "requestId" to request.toString(),
        "title" to title,
        "body" to "Reproduce with `org.example:library:1.2`.\n\n```text\nNo matching variant\n```",
        "tags" to listOf("Kotlin", "gradle"),
        "context" to
            mapOf(
                "errorText" to "No matching variant",
                "dependencies" to "org.example:library:1.2",
                "versions" to "Java 25",
                "repository" to "example/core",
            ),
    )

    private fun answer(request: UUID = UUID.randomUUID()) =
        mapOf(
            "requestId" to request.toString(),
            "body" to "Align the target JVM version.",
            "applicability" to "Java 25 consumers",
            "verification" to "Verified with the isolated fixture",
        )

    @Test
    fun `both adapters share records identity validation errors tags context and pagination`() =
        withActors { alice, bob ->
            val created = mcp(alice, "create_question", question())
            assertEquals(200, created.status)
            val id = created.body["id"].asString()
            assertEquals(alice.id, created.body["author"]["id"].asString())
            assertEquals("mcp", created.body["channel"].asString())
            assertEquals("Java 25", created.body["context"]["versions"].asString())
            assertEquals(
                listOf("gradle", "kotlin"),
                created.body["tags"]
                    .asSequence()
                    .map { it.asString() }
                    .toList(),
            )
            val detail = web(bob, "/api/v1/questions/$id")
            assertEquals(200, detail.status)
            assertEquals(created.body, detail.body["question"])
            val response = web(bob, "/api/v1/questions/$id/answers", answer())
            assertEquals(200, response.status)
            assertEquals(bob.id, response.body["author"]["id"].asString())
            assertEquals("web", response.body["channel"].asString())
            val reverse = web(bob, "/api/v1/questions", question())
            assertEquals(200, reverse.status)
            val otherId = reverse.body["id"].asString()
            val agentAnswer = mcp(alice, "create_answer", answer() + ("questionId" to otherId))
            assertEquals(200, agentAnswer.status)
            assertEquals(alice.id, agentAnswer.body["author"]["id"].asString())
            assertEquals(bob.id, mcp(alice, "get_question", mapOf("questionId" to otherId)).body["question"]["author"]["id"].asString())
            assertEquals(response.body, mcp(alice, "get_question", mapOf("questionId" to id)).body["answers"]["items"][0])
            val missing = UUID.randomUUID().toString()
            assertEquals(404, web(alice, "/api/v1/questions/$missing").status)
            assertEquals(404, mcp(bob, "get_question", mapOf("questionId" to missing)).status)
            assertEquals(404, mcp(bob, "create_answer", answer() + ("questionId" to missing)).status)
            assertEquals(401, web(null, "/api/v1/questions").status)
            for (payload in listOf(
                question() + ("authorId" to bob.id),
                question() + ("body" to "api_key=private-test-value"),
                question() + ("title" to "x".repeat(201)),
                question() + ("context" to mapOf("role" to "admin")),
                question() + ("tags" to listOf("bad tag")),
                question() - "requestId",
                question() - "title",
                question() - "body",
                question() + ("title" to null),
                question() + ("title" to 123),
                question() + ("body" to false),
                question() + ("tags" to null),
                question() + ("tags" to "kotlin"),
                question() + ("tags" to listOf(1)),
                question() + ("tags" to listOf(null)),
                question() + ("context" to mapOf("versions" to 25)),
                question() + ("context" to "invalid"),
            )) {
                val restError = web(alice, "/api/v1/questions", payload)
                val toolError = mcp(alice, "create_question", payload)
                assertEquals(400, restError.status, payload.toString())
                assertEquals(400, toolError.status, payload.toString())
                assertEquals("invalid_input", restError.body.path("code").asString(), restError.body.toString())
                assertEquals(restError.body["detail"].asString(), toolError.body["message"].asString())
                assertFalse(restError.body.toString().contains("private-test-value"))
            }
            assertEquals(400, web(bob, "/api/v1/questions/$id/answers", answer() + ("authorId" to alice.id)).status)
            assertEquals(400, mcp(bob, "create_answer", answer() + mapOf("questionId" to id, "authorId" to alice.id)).status)
            for (limit in listOf(1.5, 1.0, "20", true, null, 2147483648L)) {
                assertEquals(400, mcp(alice, "list_questions", mapOf("limit" to limit)).status)
                assertEquals(400, mcp(alice, "list_answers", mapOf("questionId" to id, "limit" to limit)).status)
            }
            for (payload in listOf(answer() - "body", answer() + ("body" to 4), answer() + ("verification" to false))) {
                val restError = web(bob, "/api/v1/questions/$id/answers", payload)
                val toolError = mcp(bob, "create_answer", payload + ("questionId" to id))
                assertEquals(400, restError.status)
                assertEquals(400, toolError.status)
                assertEquals(restError.body["detail"].asString(), toolError.body["message"].asString())
            }
            assertEquals(400, web(alice, "/api/v1/questions?limit=51").status)
            assertEquals(400, web(alice, "/api/v1/questions?cursor=garbage").status)
            assertEquals(413, web(alice, "/api/v1/questions", question() + ("body" to "x".repeat(262145))).status)
            assertEquals(0, web(alice, "/api/v1/questions?state=resolved").body["items"].size())
            repeat(4) { assertEquals(200, web(alice, "/api/v1/questions", question(title = "Page $it")).status) }
            database.update(QUESTION).set(QUESTION.CREATED_AT, java.time.OffsetDateTime.parse("2026-10-16T00:00:00Z")).execute()
            val first = web(bob, "/api/v1/questions?tag=kotlin&limit=2").body
            assertEquals(2, first["items"].size())
            val newer = web(alice, "/api/v1/questions", question(title = "Inserted between pages")).body["id"].asString()
            val ids = first["items"].asSequence().map { it["id"].asString() }.toMutableList()
            var cursor = first["nextCursor"]
            while (!cursor.isNull) {
                val page = web(bob, "/api/v1/questions?tag=kotlin&limit=2&cursor=${cursor.asString()}")
                assertEquals(200, page.status)
                ids.addAll(
                    page.body["items"]
                        .asSequence()
                        .map { it["id"].asString() }
                        .toList(),
                )
                cursor = page.body["nextCursor"]
            }
            assertEquals(ids.size, ids.toSet().size)
            assertFalse(newer in ids)
            assertTrue(id in ids && otherId in ids)
            assertEquals(400, web(alice, "/api/v1/questions?tag=other&cursor=${first["nextCursor"].asString()}").status)
            repeat(2) { web(alice, "/api/v1/questions/$id/answers", answer()) }
            val answers = web(bob, "/api/v1/questions/$id/answers?limit=1").body
            val next = mcp(alice, "list_answers", mapOf("questionId" to id, "limit" to 1, "cursor" to answers["nextCursor"].asString()))
            assertEquals(200, next.status)
            assertNotEquals(answers["items"][0]["id"], next.body["items"][0]["id"])
        }

    @Test
    fun `typed MCP envelopes preserve discovery results notifications and protocol errors`() =
        withActors { alice, _ ->
            fun request(
                method: String,
                id: Any = "request-1",
                params: Any = emptyMap<String, Any>(),
            ) = mapOf("jsonrpc" to "2.0", "id" to id, "method" to method, "params" to params)
            for (id in listOf(7, 1.5, "request-1")) {
                val ping = rpc(alice, request("ping", id))
                assertEquals(200, ping.status)
                assertEquals(json.readTree("{}"), ping.body["result"])
                assertEquals(json.valueToTree<JsonNode>(id), ping.body["id"])
                assertEquals("2.0", ping.body["jsonrpc"].asString())
            }
            val malformed = rawRpc(alice, "{invalid")
            assertEquals(400, malformed.status)
            assertFalse(malformed.body.has("code"))
            assertFalse(malformed.body.has("result"))
            val metadata = web(null, "/.well-known/oauth-protected-resource/mcp").body
            assertEquals(
                setOf("resource", "authorization_servers", "scopes_supported", "bearer_methods_supported"),
                metadata.propertyNames().toSet(),
            )
            assertEquals("$base/mcp", metadata["resource"].asString())
            val initialized = rpc(alice, request("initialize")).body["result"]
            assertEquals("2025-11-25", initialized["protocolVersion"].asString())
            assertFalse(initialized["capabilities"]["tools"]["listChanged"].asBoolean())
            val tools = rpc(alice, request("tools/list")).body["result"]["tools"]
            assertEquals(6, tools.size())
            val identity = tools.first { it["name"].asString() == "identity" }
            assertEquals(setOf("readOnlyHint", "openWorldHint"), identity["annotations"].propertyNames().toSet())
            assertEquals(0, identity["inputSchema"]["properties"].size())
            val questionTool = tools.first { it["name"].asString() == "create_question" }
            assertFalse(questionTool["inputSchema"]["additionalProperties"].asBoolean())
            assertTrue(questionTool["annotations"]["idempotentHint"].asBoolean())
            assertEquals(-32601, rpc(alice, request("unknown")).body["error"]["code"].asInt())
            for (payload in listOf(
                request("ping", true),
                request("ping") - "id",
                request("ping") + ("jsonrpc" to 2.0),
                request("ping") + ("method" to false),
                emptyList<String>(),
            )) {
                val error = rpc(alice, payload)
                assertEquals(200, error.status)
                assertEquals(-32600, error.body["error"]["code"].asInt())
            }
            for (params in listOf(
                mapOf("name" to "unknown"),
                mapOf("name" to 7),
                mapOf("name" to "identity", "arguments" to null),
                mapOf("name" to "identity", "arguments" to listOf("invalid")),
                mapOf("name" to "identity", "arguments" to mapOf("authorId" to "other")),
                listOf("invalid"),
            )) {
                assertEquals(-32602, rpc(alice, request("tools/call", params = params)).body["error"]["code"].asInt())
            }
            val noArguments = rpc(alice, request("tools/call", params = mapOf("name" to "identity")))
            assertEquals(alice.id, noArguments.body["result"]["structuredContent"]["id"].asString())
            for (method in listOf("notifications/initialized", "notifications/cancelled")) {
                assertEquals(202, rpc(alice, request(method) - "id").status)
            }
        }

    @Test
    fun `typed inputs retain legacy hashes normalized cross adapter retries and optional defaults`() =
        withActors { alice, bob ->
            val requestId = UUID.randomUUID()
            val payload =
                mapOf(
                    "requestId" to requestId.toString(),
                    "title" to "  Trimmed title  ",
                    "body" to "  Reproduce the failure.  ",
                    "tags" to listOf("Kotlin", "c++", "kotlin"),
                    "context" to mapOf("errorText" to "  Diagnostic  ", "versions" to "  ", "repository" to null),
                )
            val created = web(alice, "/api/v1/questions", payload)
            assertEquals(200, created.status)
            val id = created.body["id"].asString()
            // Literal pre-cleanup serialization: ordered normalized list, then TechnicalContext fields.
            val legacyQuestion =
                """["Trimmed title","Reproduce the failure.",["c++","kotlin"],""" +
                    """{"errorText":"Diagnostic","dependencies":null,"versions":null,"repository":null}]"""
            val expected = MessageDigest.getInstance("SHA-256").digest(legacyQuestion.toByteArray()).joinToString("") { "%02x".format(it) }
            assertEquals(
                expected,
                database
                    .select(CONTENT_REQUEST.PAYLOAD_HASH)
                    .from(CONTENT_REQUEST)
                    .where(CONTENT_REQUEST.REQUEST_ID.eq(requestId))
                    .fetchSingle(CONTENT_REQUEST.PAYLOAD_HASH),
            )
            assertEquals(
                created.body,
                mcp(
                    alice,
                    "create_question",
                    payload +
                        mapOf(
                            "title" to "Trimmed title",
                            "body" to "Reproduce the failure.",
                            "tags" to listOf("c++", "kotlin"),
                            "context" to mapOf("errorText" to "Diagnostic"),
                        ),
                ).body,
            )
            val answerRequest = UUID.randomUUID()
            val answerPayload = mapOf("requestId" to answerRequest.toString(), "body" to "  Fix it.  ", "verification" to " ")
            val posted = mcp(bob, "create_answer", answerPayload + ("questionId" to id))
            assertEquals(200, posted.status)
            val legacyAnswer = """["$id","Fix it.",null,null]"""
            val answerHash = MessageDigest.getInstance("SHA-256").digest(legacyAnswer.toByteArray()).joinToString("") { "%02x".format(it) }
            assertEquals(
                answerHash,
                database
                    .select(CONTENT_REQUEST.PAYLOAD_HASH)
                    .from(CONTENT_REQUEST)
                    .where(CONTENT_REQUEST.REQUEST_ID.eq(answerRequest))
                    .fetchSingle(CONTENT_REQUEST.PAYLOAD_HASH),
            )
            assertEquals(posted.body, web(bob, "/api/v1/questions/$id/answers", answerPayload - "verification").body)
            val minimal = question() - "tags" - "context"
            val defaults = mcp(alice, "create_question", minimal)
            assertEquals(200, defaults.status)
            assertEquals(0, defaults.body["tags"].size())
            assertTrue(defaults.body["context"].properties().all { it.value.isNull })
            assertEquals(defaults.body, web(alice, "/api/v1/questions", minimal + ("context" to null)).body)
        }

    @Test
    fun `concurrent cross adapter creates replay once conflict explicitly and scope IDs to actor and operation`() =
        withActors { alice, bob ->
            Executors.newFixedThreadPool(8).use { executor ->
                val key = UUID.randomUUID()
                val payload = question(key)
                val results =
                    executor
                        .invokeAll(
                            (1..12).map { index ->
                                Callable {
                                    if (index % 2 == 0) {
                                        web(alice, "/api/v1/questions", payload)
                                    } else {
                                        mcp(alice, "create_question", payload)
                                    }
                                }
                            },
                        ).map { it.get() }
                assertTrue(results.all { it.status == 200 })
                val ids = results.map { it.body["id"].asString() }.toSet()
                assertEquals(1, ids.size)
                assertEquals(1, results.map { it.body.toString() }.toSet().size)
                val id = ids.single()
                assertNotEquals(id, mcp(bob, "create_question", payload).body["id"].asString())
                assertEquals(409, web(alice, "/api/v1/questions", payload + ("title" to "Changed")).status)
                assertEquals(409, mcp(alice, "create_question", payload + ("title" to "Changed")).status)
                val replies =
                    executor
                        .invokeAll(
                            (1..12).map { index ->
                                Callable {
                                    if (index % 2 == 0) {
                                        web(alice, "/api/v1/questions/$id/answers", answer(key))
                                    } else {
                                        mcp(
                                            alice,
                                            "create_answer",
                                            answer(key) + ("questionId" to id),
                                        )
                                    }
                                }
                            },
                        ).map { it.get() }
                assertTrue(replies.all { it.status == 200 })
                assertEquals(1, replies.map { it.body["id"].asString() }.toSet().size)
                assertEquals(1, web(bob, "/api/v1/questions/$id").body["answers"]["items"].size())
                assertEquals(409, mcp(alice, "create_answer", answer(key) + mapOf("questionId" to id, "body" to "Changed answer")).status)
                val another = web(alice, "/api/v1/questions", question()).body["id"].asString()
                assertEquals(409, mcp(alice, "create_answer", answer(key) + ("questionId" to another)).status)
                assertEquals(409, mcp(alice, "create_answer", answer(key) + ("questionId" to UUID.randomUUID().toString())).status)
                val failedRequest = UUID.randomUUID()
                assertEquals(404, web(alice, "/api/v1/questions/${UUID.randomUUID()}/answers", answer(failedRequest)).status)
                assertEquals(200, web(alice, "/api/v1/questions/$id/answers", answer(failedRequest)).status)
                val raceKey = UUID.randomUUID()
                val race =
                    executor
                        .invokeAll(
                            (1..8).map { index ->
                                Callable {
                                    mcp(
                                        bob,
                                        "create_question",
                                        question(
                                            raceKey,
                                            if (index % 2 == 0) {
                                                "One"
                                            } else {
                                                "Two"
                                            },
                                        ),
                                    )
                                }
                            },
                        ).map { it.get() }
                assertEquals(setOf(200, 409), race.map { it.status }.toSet())
                assertEquals(
                    1,
                    race
                        .filter { it.status == 200 }
                        .map { it.body["id"].asString() }
                        .toSet()
                        .size,
                )
            }
        }

    companion object {
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
            ).also {
                it.start()
            }

        @JvmStatic @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("server.port") { port }
            registry.add("spring.datasource.url", db::getJdbcUrl)
            registry.add("spring.datasource.username", db::getUsername)
            registry.add("spring.datasource.password", db::getPassword)
            mapOf(
                "issuer" to issuer,
                "web-client-id" to "aso-web",
                "web-client-secret" to "aso-web-secret-local-only",
                "public-url" to base,
                "mcp-audience" to "$base/mcp",
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
