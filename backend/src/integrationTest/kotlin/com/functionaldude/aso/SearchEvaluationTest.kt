package com.functionaldude.aso

import com.functionaldude.aso.oidc.KeycloakFixture
import com.functionaldude.aso.oidc.OidcBrowserLogin
import com.functionaldude.aso.search.EmbeddingSettings
import com.functionaldude.aso.search.SearchIndex
import com.functionaldude.aso.search.SearchInput
import com.functionaldude.aso.search.SearchPage
import com.functionaldude.aso.search.SearchReads
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
import tools.jackson.databind.json.JsonMapper
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.ceil
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

data class EvaluationAnswer(
    val id: String,
    val body: String,
)

data class EvaluationDocument(
    val id: String,
    val title: String,
    val body: String,
    val tags: List<String>,
    val answers: List<EvaluationAnswer>,
    val acceptedAnswerId: String?,
    val context: com.functionaldude.aso.questions.TechnicalContextInput? = null,
)

data class EvaluationPolicy(
    val state: String,
    val resultLimit: Int,
    val paginationPageSize: Int,
    val exactMaxRank: Int,
    val semanticMaxRank: Int,
    val semanticMinHits: Int,
    val requireUniqueQuestions: Boolean,
)

data class SourceExpectation(
    val onlyIn: String,
    val answerIds: List<String> = emptyList(),
    val contextFields: List<String> = emptyList(),
)

data class NegativeExpectation(
    val allowedRelatedTargets: List<String>,
    val requiredRelatedTargets: List<String>,
    val forbiddenMatchedIdentifiers: List<String>,
    val forbiddenClaims: List<String>,
    val rationale: String,
    val requiredRelatedMaxRank: Int = 20,
)

data class EvaluationQuery(
    val id: String,
    val kind: String,
    val query: String,
    val target: String?,
    val targetBefore: List<String> = emptyList(),
    val sourceExpectation: SourceExpectation? = null,
    val negativeExpectation: NegativeExpectation? = null,
)

data class EvaluationCorpus(
    val version: Int,
    val ownerReview: String,
    val description: String,
    val evaluation: EvaluationPolicy,
    val documents: List<EvaluationDocument>,
    val queries: List<EvaluationQuery>,
)

data class EvaluationAssertion(
    val name: String,
    val passed: Boolean,
    val detail: String,
)

data class ResultEvidence(
    val id: String,
    val snippet: String,
    val identifiers: List<String>,
    val explanation: String,
    val state: String,
    val acceptedAnswerId: UUID?,
)

data class ModeEvidence(
    val rank: Int?,
    val results: List<ResultEvidence>,
    val assertions: List<EvaluationAssertion>,
)

data class QueryEvidence(
    val id: String,
    val kind: String,
    val query: String,
    val target: String?,
    val lexical: ModeEvidence,
    val hybrid: ModeEvidence,
)

data class LatencyEvidence(
    val samples: Int,
    val concurrency: Int,
    val firstSearchMs: Double,
    val p50Ms: Double,
    val p95Ms: Double,
    val maxMs: Double,
)

data class EvaluationEvidence(
    val recordedAt: String,
    val corpusVersion: Int,
    val corpusGitRevision: String,
    val corpusSha256: String,
    val command: String,
    val ownerReview: String,
    val questions: Int,
    val model: String,
    val dimensions: Int,
    val generation: String,
    val preparation: String,
    val indexSeconds: Double,
    val exactTop3: Int,
    val semanticTop5: Int,
    val lexicalExactTop3: Int,
    val lexicalSemanticTop5: Int,
    val queries: List<QueryEvidence>,
    val latency: LatencyEvidence?,
    val limitations: List<String>,
)

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
class SearchEvaluationTest {
    @Autowired private lateinit var db: DSLContext

    @Autowired private lateinit var index: SearchIndex

    @Autowired private lateinit var settings: EmbeddingSettings

    @Autowired private lateinit var reads: SearchReads
    private val json =
        JsonMapper
            .builder()
            .addModule(
                tools.jackson.module.kotlin.KotlinModule
                    .Builder()
                    .build(),
            ).enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build()
    private val http = HttpClient.newHttpClient()
    private val notice = "Related evidence, not a verified fix. Check versions, context and human acceptance."
    private val claimPattern = Regex("(?i)(verified fix|guaranteed compatib|guarantees? compatib|applicable fix)")

    @Test
    fun `evaluate committed corpus and actual local runtime in disposable data`() {
        val corpus = json.readValue(Path.of(System.getProperty("aso.search.corpus")).toFile(), EvaluationCorpus::class.java)
        require(corpus.version == 2) { "Unsupported evaluation corpus version" }
        require(corpus.ownerReview.startsWith("approved")) { "Owner corpus review must precede evaluation and tuning" }
        val count = System.getProperty("aso.search.questions").toInt()
        assertTrue(count >= corpus.documents.size)
        val owner = UUID.randomUUID()
        db.execute(
            "insert into app_user(id,issuer,subject,display_name) values (?, 'https://evaluation.invalid', 'seed', 'Fictitious seed')",
            owner,
        )
        val mapping =
            corpus.documents.associate { document ->
                val id = UUID.nameUUIDFromBytes(document.id.toByteArray())
                seed(id, owner, document.title, document.body, document.answers, document.tags, document.context, document.acceptedAnswerId)
                document.id to id
            }
        repeat(count - corpus.documents.size) { number ->
            val id = UUID.nameUUIDFromBytes("filler-$number".toByteArray())
            seed(
                id,
                owner,
                "Synthetic catalog entry $number",
                "Fictitious inventory shelf item catalog $number. Storage location and packaging details.",
                listOf(EvaluationAnswer("filler-answer-$number", "Inventory metadata only; no engineering solution is recorded.")),
                listOf("synthetic"),
            )
        }
        val started = System.nanoTime()
        index.rebuild()
        val deadline = started + Duration.ofMinutes(30).toNanos()
        while (index.status().pending > 0 && System.nanoTime() < deadline) {
            index.tick()
            println("Isolated evaluation indexing: ${count - index.status().pending}/$count questions; failed=${index.status().failed}")
            assertEquals(
                0,
                index.status().failed,
                "Actual local embedding indexing failed; inspect configuration and rerun the isolated task",
            )
        }
        assertEquals(0, index.status().pending)
        val indexSeconds = (System.nanoTime() - started) / 1e9
        val reverse = mapping.entries.associate { it.value to it.key }
        Playwright.create().use { playwright ->
            playwright.chromium().launch().use { browser ->
                val token = login.tokens(browser, keycloak.issuer, "alice", resource = "$base/mcp")["access_token"].asString()

                fun search(
                    query: String,
                    cursor: String? = null,
                    limit: Int = corpus.evaluation.resultLimit,
                ): SearchPage {
                    val payload =
                        json.writeValueAsString(
                            mapOf(
                                "jsonrpc" to "2.0",
                                "id" to 1,
                                "method" to "tools/call",
                                "params" to
                                    mapOf(
                                        "name" to "search_questions",
                                        "arguments" to SearchInput(query, state = corpus.evaluation.state, limit = limit, cursor = cursor),
                                    ),
                            ),
                        )
                    val response =
                        http.send(
                            HttpRequest
                                .newBuilder(URI("$base/mcp"))
                                .timeout(Duration.ofSeconds(15))
                                .header("Authorization", "Bearer $token")
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(payload))
                                .build(),
                            HttpResponse.BodyHandlers.ofString(),
                        )
                    assertEquals(200, response.statusCode())
                    val result = json.readTree(response.body())["result"]
                    assertFalse(result["isError"].asBoolean(), result.toString())
                    val page = json.treeToValue(result["structuredContent"], SearchPage::class.java)
                    assertFalse(page.degraded, "Real model search unexpectedly degraded: ${page.reasons}")
                    return page
                }
                val firstStarted = System.nanoTime()
                search(corpus.queries.first().query)
                val firstMs = (System.nanoTime() - firstStarted) / 1e6
                val evidence =
                    corpus.queries.map { query ->
                        val lexical =
                            reads.search(
                                owner,
                                SearchInput(query.query, state = corpus.evaluation.state, limit = corpus.evaluation.resultLimit),
                                null,
                                listOf("lexical_only"),
                            )
                        val hybrid = search(query.query)

                        fun assess(
                            page: SearchPage,
                            semantic: Boolean,
                        ): ModeEvidence {
                            val results =
                                page.items.map {
                                    ResultEvidence(
                                        reverse[it.question.id] ?: "synthetic:${it.question.id}",
                                        it.snippet,
                                        it.match.identifiers,
                                        it.match.explanation,
                                        it.question.state,
                                        it.question.acceptedAnswerId,
                                    )
                                }
                            val labels = results.map { it.id }

                            fun rank(target: String?) = labels.indexOf(target).takeIf { it >= 0 }?.plus(1)
                            val assertions = mutableListOf<EvaluationAssertion>()

                            fun check(
                                name: String,
                                passed: Boolean,
                                detail: String,
                            ) {
                                assertions.add(EvaluationAssertion(name, passed, detail))
                            }
                            check(
                                "unique_questions",
                                !corpus.evaluation.requireUniqueQuestions || labels.distinct().size == labels.size,
                                "Ordered results: $labels",
                            )
                            val paged = mutableListOf<UUID>()
                            var cursor: String? = null
                            do {
                                val nextPage =
                                    if (semantic) {
                                        search(
                                            query.query,
                                            cursor,
                                            corpus.evaluation.paginationPageSize,
                                        )
                                    } else {
                                        reads.search(
                                            owner,
                                            SearchInput(
                                                query.query,
                                                state = corpus.evaluation.state,
                                                limit = corpus.evaluation.paginationPageSize,
                                                cursor = cursor,
                                            ),
                                            null,
                                            listOf("lexical_only"),
                                        )
                                    }
                                paged.addAll(nextPage.items.map { it.question.id })
                                cursor = nextPage.nextCursor
                            } while (cursor != null && paged.size < corpus.evaluation.resultLimit)
                            check(
                                "pagination_order",
                                paged.take(corpus.evaluation.resultLimit) ==
                                    page.items.map {
                                        it.question.id
                                    },
                                "Two-item pages reproduce the first ${corpus.evaluation.resultLimit} ordered results",
                            )
                            check("pagination_unique", paged.distinct().size == paged.size, "No repeated question IDs across pages")
                            query.target?.let { target ->
                                val maximum =
                                    if (query.kind ==
                                        "exact"
                                    ) {
                                        corpus.evaluation.exactMaxRank
                                    } else {
                                        corpus.evaluation.semanticMaxRank
                                    }
                                check(
                                    "target_rank",
                                    (rank(target) ?: Int.MAX_VALUE) <= maximum,
                                    "Target=$target rank=${rank(target)} maximum=$maximum",
                                )
                                query.targetBefore.forEach { distractor ->
                                    check(
                                        "target_before:$distractor",
                                        rank(target) != null && (rank(distractor) == null || rank(target)!! < rank(distractor)!!),
                                        "Target rank=${rank(target)}, distractor rank=${rank(distractor)}",
                                    )
                                }
                                query.sourceExpectation?.let { source ->
                                    require(source.onlyIn in setOf("answers", "context"))
                                    val locations =
                                        corpus.documents.flatMap { document ->
                                            val context = document.context
                                            val parts =
                                                listOf(
                                                    "question" to
                                                        (document.title + "\n" + document.body + "\n" + document.tags.joinToString(" ")),
                                                ) +
                                                    document.answers.map { "answer:${it.id}" to it.body } +
                                                    listOf(
                                                        "context:errorText" to context?.errorText.orEmpty(),
                                                        "context:dependencies" to context?.dependencies.orEmpty(),
                                                        "context:versions" to context?.versions.orEmpty(),
                                                        "context:repository" to context?.repository.orEmpty(),
                                                    )
                                            parts.filter { query.query in it.second }.map { document.id to it.first }
                                        }
                                    val allowed =
                                        if (source.onlyIn ==
                                            "answers"
                                        ) {
                                            source.answerIds.map { "answer:$it" }
                                        } else {
                                            source.contextFields.map { "context:$it" }
                                        }
                                    check(
                                        "source_isolation",
                                        locations.isNotEmpty() && locations.all { it.first == target && it.second in allowed },
                                        "Literal source locations: $locations",
                                    )
                                    val targetResult = results.firstOrNull { it.id == target }
                                    check(
                                        "source_evidence",
                                        targetResult != null &&
                                            (query.query in targetResult.snippet || query.query in targetResult.identifiers),
                                        "Returned source evidence: ${targetResult?.snippet}; identifiers=${targetResult?.identifiers}",
                                    )
                                }
                            }
                            page.items.forEach { item ->
                                val expected = corpus.documents.firstOrNull { mapping[it.id] == item.question.id }
                                if (expected !=
                                    null
                                ) {
                                    check(
                                        "accepted_state:${expected.id}",
                                        item.question.acceptedAnswerId ==
                                            expected.acceptedAnswerId?.let { UUID.nameUUIDFromBytes(it.toByteArray()) },
                                        "Source acceptance is preserved",
                                    )
                                }
                                check(
                                    "source_snippet:${item.question.id}",
                                    index.sources(item.question.id).any {
                                        item.snippet in it.text
                                    },
                                    "Snippet is an excerpt of current visible source content",
                                )
                            }
                            query.negativeExpectation?.let { negative ->
                                check(
                                    "negative_allowlist",
                                    labels.all {
                                        it in negative.allowedRelatedTargets
                                    },
                                    "Allowed=${negative.allowedRelatedTargets}; returned=$labels",
                                )
                                negative.requiredRelatedTargets.forEach { required ->
                                    check(
                                        "negative_required:$required",
                                        (rank(required) ?: Int.MAX_VALUE) <= negative.requiredRelatedMaxRank,
                                        "Required rank=${rank(required)}, maximum=${negative.requiredRelatedMaxRank}",
                                    )
                                }
                                negative.forbiddenMatchedIdentifiers.forEach { identifier ->
                                    check(
                                        "negative_identifier:$identifier",
                                        results.none {
                                            identifier in
                                                it.identifiers ||
                                                identifier in it.snippet ||
                                                identifier in it.explanation
                                        },
                                        "Unsupported identifier is absent from source match evidence",
                                    )
                                }
                                negative.forbiddenClaims.forEach { claim ->
                                    require(
                                        claim in
                                            setOf(
                                                "verified-fix-for-query",
                                                "applicable-fix-for-query",
                                                "cross-version-compatibility-guarantee",
                                            ),
                                    )
                                    check(
                                        "negative_claim:$claim",
                                        page.notice == notice &&
                                            results.all { result ->
                                                !claimPattern.containsMatchIn(result.snippet + " " + result.explanation)
                                            },
                                        "Source evidence has neutral retrieval labels and no fix guarantee. " +
                                            "The browser notice is checked by e2eTest.",
                                    )
                                }
                            }
                            return ModeEvidence(rank(query.target), results, assertions)
                        }
                        QueryEvidence(query.id, query.kind, query.query, query.target, assess(lexical, false), assess(hybrid, true))
                    }
                val latency =
                    if (System.getProperty("aso.search.latency").toBoolean()) {
                        repeat(10) { search(corpus.queries[it % corpus.queries.size].query) }
                        val executor = Executors.newFixedThreadPool(5)
                        val durations =
                            try {
                                executor
                                    .invokeAll(
                                        (0 until 100).map { sample ->
                                            Callable {
                                                val start = System.nanoTime()
                                                search(corpus.queries[sample % corpus.queries.size].query)
                                                (System.nanoTime() - start) / 1e6
                                            }
                                        },
                                    ).map { it.get() }
                                    .sorted()
                            } finally {
                                executor.shutdownNow()
                            }
                        LatencyEvidence(
                            100,
                            5,
                            firstMs,
                            durations[49],
                            durations[ceil(durations.size * 0.95).toInt() - 1],
                            durations.last(),
                        )
                    } else {
                        null
                    }
                val corpusHash =
                    java.security.MessageDigest
                        .getInstance("SHA-256")
                        .digest(Files.readAllBytes(Path.of(System.getProperty("aso.search.corpus"))))
                        .joinToString("") { "%02x".format(it) }
                val report =
                    EvaluationEvidence(
                        Instant.now().toString(),
                        corpus.version,
                        System.getProperty("aso.search.corpus-revision"),
                        corpusHash,
                        System.getProperty("aso.search.command"),
                        corpus.ownerReview,
                        count,
                        settings.model,
                        settings.dimensions,
                        settings.generation,
                        settings.preparationVersion,
                        indexSeconds,
                        evidence.count { it.kind == "exact" && (it.hybrid.rank ?: 999) <= 3 },
                        evidence.count { it.kind == "semantic" && (it.hybrid.rank ?: 999) <= 5 },
                        evidence.count { it.kind == "exact" && (it.lexical.rank ?: 999) <= 3 },
                        evidence.count { it.kind == "semantic" && (it.lexical.rank ?: 999) <= 5 },
                        evidence,
                        latency,
                        listOf(
                            "First search is a cold application query after indexing, not a model unload/load measurement.",
                            "Negative queries have no expected verified solution; suggested neighbors are recorded without claiming a fix.",
                            "All product and IdP state is disposable. No development state is read or mutated.",
                            "Latency distractors are short synthetic inventory records, not production-sized engineering diagnostics.",
                        ),
                    )
                val output = Path.of(System.getProperty("aso.search.report"))
                Files.createDirectories(output.parent)
                Files.writeString(output, json.writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n")
                println(
                    "Actual evaluation: exact=${report.exactTop3}/10 semantic=${report.semanticTop5}/10 p95=${latency?.p95Ms}ms report=$output",
                )
                assertEquals(corpus.queries.count { it.kind == "exact" }, report.exactTop3)
                assertTrue(
                    report.semanticTop5 >= corpus.evaluation.semanticMinHits,
                    "Semantic top-five target missed: ${report.semanticTop5}/10",
                )
                val failures =
                    evidence.flatMap { query ->
                        query.hybrid.assertions
                            .filter { !it.passed && !(query.kind == "semantic" && it.name == "target_rank") }
                            .map { "${query.id}/${it.name}: ${it.detail}" }
                    }
                assertTrue(failures.isEmpty(), failures.joinToString("\n"))
                if (latency != null) {
                    assertTrue(count >= 1000)
                    assertTrue(latency.p95Ms <= 2000, "Actual search p95 missed: ${latency.p95Ms}ms")
                }
            }
        }
    }

    private fun seed(
        id: UUID,
        owner: UUID,
        title: String,
        body: String,
        answers: List<EvaluationAnswer>,
        tags: List<String>,
        context: com.functionaldude.aso.questions.TechnicalContextInput? = null,
        accepted: String? = null,
    ) {
        db.execute(
            """
            insert into question(id,title,body,author_id,channel,error_text,dependencies,versions,repository)
            values (?,?,?,?, 'web',?,?,?,?)
            """.trimIndent(),
            id,
            title,
            body,
            owner,
            context?.errorText,
            context?.dependencies,
            context?.versions,
            context?.repository,
        )
        answers.forEach { answer ->
            db.execute(
                "insert into answer(id,question_id,body,author_id,channel) values (?,?,?,?, 'web')",
                UUID.nameUUIDFromBytes(answer.id.toByteArray()),
                id,
                answer.body,
                owner,
            )
        }
        if (accepted !=
            null
        ) {
            db.execute("update question set accepted_answer_id=? where id=?", UUID.nameUUIDFromBytes(accepted.toByteArray()), id)
        }
        tags.forEach { tag ->
            db.execute("insert into tag(name) values (?) on conflict do nothing", tag)
            db.execute("insert into question_tag(question_id,tag_name) values (?,?)", id, tag)
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
        private val database =
            PostgreSQLContainer(
                DockerImageName.parse(System.getProperty("aso.test.database-image")).asCompatibleSubstituteFor("postgres"),
            ).also {
                it.start()
            }

        @JvmStatic @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("server.port") { port }
            registry.add("spring.datasource.url", database::getJdbcUrl)
            registry.add("spring.datasource.username", database::getUsername)
            registry.add("spring.datasource.password", database::getPassword)
            registry.add("aso.embedding.enabled") { true }
            registry.add("aso.embedding.poll-millis") { 3600000 }
            mapOf(
                "issuer" to keycloak.issuer,
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
            database.close()
            login.close()
        }
    }
}
