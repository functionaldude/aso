package com.functionaldude.aso.questions

import com.functionaldude.aso.identity.Identity
import com.functionaldude.aso.identity.IdentityService
import com.functionaldude.aso.jooq.tables.references.ANSWER
import com.functionaldude.aso.jooq.tables.references.APP_USER
import com.functionaldude.aso.jooq.tables.references.CONTENT_REQUEST
import com.functionaldude.aso.jooq.tables.references.QUESTION
import com.functionaldude.aso.jooq.tables.references.QUESTION_TAG
import com.functionaldude.aso.jooq.tables.references.TAG
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.impl.DSL
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.json.JsonMapper
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.util.Base64
import java.util.UUID

sealed interface ContentView

data class Author(
    val id: UUID,
    val displayName: String,
)

data class TechnicalContext(
    val errorText: String?,
    val dependencies: String?,
    val versions: String?,
    val repository: String?,
)

data class QuestionView(
    val id: UUID,
    val title: String,
    val body: String,
    val bodyHtml: String,
    val tags: List<String>,
    val context: TechnicalContext,
    val author: Author,
    val channel: String,
    val createdAt: OffsetDateTime,
    val state: String,
    val acceptedAnswerId: UUID?,
    val version: Int,
    val updatedAt: OffsetDateTime,
    val hidden: Boolean,
    val votes: VoteSummary,
    val occurrences: QuestionMetrics,
) : ContentView

data class AnswerView(
    val id: UUID,
    val questionId: UUID,
    val body: String,
    val bodyHtml: String,
    val applicability: String?,
    val verification: String?,
    val author: Author,
    val channel: String,
    val createdAt: OffsetDateTime,
    val version: Int,
    val updatedAt: OffsetDateTime,
    val hidden: Boolean,
    val votes: VoteSummary,
) : ContentView

data class ContentPage<T>(
    val items: List<T>,
    val nextCursor: String?,
    val asOf: OffsetDateTime? = null,
)

data class QuestionDetail(
    val question: QuestionView,
    val answers: ContentPage<AnswerView>,
)

@Service
class QuestionService(
    private val db: DSLContext,
    private val identities: IdentityService,
    private val policy: ContentPolicy,
    private val json: JsonMapper,
    private val engagement: EngagementReads,
) {
    @Transactional
    fun createQuestion(
        authentication: Authentication,
        input: CreateQuestionInput,
    ): QuestionView {
        val actor = identities.current(authentication)
        val request = policy.requestId(input.requestId)
        val title = policy.text(input.title, "title", 200, true)!!
        val body = policy.text(input.body, "body", 20000, true)!!
        val tags = policy.tags(input.tags)
        val context = input.context
        val technical =
            TechnicalContext(
                policy.text(context?.errorText, "errorText", 2000),
                policy.text(context?.dependencies, "dependencies", 2000),
                policy.text(context?.versions, "versions", 2000),
                policy.text(context?.repository, "repository", 2000),
            )
        val id =
            reserve(actor, "create_question", request, listOf(title, body, tags, technical)) { id ->
                db
                    .insertInto(QUESTION)
                    .set(QUESTION.ID, id)
                    .set(QUESTION.TITLE, title)
                    .set(QUESTION.BODY, body)
                    .set(QUESTION.ERROR_TEXT, technical.errorText)
                    .set(QUESTION.DEPENDENCIES, technical.dependencies)
                    .set(QUESTION.VERSIONS, technical.versions)
                    .set(QUESTION.REPOSITORY, technical.repository)
                    .set(QUESTION.AUTHOR_ID, actor.id)
                    .set(QUESTION.CHANNEL, actor.channel)
                    .execute()
                tags.forEach { tag ->
                    db
                        .insertInto(TAG)
                        .set(TAG.NAME, tag)
                        .onConflictDoNothing()
                        .execute()
                    db
                        .insertInto(QUESTION_TAG)
                        .set(QUESTION_TAG.QUESTION_ID, id)
                        .set(QUESTION_TAG.TAG_NAME, tag)
                        .execute()
                }
            }
        return question(id, viewer = actor.id)
    }

    @Transactional
    fun createAnswer(
        authentication: Authentication,
        questionId: String,
        input: CreateAnswerInput,
    ): AnswerView {
        val actor = identities.current(authentication)
        val parent = policy.uuid(questionId, "questionId")
        val request = policy.requestId(input.requestId)
        val body = policy.text(input.body, "body", 20000, true)!!
        val applicability = policy.text(input.applicability, "applicability", 2000)
        val verification = policy.text(input.verification, "verification", 2000)
        val id =
            reserve(actor, "create_answer", request, listOf(parent, body, applicability, verification)) { id ->
                lockQuestion(parent)
                question(parent)
                db
                    .insertInto(ANSWER)
                    .set(ANSWER.ID, id)
                    .set(ANSWER.QUESTION_ID, parent)
                    .set(ANSWER.BODY, body)
                    .set(ANSWER.APPLICABILITY, applicability)
                    .set(ANSWER.VERIFICATION, verification)
                    .set(ANSWER.AUTHOR_ID, actor.id)
                    .set(ANSWER.CHANNEL, actor.channel)
                    .execute()
            }
        lockQuestion(parent)
        question(parent)
        return answerById(id, viewer = actor.id)
    }

    @Transactional
    fun list(
        authentication: Authentication,
        tag: String? = null,
        state: String = "open",
        limit: Int = 20,
        cursor: String? = null,
        moderation: Boolean = false,
        view: String = "recent",
    ): ContentPage<QuestionView> {
        val actor = if (moderation) identities.administrator(authentication) else identities.current(authentication)
        bounds(limit)
        if (state !in setOf("all", "open", "resolved")) invalid("state must be all, open or resolved")
        if (view !in setOf("recent", "unanswered", "recurring")) invalid("view must be recent, unanswered or recurring")
        val normalizedTag = tag?.let(policy::tag)
        val scope = "questions:$normalizedTag:$state:$moderation:$view"
        val after = cursor?.let { questionCursor(it, scope) }
        val asOf = after?.asOf ?: engagement.now()
        if (asOf.isAfter(engagement.now())) invalid("Invalid cursor cutoff")
        var condition = if (moderation) DSL.trueCondition() else QUESTION.HIDDEN.isFalse
        condition = condition.and(QUESTION.CREATED_AT.le(asOf))
        if (state == "resolved") condition = condition.and(QUESTION.ACCEPTED_ANSWER_ID.isNotNull)
        if (state == "open") condition = condition.and(QUESTION.ACCEPTED_ANSWER_ID.isNull)
        if (normalizedTag != null) {
            condition =
                condition.and(
                    QUESTION.ID.`in`(
                        db
                            .select(QUESTION_TAG.QUESTION_ID)
                            .from(QUESTION_TAG)
                            .where(QUESTION_TAG.TAG_NAME.eq(normalizedTag)),
                    ),
                )
        }
        if (view == "unanswered") {
            condition =
                condition.andNotExists(
                    db
                        .selectOne()
                        .from(ANSWER)
                        .where(ANSWER.QUESTION_ID.eq(QUESTION.ID))
                        .and(ANSWER.HIDDEN.isFalse),
                )
        }
        val reporters = engagement.reporters(asOf)
        val encounters = engagement.encounters(asOf)
        val latest = engagement.latest(asOf)
        if (view == "recurring") condition = condition.and(encounters.gt(0))
        if (after != null) {
            condition =
                condition.and(
                    if (view == "recurring") {
                        val rank = DSL.row(reporters, encounters, latest, QUESTION.ID)
                        rank.lt(DSL.row(after.reporters, after.encounters, after.time, after.id))
                    } else {
                        QUESTION.CREATED_AT.lt(after.time).or(QUESTION.CREATED_AT.eq(after.time).and(QUESTION.ID.lt(after.id)))
                    },
                )
        }
        val order =
            if (view == "recurring") {
                listOf(reporters.desc(), encounters.desc(), latest.desc(), QUESTION.ID.desc())
            } else {
                listOf(QUESTION.CREATED_AT.desc(), QUESTION.ID.desc())
            }
        val rows =
            db
                .select(
                    *QUESTION.fields(),
                    APP_USER.DISPLAY_NAME,
                    reporters.`as`("reporters"),
                    encounters.`as`("encounters"),
                    latest.`as`("latest"),
                ).from(QUESTION)
                .join(APP_USER)
                .on(QUESTION.AUTHOR_ID.eq(APP_USER.ID))
                .where(condition)
                .orderBy(order)
                .limit(limit + 1)
                .fetch()
        val items = rows.take(limit).map { question(it, actor.id, asOf) }
        val next =
            if (rows.size > limit) {
                rows[limit - 1].let {
                    val time = if (view == "recurring") it.get("latest", OffsetDateTime::class.java)!! else it[QUESTION.CREATED_AT]!!
                    val key =
                        listOf(
                            asOf,
                            time,
                            it[QUESTION.ID],
                            it.get("reporters", Int::class.java),
                            it.get("encounters", Int::class.java),
                            scope,
                        )
                    Base64.getUrlEncoder().withoutPadding().encodeToString(key.joinToString("|").toByteArray())
                }
            } else {
                null
            }
        return ContentPage(items, next, asOf)
    }

    private data class QuestionCursor(
        val asOf: OffsetDateTime,
        val time: OffsetDateTime,
        val id: UUID,
        val reporters: Int,
        val encounters: Int,
    )

    private fun questionCursor(
        value: String,
        scope: String,
    ): QuestionCursor =
        try {
            if (value.length > 500) invalid("Invalid cursor")
            val parts = String(Base64.getUrlDecoder().decode(value)).split('|')
            if (parts.size != 6 || parts[5] != scope) invalid("Cursor does not match the filters")
            val reporters = parts[3].toInt()
            val encounters = parts[4].toInt()
            if (reporters < 0 || encounters < 0) invalid("Invalid cursor")
            QuestionCursor(
                OffsetDateTime.parse(parts[0]),
                OffsetDateTime.parse(parts[1]),
                policy.uuid(parts[2], "cursor"),
                reporters,
                encounters,
            )
        } catch (_: IllegalArgumentException) {
            invalid("Invalid cursor")
        } catch (_: java.time.format.DateTimeParseException) {
            invalid("Invalid cursor")
        }

    @Transactional
    fun detail(
        authentication: Authentication,
        id: String,
        moderation: Boolean = false,
    ): QuestionDetail {
        val actor = if (moderation) identities.administrator(authentication) else identities.current(authentication)
        val uuid = policy.uuid(id, "questionId")
        shareQuestion(uuid)
        return QuestionDetail(question(uuid, moderation, actor.id), answerPage(uuid, 20, null, moderation, actor.id))
    }

    @Transactional
    fun answers(
        authentication: Authentication,
        id: String,
        limit: Int = 20,
        cursor: String? = null,
        moderation: Boolean = false,
    ): ContentPage<AnswerView> {
        val actor = if (moderation) identities.administrator(authentication) else identities.current(authentication)
        val uuid = policy.uuid(id, "questionId")
        shareQuestion(uuid)
        question(uuid, moderation)
        return answerPage(uuid, limit, cursor, moderation, actor.id)
    }

    private fun answerPage(
        parent: UUID,
        limit: Int,
        after: String?,
        moderation: Boolean = false,
        viewer: UUID? = null,
    ): ContentPage<AnswerView> {
        bounds(limit)
        val scope = "answers:$parent:$moderation"
        var condition = ANSWER.QUESTION_ID.eq(parent)
        if (!moderation) condition = condition.and(ANSWER.HIDDEN.isFalse)
        if (after != null) {
            val (time, id) = cursor(after, scope)
            condition = condition.and(ANSWER.CREATED_AT.gt(time).or(ANSWER.CREATED_AT.eq(time).and(ANSWER.ID.gt(id))))
        }
        val rows =
            db
                .select(*ANSWER.fields(), APP_USER.DISPLAY_NAME)
                .from(ANSWER)
                .join(APP_USER)
                .on(ANSWER.AUTHOR_ID.eq(APP_USER.ID))
                .where(condition)
                .orderBy(ANSWER.CREATED_AT.asc(), ANSWER.ID.asc())
                .limit(limit + 1)
                .fetch()
        val items = rows.take(limit).map { answer(it, viewer) }
        return ContentPage(items, if (rows.size > limit) items.last().let { encode(it.createdAt, it.id, scope) } else null)
    }

    private fun bounds(limit: Int) {
        if (limit !in 1..50) invalid("limit must be between 1 and 50")
    }

    private fun encode(
        time: OffsetDateTime,
        id: UUID,
        scope: String,
    ) = Base64.getUrlEncoder().withoutPadding().encodeToString("$time|$id|$scope".toByteArray())

    private fun cursor(
        value: String,
        scope: String,
    ): Pair<OffsetDateTime, UUID> =
        try {
            if (value.length > 500) invalid("Invalid cursor")
            val parts = String(Base64.getUrlDecoder().decode(value)).split('|')
            if (parts.size != 3 || parts[2] != scope) invalid("Cursor does not match the filters")
            OffsetDateTime.parse(parts[0]) to policy.uuid(parts[1], "cursor")
        } catch (_: IllegalArgumentException) {
            invalid("Invalid cursor")
        } catch (_: java.time.format.DateTimeParseException) {
            invalid("Invalid cursor")
        }

    internal fun reserve(
        actor: Identity,
        operation: String,
        request: UUID,
        payload: Any,
        create: (UUID) -> Unit,
    ): UUID {
        val hash = MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(payload)).joinToString("") { "%02x".format(it) }
        val id = UUID.randomUUID()
        val inserted =
            db
                .insertInto(CONTENT_REQUEST)
                .set(CONTENT_REQUEST.ACTOR_ID, actor.id)
                .set(CONTENT_REQUEST.OPERATION, operation)
                .set(CONTENT_REQUEST.REQUEST_ID, request)
                .set(CONTENT_REQUEST.PAYLOAD_HASH, hash)
                .set(CONTENT_REQUEST.RESULT_ID, id)
                .onConflictDoNothing()
                .execute()
        if (inserted == 1) {
            create(id)
            return id
        }
        val previous =
            db
                .selectFrom(CONTENT_REQUEST)
                .where(CONTENT_REQUEST.ACTOR_ID.eq(actor.id))
                .and(CONTENT_REQUEST.OPERATION.eq(operation))
                .and(CONTENT_REQUEST.REQUEST_ID.eq(request))
                .fetchOne()!!
        if (previous.payloadHash != hash) {
            throw ContentFailure(
                409,
                "request_conflict",
                "requestId was already used with different content; retry the original payload or use a new requestId for a new post",
            )
        }
        return previous.resultId!!
    }

    internal fun question(
        id: UUID,
        moderation: Boolean = false,
        viewer: UUID? = null,
    ): QuestionView =
        db
            .select(*QUESTION.fields(), APP_USER.DISPLAY_NAME)
            .from(QUESTION)
            .join(APP_USER)
            .on(QUESTION.AUTHOR_ID.eq(APP_USER.ID))
            .where(QUESTION.ID.eq(id))
            .and(if (moderation) DSL.trueCondition() else QUESTION.HIDDEN.isFalse)
            .fetchOne()
            ?.let { question(it, viewer, engagement.now()) }
            ?: throw ContentFailure(404, "not_found", "Question not found")

    private fun question(
        row: Record,
        viewer: UUID?,
        asOf: OffsetDateTime,
    ): QuestionView =
        QuestionView(
            row[QUESTION.ID]!!,
            row[QUESTION.TITLE]!!,
            row[QUESTION.BODY]!!,
            policy.html(row[QUESTION.BODY]!!),
            db
                .select(QUESTION_TAG.TAG_NAME)
                .from(QUESTION_TAG)
                .where(QUESTION_TAG.QUESTION_ID.eq(row[QUESTION.ID]))
                .orderBy(QUESTION_TAG.TAG_NAME)
                .fetch(QUESTION_TAG.TAG_NAME)
                .map { it!! },
            TechnicalContext(row[QUESTION.ERROR_TEXT], row[QUESTION.DEPENDENCIES], row[QUESTION.VERSIONS], row[QUESTION.REPOSITORY]),
            Author(row[QUESTION.AUTHOR_ID]!!, row[APP_USER.DISPLAY_NAME]!!),
            row[QUESTION.CHANNEL]!!,
            row[QUESTION.CREATED_AT]!!,
            if (row[QUESTION.ACCEPTED_ANSWER_ID] == null) "open" else "resolved",
            row[QUESTION.ACCEPTED_ANSWER_ID],
            row[QUESTION.VERSION]!!,
            row[QUESTION.UPDATED_AT]!!,
            row[QUESTION.HIDDEN]!!,
            engagement.votes(row[QUESTION.ID]!!, null, viewer),
            engagement.metrics(row[QUESTION.ID]!!, asOf),
        )

    private fun answer(
        row: Record,
        viewer: UUID?,
    ): AnswerView =
        AnswerView(
            row[ANSWER.ID]!!,
            row[ANSWER.QUESTION_ID]!!,
            row[ANSWER.BODY]!!,
            policy.html(row[ANSWER.BODY]!!),
            row[ANSWER.APPLICABILITY],
            row[ANSWER.VERIFICATION],
            Author(row[ANSWER.AUTHOR_ID]!!, row[APP_USER.DISPLAY_NAME]!!),
            row[ANSWER.CHANNEL]!!,
            row[ANSWER.CREATED_AT]!!,
            row[ANSWER.VERSION]!!,
            row[ANSWER.UPDATED_AT]!!,
            row[ANSWER.HIDDEN]!!,
            engagement.votes(row[ANSWER.QUESTION_ID]!!, row[ANSWER.ID]!!, viewer),
        )

    internal fun shareQuestion(id: UUID) {
        db
            .select(QUESTION.ID)
            .from(QUESTION)
            .where(QUESTION.ID.eq(id))
            .forShare()
            .fetchOne()
            ?: throw ContentFailure(404, "not_found", "Question not found")
    }

    internal fun lockQuestion(id: UUID) {
        db
            .select(QUESTION.ID)
            .from(QUESTION)
            .where(QUESTION.ID.eq(id))
            .forUpdate()
            .fetchOne()
            ?: throw ContentFailure(404, "not_found", "Question not found")
    }

    internal fun answerById(
        id: UUID,
        moderation: Boolean = false,
        viewer: UUID? = null,
    ): AnswerView =
        db
            .select(*ANSWER.fields(), APP_USER.DISPLAY_NAME)
            .from(ANSWER)
            .join(APP_USER)
            .on(ANSWER.AUTHOR_ID.eq(APP_USER.ID))
            .join(QUESTION)
            .on(ANSWER.QUESTION_ID.eq(QUESTION.ID))
            .where(ANSWER.ID.eq(id))
            .and(if (moderation) DSL.trueCondition() else ANSWER.HIDDEN.isFalse.and(QUESTION.HIDDEN.isFalse))
            .fetchOne()
            ?.let { answer(it, viewer) } ?: throw ContentFailure(404, "not_found", "Answer not found")
}
