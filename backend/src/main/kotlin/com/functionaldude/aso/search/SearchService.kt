package com.functionaldude.aso.search

import com.functionaldude.aso.identity.IdentityService
import com.functionaldude.aso.jooq.tables.references.QUESTION
import com.functionaldude.aso.jooq.tables.references.QUESTION_TAG
import com.functionaldude.aso.questions.ContentPolicy
import com.functionaldude.aso.questions.QuestionService
import com.functionaldude.aso.questions.QuestionView
import com.functionaldude.aso.questions.invalid
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

data class SearchInput(
    val query: String? = null,
    val tag: String? = null,
    val state: String = "all",
    val limit: Int = 20,
    val cursor: String? = null,
)

data class SearchMatch(
    val identifiers: List<String>,
    val lexical: Boolean,
    val semantic: Boolean,
    val explanation: String,
)

data class SearchResult(
    val question: QuestionView,
    val snippet: String,
    val match: SearchMatch,
)

data class SearchPage(
    val items: List<SearchResult>,
    val nextCursor: String?,
    val degraded: Boolean,
    val reasons: List<String>,
    val generation: String?,
    val notice: String = "Related evidence, not a verified fix. Check versions, context and human acceptance.",
)

data class SearchCandidate(
    val id: UUID,
    val score: Double,
    val lexical: Boolean,
    val semantic: Boolean,
    val text: String,
)

object SearchIdentifiers {
    fun tokens(query: String): List<String> =
        Regex("[\\p{L}\\p{N}_][\\p{L}\\p{N}_.:+#/-]*")
            .findAll(query)
            .map { it.value.trimEnd('.', ',', ':', '/') }
            .filter { token ->
                token.any { it.isDigit() || it in ".:+#/_-" } || Regex("[a-z][A-Z]").containsMatchIn(token) ||
                    Regex("[A-Z]{2,}").matches(token)
            }.distinct()
            .take(20)
            .toList()

    fun pattern(token: String): String {
        val boundary = if (':' in token) "[^[:alnum:]_.:+#/-]" else "[^[:alnum:]_.+#/-]"
        return "(^|[^[:alnum:]_.:+#/-])" + token.map(::escape).joinToString("") + "($|$boundary|[.]($|$boundary))"
    }

    private fun escape(character: Char): String = if (character in ".+*?^$[](){}|\\") "\\$character" else "$character"

    fun required(token: String): Boolean = token.any { it.isDigit() || it in ".:+#/_-" } || Regex("[a-z][A-Z]").containsMatchIn(token)

    fun matched(
        text: String,
        tokens: List<String>,
    ): List<String> =
        tokens.filter { token ->
            val boundary = if (':' in token) "[^\\p{L}\\p{N}_.:+#/-]" else "[^\\p{L}\\p{N}_.+#/-]"
            val ending = "(?=$|$boundary|[.]($|$boundary))"
            Regex("(?<![\\p{L}\\p{N}_.:+#/-])${Regex.escape(token)}$ending", RegexOption.IGNORE_CASE).containsMatchIn(text)
        }
}

@Service
class SearchService(
    private val identities: IdentityService,
    private val policy: ContentPolicy,
    private val settings: EmbeddingSettings,
    private val embeddings: EmbeddingClient,
    private val index: SearchIndex,
    private val reads: SearchReads,
) {
    fun search(
        authentication: Authentication,
        input: SearchInput,
        lexicalOnly: Boolean = false,
    ): SearchPage {
        val actor = identities.current(authentication)
        val query = policy.text(input.query, "query", settings.maxQueryCharacters, true)!!
        val tag = policy.text(input.tag, "tag", 40)?.let(policy::tag)
        if (input.state !in setOf("all", "open", "resolved") || input.limit !in 1..50) invalid("Invalid search state or page limit")
        val status = index.status()
        val reasons = mutableListOf<String>()
        val vector =
            if (lexicalOnly || !settings.enabled || !status.compatible) {
                reasons.add(
                    if (lexicalOnly) {
                        "lexical_only"
                    } else if (!settings.enabled) {
                        "embeddings_disabled"
                    } else {
                        "rebuild_required"
                    },
                )
                null
            } else {
                try {
                    embeddings.query(query)
                } catch (_: EmbeddingUnavailable) {
                    reasons.add("embeddings_unavailable")
                    null
                }
            }
        if (status.pending > 0) reasons.add("index_pending")
        return reads.search(actor.id, input.copy(query = query, tag = tag), vector, reasons)
    }
}

@Service
class SearchReads(
    private val db: DSLContext,
    private val questions: QuestionService,
    private val index: SearchIndex,
    private val settings: EmbeddingSettings,
) {
    @Transactional
    fun search(
        viewer: UUID,
        input: SearchInput,
        vector: List<Double>?,
        reasons: List<String>,
    ): SearchPage {
        var condition = QUESTION.HIDDEN.isFalse
        if (input.state == "open") condition = condition.and(QUESTION.ACCEPTED_ANSWER_ID.isNull)
        if (input.state == "resolved") condition = condition.and(QUESTION.ACCEPTED_ANSWER_ID.isNotNull)
        if (input.tag !=
            null
        ) {
            condition =
                condition.and(
                    QUESTION.ID.`in`(db.select(QUESTION_TAG.QUESTION_ID).from(QUESTION_TAG).where(QUESTION_TAG.TAG_NAME.eq(input.tag))),
                )
        }
        val ids =
            db
                .select(QUESTION.ID)
                .from(QUESTION)
                .where(condition)
                .orderBy(QUESTION.ID)
                .forShare()
                .fetch(QUESTION.ID)
                .filterNotNull()
        val compatible = index.status().compatible
        val actualVector = vector?.takeIf { compatible }
        val actualReasons = reasons + if (vector != null && !compatible) listOf("rebuild_required") else emptyList()
        val scope =
            fingerprint(listOf(input.query, input.tag, input.state, settings.generation, actualVector != null).joinToString("\u001f"))
        val after = decode(input.cursor, scope)
        if (ids.isEmpty()) return SearchPage(emptyList(), null, actualReasons.isNotEmpty(), actualReasons.distinct(), settings.generation)
        val tokens = SearchIdentifiers.tokens(input.query!!)
        val identifiersMatch =
            DSL.and(
                tokens.filter(SearchIdentifiers::required).map { token ->
                    DSL.condition("bool_or(text ~* {0})", DSL.value(SearchIdentifiers.pattern(token)))
                },
            )
        val exact =
            tokens.fold<String, org.jooq.Field<Int>>(
                DSL.inline(0),
            ) { expression, token ->
                expression.plus(
                    DSL.field("case when text ~* {0} then 1 else 0 end", Int::class.java, DSL.value(SearchIdentifiers.pattern(token))),
                )
            }
        val similarity =
            if (actualVector == null) {
                DSL.inline(-1.0)
            } else {
                DSL.field(
                    """
                    (select max(1 - (d.embedding::vector <=> {0}::vector)) from search_document d
                    where d.question_id=docs.id and d.generation_id={1} and d.revision=docs.revision
                    and d.answer_id is not distinct from docs.answer_id)
                    """.trimIndent(),
                    Double::class.java,
                    DSL.value(actualVector.joinToString(",", "[", "]")),
                    DSL.value(settings.generation),
                )
            }
        val idList = DSL.list(ids.map { DSL.value(it) })
        val rows =
            db
                .resultQuery(
                    """
                    with docs as (
                      select q.id, q.search_revision revision, null::uuid answer_id,
                        concat_ws(E'\n', q.title, q.body, q.error_text, q.dependencies, q.versions, q.repository,
                          (select string_agg(tag_name, ' ' order by tag_name) from question_tag where question_id=q.id)) text
                      from question q where q.id in ({0}) and not q.hidden
                      union all
                      select q.id, q.search_revision, a.id, concat_ws(E'\n', q.title, a.body, a.applicability, a.verification)
                      from question q join answer a on a.question_id=q.id where q.id in ({0}) and not q.hidden and not a.hidden
                    ), measured as (
                      select *, {1} exact,
                        ts_rank_cd(to_tsvector('english', text),
                          to_tsquery('english', replace(plainto_tsquery('english', {2})::text, ' & ', ' | '))) lexical,
                        coalesce({3}, -1) similarity from docs
                    ), candidates as (
                      select id, max(exact) exact, max(lexical) lexical, max(similarity) similarity, {8} identifiers_match,
                        (array_agg(text order by exact desc, lexical desc, similarity desc, answer_id nulls first))[1] text
                      from measured group by id
                    ), ranked as (
                      select *, case when lexical > 0 then 1.0/(60 + rank() over(order by lexical desc)) else 0 end lr,
                        case when similarity >= {4} then 1.0/(60 + rank() over(order by similarity desc)) else 0 end sr
                      from candidates
                    ), scored as (
                      select *, (exact*10 + lr + sr)::double precision score from ranked
                      where identifiers_match and (exact>0 or lexical>0 or similarity >= {4})
                    )
                    select * from scored where score < {5} or (score = {5} and id > {6})
                    order by score desc, id limit {7}
                    """.trimIndent(),
                    idList,
                    exact,
                    DSL.value(input.query),
                    similarity,
                    DSL.value(settings.minSimilarity),
                    DSL.value(after?.first ?: Double.MAX_VALUE),
                    DSL.value(after?.second ?: UUID(0, 0)),
                    DSL.value(input.limit + 1),
                    identifiersMatch,
                ).fetch { row ->
                    SearchCandidate(
                        row.get("id", UUID::class.java)!!,
                        row.get("score", Double::class.java)!!,
                        row.get("lexical", Double::class.java)!! > 0,
                        actualVector != null && row.get("similarity", Double::class.java)!! >= settings.minSimilarity,
                        row.get("text", String::class.java)!!,
                    )
                }
        val items =
            rows.take(input.limit).map { candidate ->
                val matched = SearchIdentifiers.matched(candidate.text, tokens)
                val words = (matched + input.query.split(Regex("\\s+"))).filter { it.length > 2 }
                val start = words.map { candidate.text.indexOf(it, ignoreCase = true) }.filter { it >= 0 }.minOrNull() ?: 0
                val snippet = candidate.text.drop((start - 60).coerceAtLeast(0)).take(360)
                val explanation =
                    listOfNotNull(
                        if (matched.isNotEmpty()) "Exact identifiers: ${matched.joinToString()}" else null,
                        if (candidate.lexical) "Lexical text match" else null,
                        if (candidate.semantic) "Semantic similarity" else null,
                    ).joinToString("; ")
                SearchResult(
                    questions.question(candidate.id, viewer = viewer),
                    snippet,
                    SearchMatch(matched, candidate.lexical, candidate.semantic, explanation),
                )
            }
        val next = if (rows.size > input.limit) rows[input.limit - 1].let { encode("$scope|${it.score}|${it.id}") } else null
        return SearchPage(items, next, actualReasons.isNotEmpty(), actualReasons.distinct(), settings.generation)
    }

    private fun fingerprint(value: String) =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") {
            "%02x".format(it)
        }

    private fun encode(value: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray())

    private fun decode(
        value: String?,
        scope: String,
    ): Pair<Double, UUID>? {
        if (value == null) return null
        try {
            if (value.length > 500) invalid("Invalid search cursor")
            val parts = String(Base64.getUrlDecoder().decode(value)).split('|')
            if (parts.size != 3 || parts[0] != scope) invalid("Search conditions changed; restart from the first page")
            val score = parts[1].toDouble()
            if (!score.isFinite()) invalid("Invalid search cursor")
            return score to UUID.fromString(parts[2])
        } catch (_: IllegalArgumentException) {
            invalid("Invalid search cursor")
        }
    }
}
