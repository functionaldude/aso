package com.functionaldude.aso

import com.functionaldude.aso.jooq.tables.references.CONTENT_REQUEST
import com.functionaldude.aso.jooq.tables.references.QUESTION
import com.functionaldude.aso.oidc.KeycloakFixture
import com.functionaldude.aso.oidc.OidcBrowserLogin
import com.functionaldude.aso.search.EmbeddingClient
import com.functionaldude.aso.search.EmbeddingFixture
import com.functionaldude.aso.search.EmbeddingSettings
import com.functionaldude.aso.search.SearchIndex
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
@org.springframework.context.annotation.Import(QuestionIntegrationTest.ClockFixture::class)
class QuestionIntegrationTest {
    @Autowired private lateinit var database: DSLContext

    @Autowired private lateinit var lifecycle: com.functionaldude.aso.questions.LifecycleService

    @Autowired private lateinit var decoder: org.springframework.security.oauth2.jwt.JwtDecoder

    @Autowired private lateinit var searchIndex: SearchIndex

    @Autowired private lateinit var transactions: org.springframework.transaction.support.TransactionTemplate

    @Autowired private lateinit var embeddingSettings: EmbeddingSettings

    @Autowired private lateinit var embeddingClient: EmbeddingClient
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
            assertTrue(web(alice, "/api/v1/questions?state=resolved").body["items"].none { it["id"].asString() == id })
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
            assertEquals(15, tools.size())
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

    private fun withLifecycleActors(work: (Actor, Actor, Actor) -> Unit) {
        Playwright.create().use { playwright ->
            playwright.chromium().launch(BrowserType.LaunchOptions().setHeadless(true)).use { browser ->
                val contexts = mutableListOf<BrowserContext>()
                try {
                    work(actor(browser, "alice", contexts), actor(browser, "bob", contexts), actor(browser, "aso-admin", contexts))
                } finally {
                    contexts.forEach { it.close() }
                }
            }
        }
    }

    @Test
    fun `permissions retries conflicts acceptance and hidden descendants`() =
        withLifecycleActors { alice, bob, admin ->
            val q = mcp(alice, "create_question", question()).body
            val qid = q["id"].asString()
            val a = web(bob, "/api/v1/questions/$qid/answers", answer()).body
            val aid = a["id"].asString()
            val other = web(bob, "/api/v1/questions", question()).body["id"].asString()
            val unrelated = web(bob, "/api/v1/questions/$other/answers", answer()).body["id"].asString()
            val commentPayload =
                mapOf(
                    "requestId" to UUID.randomUUID().toString(),
                    "body" to "More **context**.\n\n<script>unsafe()</script>",
                )
            val c = mcp(alice, "create_comment", commentPayload + mapOf("kind" to "answer", "targetId" to aid))
            assertEquals(200, c.status)
            val cid = c.body["id"].asString()
            assertEquals(alice.id, c.body["author"]["id"].asString())
            assertEquals("mcp", c.body["channel"].asString())
            assertTrue(c.body["bodyHtml"].asString().contains("&lt;script&gt;"))
            assertEquals(c.body, web(alice, "/api/v1/content/answer/$aid/comments", commentPayload).body)
            assertEquals(409, web(alice, "/api/v1/content/answer/$aid/comments", commentPayload + ("body" to "Changed")).status)
            assertEquals(409, web(alice, "/api/v1/content/question/$qid/comments", commentPayload).status)
            for (payload in listOf(
                commentPayload + ("authorId" to bob.id),
                commentPayload + ("body" to 4),
                commentPayload + ("body" to "password=private-fixture"),
            )) {
                assertEquals(400, web(alice, "/api/v1/content/question/$qid/comments", payload).status)
                assertEquals(400, mcp(alice, "create_comment", payload + mapOf("kind" to "question", "targetId" to qid)).status)
            }
            repeat(3) {
                web(
                    bob,
                    "/api/v1/content/question/$qid/comments",
                    mapOf(
                        "requestId" to UUID.randomUUID().toString(),
                        "body" to "Context $it",
                    ),
                )
            }
            val first = web(alice, "/api/v1/content/question/$qid/comments?limit=2").body
            val second =
                mcp(
                    bob,
                    "list_comments",
                    mapOf(
                        "kind" to "question",
                        "targetId" to qid,
                        "limit" to 2,
                        "cursor" to first["nextCursor"].asString(),
                    ),
                )
            assertEquals(1, second.body["items"].size())
            assertEquals("Context 0", first["items"][0]["body"].asString())
            assertEquals("Context 2", second.body["items"][0]["body"].asString())
            assertEquals(400, web(alice, "/api/v1/content/answer/$aid/comments?cursor=${first["nextCursor"].asString()}").status)
            val editQ =
                mapOf(
                    "expectedVersion" to 1,
                    "title" to "Edited problem",
                    "body" to "Focused edited problem",
                    "tags" to listOf("edited"),
                    "context" to mapOf("versions" to "Java 25"),
                )
            val editA = mapOf("expectedVersion" to 1, "body" to "Edited fix", "verification" to "Not tested")
            val editC = mapOf("expectedVersion" to 1, "body" to "Edited context")
            for ((who, path, payload) in listOf(
                Triple(
                    bob,
                    "/questions/$qid/edit",
                    editQ,
                ),
                Triple(
                    alice,
                    "/answers/$aid/edit",
                    editA,
                ),
                Triple(bob, "/comments/$cid/edit", editC),
            )) {
                assertEquals(403, web(who, "/api/v1$path", payload).status)
            }
            assertEquals(403, mcp(bob, "edit_question", mapOf("questionId" to qid, "content" to editQ)).status)
            assertEquals(403, mcp(alice, "edit_answer", mapOf("answerId" to aid, "content" to editA)).status)
            assertEquals(403, mcp(bob, "edit_comment", mapOf("commentId" to cid, "content" to editC)).status)
            assertEquals(403, mcp(admin, "edit_question", mapOf("questionId" to qid, "content" to editQ)).status)
            val updated = mcp(alice, "edit_question", mapOf("questionId" to qid, "content" to editQ))
            assertEquals(200, updated.status)
            assertEquals(2, updated.body["version"].asInt())
            assertEquals(q["createdAt"], updated.body["createdAt"])
            assertEquals(q["author"], updated.body["author"])
            assertEquals(409, web(alice, "/api/v1/questions/$qid/edit", editQ).status)
            assertEquals(200, web(alice, "/api/v1/questions/$qid/edit", editQ + ("expectedVersion" to 2)).status)
            assertEquals(200, mcp(bob, "edit_answer", mapOf("answerId" to aid, "content" to editA)).status)
            assertEquals(409, web(bob, "/api/v1/answers/$aid/edit", editA).status)
            assertEquals(200, mcp(alice, "edit_comment", mapOf("commentId" to cid, "content" to editC)).status)
            assertEquals(409, mcp(alice, "edit_comment", mapOf("commentId" to cid, "content" to editC)).status)
            for (badVersion in listOf(null, "3", 3.5, 0)) {
                assertEquals(400, web(alice, "/api/v1/questions/$qid/edit", editQ + ("expectedVersion" to badVersion)).status)
            }
            assertEquals(400, web(alice, "/api/v1/questions/$qid/edit", editQ + mapOf("expectedVersion" to 3, "authorId" to bob.id)).status)
            assertEquals(403, web(bob, "/api/v1/questions/$qid/acceptance", mapOf("expectedVersion" to 3, "answerId" to aid)).status)
            assertEquals(
                400,
                web(alice, "/api/v1/questions/$qid/acceptance", mapOf("expectedVersion" to 3, "answerId" to unrelated)).status,
            )
            val accepted = web(alice, "/api/v1/questions/$qid/acceptance", mapOf("expectedVersion" to 3, "answerId" to aid))
            assertEquals(200, accepted.status)
            assertEquals("resolved", accepted.body["state"].asString())
            assertEquals(aid, mcp(bob, "get_question", mapOf("questionId" to qid)).body["question"]["acceptedAnswerId"].asString())
            assertTrue(web(bob, "/api/v1/questions?state=resolved").body["items"].any { it["id"].asString() == qid })
            assertFalse(web(bob, "/api/v1/questions?state=open").body["items"].any { it["id"].asString() == qid })
            assertEquals(409, web(admin, "/api/v1/questions/$qid/acceptance", mapOf("expectedVersion" to 3, "answerId" to null)).status)
            val a2 = web(alice, "/api/v1/questions/$qid/answers", answer()).body["id"].asString()
            val changed = web(admin, "/api/v1/questions/$qid/acceptance", mapOf("expectedVersion" to 4, "answerId" to a2))
            assertEquals(a2, changed.body["acceptedAnswerId"].asString())
            assertEquals(
                "open",
                web(
                    alice,
                    "/api/v1/questions/$qid/acceptance",
                    mapOf("expectedVersion" to 5, "answerId" to null),
                ).body["state"].asString(),
            )
            assertEquals(200, web(admin, "/api/v1/questions/$qid/acceptance", mapOf("expectedVersion" to 6, "answerId" to aid)).status)
            // Actual browser credentials without CSRF, and signed member/admin bearer tokens with CSRF, cannot mutate browser actions.
            val csrfLess =
                HttpRequest
                    .newBuilder(URI("$base/api/v1/questions/$qid/acceptance"))
                    .header("Cookie", alice.cookie)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"expectedVersion\":7}"))
                    .build()
            assertEquals(403, http.send(csrfLess, HttpResponse.BodyHandlers.ofString()).statusCode())
            for (who in listOf(alice, admin)) {
                for (path in listOf("/questions/$qid/acceptance", "/admin/content/answer/$aid/visibility", "/admin/questions/$qid/edit")) {
                    val attempt =
                        HttpRequest
                            .newBuilder(URI("$base/api/v1$path"))
                            .header("Authorization", "Bearer ${who.token}")
                            .header("X-CSRF-TOKEN", who.csrf)
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("{\"expectedVersion\":7}"))
                            .build()
                    assertTrue(http.send(attempt, HttpResponse.BodyHandlers.ofString()).statusCode() in setOf(401, 403))
                }
            }
            // Even bypassing HTTP dispatch cannot promote a validated admin bearer to browser authority.
            val adminBearer =
                org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken(
                    decoder.decode(admin.token),
                )
            kotlin.test.assertFailsWith<org.springframework.security.access.AccessDeniedException> {
                lifecycle.accept(
                    adminBearer,
                    qid,
                    com.functionaldude.aso.questions
                        .AcceptanceInput(7, aid),
                )
            }
            kotlin.test.assertFailsWith<org.springframework.security.access.AccessDeniedException> {
                lifecycle.visibility(
                    adminBearer,
                    "answer",
                    aid,
                    com.functionaldude.aso.questions
                        .VisibilityInput(2, true),
                )
            }
            kotlin.test.assertFailsWith<org.springframework.security.access.AccessDeniedException> {
                lifecycle.editComment(
                    adminBearer,
                    cid,
                    com.functionaldude.aso.questions
                        .EditCommentInput(2, "Forbidden"),
                    moderation = true,
                )
            }
            for (path in listOf("/admin/questions", "/admin/questions/$qid", "/admin/content/comment/$cid")) {
                val attempt = HttpRequest.newBuilder(URI("$base/api/v1$path")).header("Authorization", "Bearer ${admin.token}").build()
                assertEquals(401, http.send(attempt, HttpResponse.BodyHandlers.ofString()).statusCode())
            }
            val tools = rpc(admin, mapOf("jsonrpc" to "2.0", "id" to 1, "method" to "tools/list")).body["result"]["tools"]
            assertEquals(
                setOf(
                    "identity",
                    "search_questions",
                    "list_questions",
                    "get_question",
                    "list_answers",
                    "create_question",
                    "create_answer",
                    "list_comments",
                    "create_comment",
                    "edit_question",
                    "edit_answer",
                    "edit_comment",
                    "set_vote",
                    "list_votes",
                    "report_occurrence",
                ),
                tools
                    .asSequence()
                    .map {
                        it["name"].asString()
                    }.toSet(),
            )
            for (tool in listOf("accept_answer", "set_acceptance", "hide_content", "restore_content", "moderate_question")) {
                assertEquals(
                    -32602,
                    rpc(
                        admin,
                        mapOf(
                            "jsonrpc" to "2.0",
                            "id" to 1,
                            "method" to "tools/call",
                            "params" to
                                mapOf(
                                    "name" to tool,
                                    "arguments" to emptyMap<String, Any>(),
                                ),
                        ),
                    ).body["error"]["code"].asInt(),
                )
            }
            assertEquals(403, web(alice, "/api/v1/admin/questions").status)
            assertEquals(
                403,
                web(bob, "/api/v1/admin/content/answer/$aid/visibility", mapOf("expectedVersion" to 2, "hidden" to true)).status,
            )
            assertEquals(200, web(admin, "/api/v1/admin/answers/$aid/edit", editA + ("expectedVersion" to 2)).status)
            assertEquals(200, web(admin, "/api/v1/admin/comments/$cid/edit", editC + ("expectedVersion" to 2)).status)
            val hide = web(admin, "/api/v1/admin/content/answer/$aid/visibility", mapOf("expectedVersion" to 3, "hidden" to true))
            assertEquals(200, hide.status)
            assertEquals("open", web(alice, "/api/v1/questions/$qid").body["question"]["state"].asString())
            for (path in listOf(
                "/content/answer/$aid",
                "/content/comment/$cid",
                "/content/answer/$aid/comments",
            )) {
                assertEquals(
                    404,
                    web(alice, "/api/v1$path").status,
                )
            }
            assertEquals(404, mcp(alice, "list_comments", mapOf("kind" to "answer", "targetId" to aid)).status)
            assertEquals(404, web(alice, "/api/v1/questions/$qid/acceptance", mapOf("expectedVersion" to 8, "answerId" to aid)).status)
            assertFalse(mcp(alice, "get_question", mapOf("questionId" to qid)).body["answers"]["items"].any { it["id"].asString() == aid })
            assertEquals(200, web(admin, "/api/v1/admin/content/answer/$aid/comments").status)
            assertEquals(
                200,
                web(
                    admin,
                    "/api/v1/admin/content/answer/$aid/visibility",
                    mapOf("expectedVersion" to 4, "hidden" to false),
                ).status,
            )
            assertTrue(web(alice, "/api/v1/questions/$qid").body["question"]["acceptedAnswerId"].isNull)
            assertEquals(
                200,
                web(
                    admin,
                    "/api/v1/admin/content/comment/$cid/visibility",
                    mapOf("expectedVersion" to 3, "hidden" to true),
                ).status,
            )
            assertEquals(0, mcp(alice, "list_comments", mapOf("kind" to "answer", "targetId" to aid)).body["items"].size())
            assertEquals(
                200,
                web(
                    admin,
                    "/api/v1/admin/content/comment/$cid/visibility",
                    mapOf("expectedVersion" to 4, "hidden" to false),
                ).status,
            )
            assertEquals(
                200,
                web(
                    admin,
                    "/api/v1/admin/content/question/$qid/visibility",
                    mapOf("expectedVersion" to 8, "hidden" to true),
                ).status,
            )
            assertEquals(404, web(admin, "/api/v1/questions/$qid").status)
            assertEquals(404, mcp(alice, "get_question", mapOf("questionId" to qid)).status)
            assertEquals(404, mcp(bob, "list_answers", mapOf("questionId" to qid)).status)
            assertEquals(404, web(alice, "/api/v1/content/comment/$cid").status)
            assertEquals(404, web(bob, "/api/v1/questions/$qid/answers", answer()).status)
            assertEquals(
                404,
                web(
                    bob,
                    "/api/v1/content/answer/$aid/comments",
                    mapOf(
                        "requestId" to UUID.randomUUID().toString(),
                        "body" to "Hidden parent",
                    ),
                ).status,
            )
            assertFalse(web(alice, "/api/v1/questions?state=all").body["items"].any { it["id"].asString() == qid })
            assertEquals(200, web(admin, "/api/v1/admin/questions/$qid").status)
            assertEquals(200, web(admin, "/api/v1/admin/questions/$qid/edit", editQ + ("expectedVersion" to 9)).status)
            assertEquals(
                200,
                web(
                    admin,
                    "/api/v1/admin/content/question/$qid/visibility",
                    mapOf("expectedVersion" to 10, "hidden" to false),
                ).status,
            )
            assertEquals(200, web(alice, "/api/v1/content/comment/$cid").status)
            val audits =
                database
                    .selectFrom(com.functionaldude.aso.jooq.tables.references.CONTENT_AUDIT)
                    .where(
                        com.functionaldude.aso.jooq.tables.references.CONTENT_AUDIT.QUESTION_ID
                            .eq(UUID.fromString(qid)),
                    ).fetch()
            assertTrue(audits.size >= 15)
            assertTrue(
                audits.all { it.actorId != null && it.occurredAt != null && it.targetId != null && it.channel in setOf("web", "mcp") },
            )
            assertTrue(
                audits.any {
                    it.action == "accept" &&
                        json.readTree(it.beforeState!!)["acceptedAnswerId"].asString() == aid &&
                        json.readTree(it.afterState!!)["acceptedAnswerId"].isNull
                },
            )
            assertTrue(
                audits.any {
                    it.action == "edit" &&
                        json.readTree(it.beforeState!!)["contentHash"] != json.readTree(it.afterState!!)["contentHash"]
                },
            )
            assertFalse(audits.any { it.beforeState!!.contains("Focused edited problem") || it.afterState!!.contains("Bearer") })
        }

    @Test
    fun `concurrent lifecycle changes preserve state and audits`() =
        withLifecycleActors { alice, bob, admin ->
            val q = web(alice, "/api/v1/questions", question()).body["id"].asString()
            val a = web(bob, "/api/v1/questions/$q/answers", answer()).body["id"].asString()
            Executors.newFixedThreadPool(8).use { pool ->
                val edits =
                    pool
                        .invokeAll(
                            (1..8).map { n ->
                                Callable {
                                    web(
                                        alice,
                                        "/api/v1/questions/$q/edit",
                                        mapOf(
                                            "expectedVersion" to 1,
                                            "title" to "Concurrent $n",
                                            "body" to "Draft $n",
                                        ),
                                    )
                                }
                            },
                        ).map { it.get() }
                assertEquals(1, edits.count { it.status == 200 })
                assertEquals(7, edits.count { it.status == 409 })
                val payload = mapOf("requestId" to UUID.randomUUID().toString(), "body" to "Same encounter context")
                val comments =
                    pool
                        .invokeAll(
                            (1..8).map { n ->
                                Callable {
                                    if (n % 2 == 0) {
                                        web(
                                            bob,
                                            "/api/v1/content/question/$q/comments",
                                            payload,
                                        )
                                    } else {
                                        mcp(
                                            bob,
                                            "create_comment",
                                            payload +
                                                mapOf(
                                                    "kind" to "question",
                                                    "targetId" to q,
                                                ),
                                        )
                                    }
                                }
                            },
                        ).map { it.get() }
                assertTrue(comments.all { it.status == 200 })
                assertEquals(1, comments.map { it.body["id"].asString() }.toSet().size)
                val cid = comments.first().body["id"].asString()
                val editsA =
                    pool
                        .invokeAll(
                            (1..4).map { n ->
                                Callable {
                                    mcp(
                                        bob,
                                        "edit_answer",
                                        mapOf(
                                            "answerId" to a,
                                            "content" to mapOf("expectedVersion" to 1, "body" to "Answer draft $n"),
                                        ),
                                    )
                                }
                            },
                        ).map {
                            it.get()
                        }
                assertEquals(1, editsA.count { it.status == 200 })
                assertEquals(3, editsA.count { it.status == 409 })
                val editsC =
                    pool
                        .invokeAll(
                            (1..4).map { n ->
                                Callable {
                                    web(
                                        bob,
                                        "/api/v1/comments/$cid/edit",
                                        mapOf("expectedVersion" to 1, "body" to "Comment draft $n"),
                                    )
                                }
                            },
                        ).map {
                            it.get()
                        }
                assertEquals(1, editsC.count { it.status == 200 })
                assertEquals(3, editsC.count { it.status == 409 })
                val race =
                    pool
                        .invokeAll(
                            listOf(
                                Callable { web(alice, "/api/v1/questions/$q/acceptance", mapOf("expectedVersion" to 2, "answerId" to a)) },
                                Callable {
                                    web(
                                        admin,
                                        "/api/v1/admin/content/answer/$a/visibility",
                                        mapOf(
                                            "expectedVersion" to 2,
                                            "hidden" to true,
                                        ),
                                    )
                                },
                            ),
                        ).map { it.get() }
                assertEquals(200, race[1].status)
                assertTrue(race[0].status in setOf(200, 404))
                val current = web(alice, "/api/v1/questions/$q").body
                assertEquals("open", current["question"]["state"].asString())
                assertTrue(current["question"]["acceptedAnswerId"].isNull)
                assertEquals(0, current["answers"]["items"].size())
                val version = current["question"]["version"].asInt()
                val moderationRace =
                    pool
                        .invokeAll(
                            listOf(
                                Callable {
                                    web(
                                        admin,
                                        "/api/v1/admin/content/question/$q/visibility",
                                        mapOf("expectedVersion" to version, "hidden" to true),
                                    )
                                },
                                Callable {
                                    web(
                                        alice,
                                        "/api/v1/questions/$q/edit",
                                        mapOf(
                                            "expectedVersion" to version,
                                            "title" to "Race draft",
                                            "body" to "Race body",
                                        ),
                                    )
                                },
                            ),
                        ).map { it.get() }
                assertEquals(1, moderationRace.count { it.status == 200 })
                assertTrue(moderationRace.all { it.status in setOf(200, 404, 409) })
            }
            val audit = com.functionaldude.aso.jooq.tables.references.CONTENT_AUDIT
            val committedEdits =
                database
                    .selectFrom(audit)
                    .where(audit.QUESTION_ID.eq(UUID.fromString(q)))
                    .and(audit.ACTION.eq("edit"))
                    .fetch()
            assertTrue(committedEdits.size in 3..4)
            assertTrue(
                committedEdits.all {
                    val beforeVersion = json.readTree(it.beforeState!!)["version"].asInt()
                    val afterVersion = json.readTree(it.afterState!!)["version"].asInt()
                    afterVersion == beforeVersion + 1
                },
            )
        }

    @Autowired private lateinit var engagementClock: ControlledEngagementClock

    class ControlledEngagementClock : java.time.Clock() {
        @Volatile var time: java.time.Instant? = null

        override fun instant(): java.time.Instant = time ?: java.time.Instant.now()

        override fun getZone(): java.time.ZoneId = java.time.ZoneOffset.UTC

        override fun withZone(zone: java.time.ZoneId): java.time.Clock = this
    }

    @org.springframework.boot.test.context.TestConfiguration
    class ClockFixture {
        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        fun controlledEngagementClock() = ControlledEngagementClock()
    }

    @org.junit.jupiter.api.AfterEach
    fun resetEngagementClock() {
        engagementClock.time = null
    }

    @Test
    fun `votes enforce identity evidence uniqueness and concurrent undo`() =
        withActors { alice, bob ->
            val q = mcp(alice, "create_question", question()).body["id"].asString()
            val a = web(bob, "/api/v1/questions/$q/answers", answer()).body["id"].asString()

            fun vote(
                who: Actor,
                kind: String,
                id: String,
                active: Boolean,
                agent: Boolean = false,
            ): Result {
                val payload =
                    mapOf(
                        "active" to active,
                        "verification" to "Applied the JVM target fix and reran the failing build successfully.",
                    )
                return if (agent) {
                    mcp(who, "set_vote", payload + mapOf("kind" to kind, "targetId" to id))
                } else {
                    web(who, "/api/v1/content/$kind/$id/vote", payload)
                }
            }
            for (agent in listOf(false, true)) {
                assertEquals(403, vote(alice, "question", q, true, agent).status)
                assertEquals(403, vote(bob, "answer", a, true, agent).status)
            }
            val blank = mcp(alice, "set_vote", mapOf("kind" to "answer", "targetId" to a, "active" to true))
            assertEquals(400, blank.status)
            for (verification in listOf(" ", "x".repeat(501), "api_key=private-test-value")) {
                assertEquals(
                    400,
                    mcp(
                        alice,
                        "set_vote",
                        mapOf(
                            "kind" to "answer",
                            "targetId" to a,
                            "active" to true,
                            "verification" to verification,
                        ),
                    ).status,
                )
            }
            val at = java.time.Instant.parse("2030-05-10T12:00:00Z")
            engagementClock.time = at
            Executors.newFixedThreadPool(8).use { pool ->
                val added = pool.invokeAll((0..15).map { n -> Callable { vote(alice, "answer", a, true, n % 2 == 0) } }).map { it.get() }
                assertTrue(added.all { it.status == 200 && it.body["upvotes"].asInt() == 1 && it.body["votedByYou"].asBoolean() })
                val first = mcp(alice, "list_votes", mapOf("kind" to "answer", "targetId" to a)).body["items"]
                assertEquals(1, first.size())
                assertEquals(alice.id, first[0]["voter"]["id"].asString())
                assertEquals(
                    at,
                    java.time.OffsetDateTime
                        .parse(first[0]["createdAt"].asString())
                        .toInstant(),
                )
                assertTrue(first[0]["callerReportedVerification"].asString().contains("reran"))
                engagementClock.time = at.plusSeconds(60)
                assertEquals(200, vote(alice, "answer", a, true, true).status)
                assertEquals(first, mcp(alice, "list_votes", mapOf("kind" to "answer", "targetId" to a)).body["items"])
                // Undo by the author cannot delete the other developer's vote.
                assertEquals(1, vote(bob, "answer", a, false).body["upvotes"].asInt())
                val undone = pool.invokeAll((0..15).map { n -> Callable { vote(alice, "answer", a, false, n % 2 == 0) } }).map { it.get() }
                assertTrue(undone.all { it.status == 200 && it.body["upvotes"].asInt() == 0 && !it.body["votedByYou"].asBoolean() })
                val mixed =
                    pool
                        .invokeAll(
                            (0..15).map { n ->
                                Callable { vote(bob, "question", q, n % 3 != 0, n % 2 == 0) }
                            },
                        ).map { it.get() }
                assertTrue(mixed.all { it.status == 200 && it.body["upvotes"].asInt() in 0..1 })
                assertEquals(1, vote(bob, "question", q, true, true).body["upvotes"].asInt())
            }
            Playwright.create().use { pw ->
                pw.chromium().launch().use { browser ->
                    val contexts = mutableListOf<BrowserContext>()
                    try {
                        val admin = actor(browser, "aso-admin", contexts)
                        val secondAlice =
                            alice.copy(
                                token =
                                    login
                                        .tokens(
                                            browser,
                                            issuer,
                                            "alice",
                                            client = "aso-mcp-other-test",
                                            resource = "$base/mcp",
                                        )["access_token"]
                                        .asString(),
                            )
                        val secondBob =
                            bob.copy(
                                token =
                                    login
                                        .tokens(
                                            browser,
                                            issuer,
                                            "bob",
                                            client = "aso-mcp-other-test",
                                            resource = "$base/mcp",
                                        )["access_token"]
                                        .asString(),
                            )
                        assertEquals(403, vote(secondAlice, "question", q, true, true).status)
                        assertEquals(403, vote(secondBob, "answer", a, true, true).status)
                        assertEquals(1, vote(secondBob, "question", q, true, true).body["upvotes"].asInt())
                        assertEquals(2, vote(admin, "question", q, true).body["upvotes"].asInt())
                        val page = web(alice, "/api/v1/content/question/$q/votes?limit=1").body
                        assertEquals(1, page["items"].size())
                        val next =
                            mcp(
                                alice,
                                "list_votes",
                                mapOf(
                                    "kind" to "question",
                                    "targetId" to q,
                                    "limit" to 1,
                                    "cursor" to page["nextCursor"].asString(),
                                ),
                            )
                        assertEquals(1, next.body["items"].size())
                        assertNotEquals(page["items"][0]["id"], next.body["items"][0]["id"])
                        val evidenceCursor = page["nextCursor"].asString()
                        assertEquals(400, web(alice, "/api/v1/content/answer/$a/votes?cursor=$evidenceCursor").status)
                        assertEquals(200, vote(alice, "answer", a, true, true).status)
                        assertEquals(
                            200,
                            web(
                                admin,
                                "/api/v1/admin/content/answer/$a/visibility",
                                mapOf(
                                    "expectedVersion" to 1,
                                    "hidden" to true,
                                ),
                            ).status,
                        )
                        assertEquals(404, vote(alice, "answer", a, true, true).status)
                        assertEquals(404, mcp(alice, "list_votes", mapOf("kind" to "answer", "targetId" to a)).status)
                        assertEquals(0, web(alice, "/api/v1/questions/$q").body["answers"]["items"].size())
                        assertEquals(2, web(alice, "/api/v1/questions/$q").body["question"]["votes"]["upvotes"].asInt())
                        assertTrue(web(alice, "/api/v1/questions/$q").body["question"]["acceptedAnswerId"].isNull)
                    } finally {
                        contexts.forEach { it.close() }
                    }
                }
            }
            for (payload in listOf(
                mapOf("active" to "true"),
                mapOf("active" to 1),
                mapOf("active" to true, "actorId" to alice.id),
                mapOf("active" to true, "createdAt" to at.toString()),
            )) {
                assertEquals(400, web(bob, "/api/v1/content/question/$q/vote", payload).status)
                assertEquals(400, mcp(bob, "set_vote", payload + mapOf("kind" to "question", "targetId" to q)).status)
            }
            assertEquals(401, web(null, "/api/v1/content/question/$q/votes").status)
        }

    @Test
    fun `encounters preserve retries distinguish incidents and reject conflicts`() =
        withActors { alice, bob ->
            val q = web(alice, "/api/v1/questions", question()).body["id"].asString()
            val other = web(alice, "/api/v1/questions", question()).body["id"].asString()

            fun detail() = web(alice, "/api/v1/questions/$q").body["question"]
            assertEquals(0, detail()["occurrences"]["allTime"]["encounters"].asInt())
            val event = UUID.randomUUID().toString()
            val at = java.time.Instant.parse("2030-05-10T12:00:00Z")
            engagementClock.time = at
            Executors.newFixedThreadPool(8).use { pool ->
                val results =
                    pool
                        .invokeAll(
                            (0..15).map { n ->
                                Callable {
                                    if (n % 2 == 0) {
                                        web(alice, "/api/v1/questions/$q/occurrences", mapOf("eventId" to event))
                                    } else {
                                        mcp(alice, "report_occurrence", mapOf("questionId" to q, "eventId" to event))
                                    }
                                }
                            },
                        ).map { it.get() }
                assertTrue(results.all { it.status == 200 })
                assertEquals(1, results.map { it.body }.toSet().size)
                val original = results.first().body
                assertEquals(alice.id, original["reporterId"].asString())
                assertEquals(
                    at,
                    java.time.OffsetDateTime
                        .parse(original["createdAt"].asString())
                        .toInstant(),
                )
                engagementClock.time = at.plusSeconds(3600)
                assertEquals(original, mcp(alice, "report_occurrence", mapOf("questionId" to q, "eventId" to event)).body)
                for (id in listOf(other, UUID.randomUUID().toString())) {
                    assertEquals(409, web(alice, "/api/v1/questions/$id/occurrences", mapOf("eventId" to event)).status)
                    assertEquals(409, mcp(alice, "report_occurrence", mapOf("questionId" to id, "eventId" to event)).status)
                }
                assertEquals(1, detail()["occurrences"]["allTime"]["encounters"].asInt())
                assertEquals(200, web(alice, "/api/v1/questions/$q/occurrences", mapOf("eventId" to UUID.randomUUID().toString())).status)
                assertEquals(2, detail()["occurrences"]["allTime"]["encounters"].asInt())
                assertEquals(1, detail()["occurrences"]["allTime"]["distinctDevelopers"].asInt())
                // IDs are scoped to the developer, so Bob may use the same incident UUID independently.
                assertEquals(200, mcp(bob, "report_occurrence", mapOf("questionId" to q, "eventId" to event)).status)
                assertEquals(3, detail()["occurrences"]["allTime"]["encounters"].asInt())
                assertEquals(2, detail()["occurrences"]["allTime"]["distinctDevelopers"].asInt())
                assertEquals(0, detail()["votes"]["upvotes"].asInt())
                assertTrue(detail()["acceptedAnswerId"].isNull)
                val reused = UUID.randomUUID().toString()
                val conflict =
                    pool
                        .invokeAll(
                            listOf(q, other).map { id ->
                                Callable {
                                    mcp(bob, "report_occurrence", mapOf("questionId" to id, "eventId" to reused))
                                }
                            },
                        ).map { it.get().status }
                        .sorted()
                assertEquals(listOf(200, 409), conflict)
            }
            for (payload in listOf(
                mapOf("eventId" to 123),
                mapOf("eventId" to event, "reporterId" to bob.id),
                mapOf(
                    "eventId" to event,
                    "createdAt" to at.toString(),
                ),
            )) {
                assertEquals(400, web(alice, "/api/v1/questions/$q/occurrences", payload).status)
                assertEquals(400, mcp(alice, "report_occurrence", payload + ("questionId" to q)).status)
            }
            Playwright.create().use { pw ->
                pw.chromium().launch().use { browser ->
                    val contexts = mutableListOf<BrowserContext>()
                    try {
                        val admin = actor(browser, "aso-admin", contexts)
                        assertEquals(
                            200,
                            web(
                                admin,
                                "/api/v1/admin/content/question/$q/visibility",
                                mapOf(
                                    "expectedVersion" to 1,
                                    "hidden" to true,
                                ),
                            ).status,
                        )
                        assertEquals(404, mcp(alice, "report_occurrence", mapOf("questionId" to q, "eventId" to event)).status)
                        assertEquals(
                            404,
                            web(bob, "/api/v1/questions/$q/occurrences", mapOf("eventId" to UUID.randomUUID().toString())).status,
                        )
                        val visible = mcp(bob, "list_questions", mapOf("view" to "recurring", "state" to "all")).body["items"]
                        assertTrue(visible.none { it["id"].asString() == q })
                        assertEquals(
                            200,
                            web(
                                admin,
                                "/api/v1/admin/content/question/$q/visibility",
                                mapOf(
                                    "expectedVersion" to 2,
                                    "hidden" to false,
                                ),
                            ).status,
                        )
                        assertTrue(detail()["occurrences"]["allTime"]["encounters"].asInt() in 3..4)
                    } finally {
                        contexts.forEach { it.close() }
                    }
                }
            }
        }

    @Test
    fun `recurring uses exact UTC boundaries deterministic ties and anchored pages`() =
        withActors { alice, bob ->
            val tag = "window-${UUID.randomUUID().toString().take(12)}"

            fun create(): String {
                val result = web(alice, "/api/v1/questions", question() + ("tags" to listOf(tag)))
                assertEquals(200, result.status, result.body.toString())
                return result.body["id"].asString()
            }
            val high = create()
            val many = create()
            val latest = create()
            val tieA = create()
            val tieB = create()
            val old = create()
            val empty = create()
            val asOf = java.time.Instant.parse("2030-05-10T12:00:00Z")

            fun event(
                who: Actor,
                q: String,
                at: java.time.Instant,
            ) {
                engagementClock.time = at
                assertEquals(200, mcp(who, "report_occurrence", mapOf("questionId" to q, "eventId" to UUID.randomUUID().toString())).status)
            }
            val boundary = asOf.minusSeconds(7 * 24 * 3600)
            event(alice, high, boundary) // Inclusive exact lower boundary.
            event(bob, high, boundary.plusSeconds(1))
            for (n in 1..4) event(alice, many, asOf.minusSeconds(100L + n))
            event(alice, latest, asOf) // Inclusive upper boundary.
            event(alice, tieA, asOf.minusSeconds(60))
            event(alice, tieB, asOf.minusSeconds(60))
            event(alice, old, boundary.minusNanos(1000)) // PostgreSQL microsecond immediately outside.
            event(alice, high, asOf.plusNanos(1000)) // Future event excluded at this cutoff.
            engagementClock.time = asOf

            fun listing(
                view: String,
                cursor: String? = null,
                limit: Int = 50,
            ) = mcp(
                alice,
                "list_questions",
                mapOf("tag" to tag, "state" to "all", "view" to view, "limit" to limit) +
                    (cursor?.let { mapOf("cursor" to it) } ?: emptyMap()),
            )
            val all = listing("recurring").body
            val ties = listOf(tieA, tieB).sortedDescending()
            val expected = listOf(high, many, latest) + ties
            assertEquals(expected, all["items"].asSequence().map { it["id"].asString() }.toList())
            assertEquals(2, all["items"][0]["occurrences"]["sevenDay"]["distinctDevelopers"].asInt())
            assertEquals(2, all["items"][0]["occurrences"]["sevenDay"]["encounters"].asInt())
            assertEquals(2, all["items"][0]["occurrences"]["allTime"]["encounters"].asInt())
            assertEquals(0, web(alice, "/api/v1/questions/$old").body["question"]["occurrences"]["sevenDay"]["encounters"].asInt())
            assertEquals(1, web(alice, "/api/v1/questions/$old").body["question"]["occurrences"]["allTime"]["encounters"].asInt())
            val first = listing("recurring", limit = 1).body
            val ids = mutableListOf(first["items"][0]["id"].asString())
            var cursor = first["nextCursor"].asString()
            // New encounters after page one cannot change this traversal's ranking/window.
            repeat(6) { event(bob, tieB, asOf.plusSeconds(1L + it)) }
            do {
                val page = listing("recurring", cursor, 1).body
                assertEquals(first["asOf"], page["asOf"])
                ids.addAll(page["items"].asSequence().map { it["id"].asString() }.toList())
                cursor = if (page["nextCursor"].isNull) "" else page["nextCursor"].asString()
            } while (cursor.isNotEmpty())
            assertEquals(expected, ids)
            assertEquals(tieB, listing("recurring").body["items"][0]["id"].asString())
            assertEquals(400, listing("recent", first["nextCursor"].asString()).status)
            assertEquals(400, mcp(alice, "list_questions", mapOf("view" to "popular")).status)
            val answerId = web(bob, "/api/v1/questions/$empty/answers", answer()).body["id"].asString()
            assertEquals("open", web(alice, "/api/v1/questions/$empty").body["question"]["state"].asString())
            assertTrue(listing("unanswered").body["items"].none { it["id"].asString() == empty })
            val unanswered = listing("unanswered", limit = 1).body
            assertFalse(unanswered["nextCursor"].isNull)
            assertEquals(400, listing("recent", unanswered["nextCursor"].asString()).status)
            Playwright.create().use { pw ->
                pw.chromium().launch().use { browser ->
                    val contexts = mutableListOf<BrowserContext>()
                    try {
                        val admin = actor(browser, "aso-admin", contexts)
                        web(
                            admin,
                            "/api/v1/admin/content/answer/$answerId/visibility",
                            mapOf("expectedVersion" to 1, "hidden" to true),
                        ).also {
                            assertEquals(200, it.status)
                        }
                        assertTrue(listing("unanswered").body["items"].any { it["id"].asString() == empty })
                        web(
                            admin,
                            "/api/v1/admin/content/question/$tieB/visibility",
                            mapOf("expectedVersion" to 1, "hidden" to true),
                        ).also {
                            assertEquals(200, it.status)
                        }
                        assertTrue(listing("recurring").body["items"].none { it["id"].asString() == tieB })
                    } finally {
                        contexts.forEach { it.close() }
                    }
                }
            }
        }

    @Test
    fun `search shares adapters survives outages rebuilds and excludes stale hidden answer matches`() =
        withActors { alice, bob ->
            val tag = "search-" + UUID.randomUUID().toString().take(8)

            fun create(
                title: String,
                body: String,
            ) = web(
                alice,
                "/api/v1/questions",
                mapOf(
                    "requestId" to UUID.randomUUID().toString(),
                    "title" to title,
                    "body" to body,
                    "tags" to listOf(tag),
                ),
            ).also { assertEquals(200, it.status) }.body["id"].asString()

            fun search(
                query: String,
                cursor: String? = null,
                limit: Int = 20,
            ): Result =
                mcp(
                    bob,
                    "search_questions",
                    buildMap {
                        put("query", query)
                        put("tag", tag)
                        put("limit", limit)
                        if (cursor != null) put("cursor", cursor)
                    },
                )

            fun drain(index: SearchIndex = searchIndex) {
                val deadline =
                    System.nanoTime() +
                        java.time.Duration
                            .ofSeconds(60)
                            .toNanos()
                while (index.status().pending > 0 && System.nanoTime() < deadline) index.tick()
                assertEquals(0, index.status().pending)
            }
            val water = create("Water connection", "Aqua supply needs attention in org.example:water:2.0")
            val other = create("Build connection", "Compiler setup in org.example:water:1.0")
            val answer =
                web(
                    bob,
                    "/api/v1/questions/$other/answers",
                    mapOf(
                        "requestId" to UUID.randomUUID().toString(),
                        "body" to "UniqueAnswerCode:7 water",
                    ),
                )
            assertEquals(200, answer.status)
            drain()
            val edited =
                web(
                    alice,
                    "/api/v1/questions/$water/edit",
                    mapOf(
                        "expectedVersion" to 1,
                        "title" to "Water connection revised",
                        "body" to "Aqua supply needs attention in org.example:water:2.0 EditSentinel:3",
                        "tags" to listOf(tag),
                    ),
                )
            assertEquals(200, edited.status)
            val answerId = answer.body["id"].asString()
            assertEquals(
                200,
                web(
                    bob,
                    "/api/v1/answers/$answerId/edit",
                    mapOf(
                        "expectedVersion" to 1,
                        "body" to "UniqueAnswerCode:8 water",
                    ),
                ).status,
            )
            assertEquals(0, search("UniqueAnswerCode:7").body["items"].size())
            drain()
            assertEquals(water, search("EditSentinel:3").body["items"][0]["question"]["id"].asString())
            assertEquals(other, search("UniqueAnswerCode:8").body["items"][0]["question"]["id"].asString())
            assertTrue(embeddings.inputs.any { "Water connection revised" in it && "EditSentinel:3" in it })
            assertEquals(200, web(bob, "/api/v1/content/question/$water/vote", mapOf("active" to true)).status)
            assertEquals(200, web(bob, "/api/v1/questions/$water/occurrences", mapOf("eventId" to UUID.randomUUID().toString())).status)
            val semantic = search("hydration")
            assertEquals(200, semantic.status)
            assertFalse(semantic.body["degraded"].asBoolean())
            assertTrue(semantic.body["items"].any { it["question"]["id"].asString() == water })
            assertEquals(water, search("org.example:water:2.0").body["items"][0]["question"]["id"].asString())
            val rest = web(bob, "/api/v1/search?query=hydration&tag=$tag")
            assertEquals(semantic.body, rest.body)
            assertEquals(401, web(null, "/api/v1/search?query=hydration").status)
            assertEquals(400, mcp(bob, "search_questions", mapOf("query" to "x", "limit" to "20")).status)
            val first = search("connection", limit = 1)
            val second = search("connection", first.body["nextCursor"].asString(), 1)
            assertNotEquals(first.body["items"][0]["question"]["id"], second.body["items"][0]["question"]["id"])
            assertEquals(400, search("changed", first.body["nextCursor"].asString(), 1).status)
            embeddings.failure = true
            val outage = search("org.example:water:2.0")
            assertTrue(outage.body["degraded"].asBoolean())
            assertTrue(outage.body["reasons"].any { it.asString() == "embeddings_unavailable" })
            assertEquals(water, outage.body["items"][0]["question"]["id"].asString())
            assertEquals(1, outage.body["items"][0]["question"]["votes"]["upvotes"].asInt())
            assertEquals(1, outage.body["items"][0]["question"]["occurrences"]["allTime"]["encounters"].asInt())
            val pending = create("Water after outage", "water request still creates successfully")
            searchIndex.tick()
            assertTrue(searchIndex.status().failed > 0)
            assertEquals(200, web(alice, "/api/v1/questions/$pending").status)
            val restarted =
                com.functionaldude.aso.search
                    .SearchIndex(database, embeddingSettings, embeddingClient, transactions)
            assertEquals(searchIndex.status().pending, restarted.status().pending)
            database.execute(
                "update search_work set lease_until = current_timestamp - interval '1 second' where question_id = ?",
                UUID.fromString(pending),
            )
            embeddings.failure = false
            restarted.retry()
            drain(restarted)
            assertFalse(search("hydration").body["degraded"].asBoolean())
            Playwright.create().use { pw ->
                pw.chromium().launch().use { browser ->
                    val contexts = mutableListOf<BrowserContext>()
                    try {
                        val admin = actor(browser, "aso-admin", contexts)
                        val accepted =
                            web(
                                alice,
                                "/api/v1/questions/$other/acceptance",
                                mapOf(
                                    "expectedVersion" to 1,
                                    "answerId" to answer.body["id"].asString(),
                                ),
                            )
                        assertEquals(200, accepted.status)
                        drain()
                        assertEquals("resolved", search("UniqueAnswerCode:8").body["items"][0]["question"]["state"].asString())
                        val hidden =
                            web(
                                admin,
                                "/api/v1/admin/content/answer/${answer.body["id"].asString()}/visibility",
                                mapOf("expectedVersion" to 2, "hidden" to true),
                            )
                        assertEquals(200, hidden.status)
                        val immediate = search("UniqueAnswerCode:8")
                        assertFalse(immediate.body.toString().contains("UniqueAnswerCode:8"))
                        assertEquals("open", web(alice, "/api/v1/questions/$other").body["question"]["state"].asString())
                        assertEquals(
                            200,
                            web(
                                admin,
                                "/api/v1/admin/content/question/$water/visibility",
                                mapOf(
                                    "expectedVersion" to 2,
                                    "hidden" to true,
                                ),
                            ).status,
                        )
                        assertTrue(search("hydration").body["items"].none { it["question"]["id"].asString() == water })
                        assertEquals(404, web(alice, "/api/v1/questions/$water").status)
                        assertEquals(403, web(bob, "/api/v1/admin/search/index").status)
                        assertEquals(200, web(admin, "/api/v1/admin/search/index").status)
                    } finally {
                        contexts.forEach { it.close() }
                    }
                }
            }
            val changed =
                EmbeddingSettings(
                    enabled = true,
                    baseUrl = embeddings.baseUrl,
                    model = "fixture-other",
                    dimensions = 4,
                    documentPrefix = "passage: ",
                )
            val replacement =
                SearchIndex(
                    database,
                    changed,
                    com.functionaldude.aso.search
                        .EmbeddingClient(changed),
                    transactions,
                )
            assertFalse(replacement.status().compatible)
            replacement.tick()
            assertFalse(replacement.status().compatible)
            val productCount = database.fetchCount(QUESTION)
            embeddings.dimensions = 4
            replacement.rebuild()
            drain(replacement)
            assertEquals(productCount, database.fetchCount(QUESTION))
            assertEquals(
                setOf(4),
                database
                    .fetch("select distinct cardinality(embedding) size from search_document")
                    .map {
                        it.get("size", Int::class.java)
                    }.toSet(),
            )
            assertTrue(search("connection").body["reasons"].any { it.asString() == "rebuild_required" })
            embeddings.dimensions = 3
            searchIndex.rebuild()
            drain()
            val concurrent = Executors.newFixedThreadPool(5)
            try {
                val operations =
                    (0 until 12).map { number ->
                        Callable {
                            if (number % 3 == 0) {
                                searchIndex.rebuild()
                                null
                            } else {
                                create("Concurrent water $number", "org.example:concurrent:$number water")
                            }
                        }
                    } +
                        Callable<String?> {
                            repeat(4) { searchIndex.tick() }
                            null
                        }
                val created = concurrent.invokeAll(operations).mapNotNull { it.get() }
                drain()
                created.forEach { id ->
                    assertEquals(
                        1,
                        database
                            .resultQuery(
                                """
                                select 1 from search_document d join question q on q.id=d.question_id
                                where q.id=? and d.generation_id=? and d.revision=q.search_revision and d.answer_id is null
                                """.trimIndent(),
                                UUID.fromString(id),
                                embeddingSettings.generation,
                            ).fetch()
                            .size,
                    )
                }
            } finally {
                concurrent.shutdownNow()
            }
        }

    companion object {
        private val embeddings =
            EmbeddingFixture()
        private val port = ServerSocket(0).use { it.localPort }
        private val base = "http://127.0.0.1:$port"
        private val login = OidcBrowserLogin()
        private val keycloak =
            KeycloakFixture(
                login.callback,
                login.callback,
                "$base/mcp",
                "$base/login/oauth2/code/oidc",
                additionalMcpClient = true,
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
            registry.add("aso.embedding.enabled") { true }
            registry.add("aso.embedding.base-url") { embeddings.baseUrl }
            registry.add("aso.embedding.dimensions") { 3 }
            registry.add("aso.embedding.poll-millis") { 3600000 }
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
            embeddings.close()
            keycloak.close()
            db.close()
            login.close()
        }
    }
}
