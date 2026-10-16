package com.functionaldude.aso.questions

import com.functionaldude.aso.identity.Identity
import com.functionaldude.aso.identity.IdentityService
import com.functionaldude.aso.jooq.tables.references.ANSWER
import com.functionaldude.aso.jooq.tables.references.APP_USER
import com.functionaldude.aso.jooq.tables.references.CONTENT_AUDIT
import com.functionaldude.aso.jooq.tables.references.CONTENT_COMMENT
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

data class CommentView(
    val id: UUID,
    val questionId: UUID,
    val answerId: UUID?,
    val body: String,
    val bodyHtml: String,
    val author: Author,
    val channel: String,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
    val version: Int,
    val hidden: Boolean,
) : ContentView

data class ContentFingerprints(
    val body: String,
    val title: String? = null,
    val tags: String? = null,
    val errorText: String? = null,
    val dependencies: String? = null,
    val versions: String? = null,
    val repository: String? = null,
    val applicability: String? = null,
    val verification: String? = null,
)

data class AuditState(
    val version: Int,
    val hidden: Boolean,
    val acceptedAnswerId: UUID?,
    val contentHash: String,
    val fields: ContentFingerprints,
)

/** Every lifecycle mutation locks the parent question first. All child/acceptance changes share this order. */
@Service
class LifecycleService(
    private val db: DSLContext,
    private val identities: IdentityService,
    private val questions: QuestionService,
    private val policy: ContentPolicy,
    private val json: JsonMapper,
) {
    private fun id(
        value: String?,
        field: String,
    ) = policy.uuid(policy.text(value, field, 36, true)!!, field)

    private fun missing(): Nothing = throw ContentFailure(404, "not_found", "Content not found")

    private fun forbidden(): Nothing =
        throw ContentFailure(
            403,
            "forbidden",
            "Only the author may edit this content; moderation requires an administrator browser session",
        )

    private fun version(
        expected: Int?,
        actual: Int,
    ) {
        if (expected == null || expected < 1) invalid("expectedVersion must be a positive integer")
        if (expected != actual) {
            throw ContentFailure(
                409,
                "stale_version",
                "Content changed. Keep your draft, reload the latest content, compare it, and explicitly retry with its version.",
            )
        }
    }

    private fun actor(
        authentication: Authentication,
        moderation: Boolean,
    ) = if (moderation) identities.administrator(authentication) else identities.current(authentication)

    private fun parent(
        kind: String,
        target: UUID,
    ): UUID =
        when (kind) {
            "question" -> target
            "answer" ->
                db
                    .select(ANSWER.QUESTION_ID)
                    .from(ANSWER)
                    .where(ANSWER.ID.eq(target))
                    .fetchOne(ANSWER.QUESTION_ID) ?: missing()
            "comment" ->
                db
                    .select(CONTENT_COMMENT.QUESTION_ID)
                    .from(CONTENT_COMMENT)
                    .where(CONTENT_COMMENT.ID.eq(target))
                    .fetchOne(CONTENT_COMMENT.QUESTION_ID)
                    ?: missing()
            else -> invalid("kind must be question, answer or comment")
        }

    private fun lock(
        kind: String,
        target: UUID,
        moderation: Boolean,
    ): UUID {
        val parent = parent(kind, target)
        questions.lockQuestion(parent)
        questions.question(parent, moderation)
        if (kind == "answer") questions.answerById(target, moderation)
        if (kind == "comment") comment(target, moderation)
        return parent
    }

    @Transactional
    fun read(
        authentication: Authentication,
        kind: String,
        targetId: String,
        moderation: Boolean = false,
    ): ContentView {
        actor(authentication, moderation)
        val target = id(targetId, "targetId")
        questions.shareQuestion(parent(kind, target))
        return when (kind) {
            "question" -> questions.question(target, moderation)
            "answer" -> questions.answerById(target, moderation)
            "comment" -> comment(target, moderation)
            else -> invalid("Invalid content kind")
        }
    }

    @Transactional
    fun editQuestion(
        authentication: Authentication,
        targetId: String,
        input: EditQuestionInput,
        moderation: Boolean = false,
    ): QuestionView {
        val actor = actor(authentication, moderation)
        val target = id(targetId, "questionId")
        lock("question", target, moderation)
        val before = questions.question(target, moderation)
        if (!moderation && before.author.id != actor.id) forbidden()
        version(input.expectedVersion, before.version)
        val title = policy.text(input.title, "title", 200, true)!!
        val body = policy.text(input.body, "body", 20000, true)!!
        val tags = policy.tags(input.tags)
        val context = input.context
        db
            .update(QUESTION)
            .set(QUESTION.TITLE, title)
            .set(QUESTION.BODY, body)
            .set(QUESTION.ERROR_TEXT, policy.text(context?.errorText, "errorText", 2000))
            .set(QUESTION.DEPENDENCIES, policy.text(context?.dependencies, "dependencies", 2000))
            .set(QUESTION.VERSIONS, policy.text(context?.versions, "versions", 2000))
            .set(QUESTION.REPOSITORY, policy.text(context?.repository, "repository", 2000))
            .set(QUESTION.VERSION, before.version + 1)
            .set(QUESTION.UPDATED_AT, OffsetDateTime.now())
            .where(QUESTION.ID.eq(target))
            .execute()
        db.deleteFrom(QUESTION_TAG).where(QUESTION_TAG.QUESTION_ID.eq(target)).execute()
        tags.forEach { tag ->
            db
                .insertInto(TAG)
                .set(TAG.NAME, tag)
                .onConflictDoNothing()
                .execute()
            db
                .insertInto(QUESTION_TAG)
                .set(QUESTION_TAG.QUESTION_ID, target)
                .set(QUESTION_TAG.TAG_NAME, tag)
                .execute()
        }
        val after = questions.question(target, moderation)
        audit(actor, "edit", "question", target, target, state(before), state(after))
        return after
    }

    @Transactional
    fun editAnswer(
        authentication: Authentication,
        targetId: String,
        input: EditAnswerInput,
        moderation: Boolean = false,
    ): AnswerView {
        val actor = actor(authentication, moderation)
        val target = id(targetId, "answerId")
        val parent = lock("answer", target, moderation)
        val before = questions.answerById(target, moderation)
        if (!moderation && before.author.id != actor.id) forbidden()
        version(input.expectedVersion, before.version)
        db
            .update(ANSWER)
            .set(ANSWER.BODY, policy.text(input.body, "body", 20000, true)!!)
            .set(ANSWER.APPLICABILITY, policy.text(input.applicability, "applicability", 2000))
            .set(ANSWER.VERIFICATION, policy.text(input.verification, "verification", 2000))
            .set(ANSWER.VERSION, before.version + 1)
            .set(ANSWER.UPDATED_AT, OffsetDateTime.now())
            .where(ANSWER.ID.eq(target))
            .execute()
        val after = questions.answerById(target, moderation)
        audit(actor, "edit", "answer", target, parent, state(before), state(after))
        return after
    }

    @Transactional
    fun editComment(
        authentication: Authentication,
        targetId: String,
        input: EditCommentInput,
        moderation: Boolean = false,
    ): CommentView {
        val actor = actor(authentication, moderation)
        val target = id(targetId, "commentId")
        val parent = lock("comment", target, moderation)
        val before = comment(target, moderation)
        if (!moderation && before.author.id != actor.id) forbidden()
        version(input.expectedVersion, before.version)
        db
            .update(CONTENT_COMMENT)
            .set(CONTENT_COMMENT.BODY, policy.text(input.body, "body", 20000, true)!!)
            .set(CONTENT_COMMENT.VERSION, before.version + 1)
            .set(CONTENT_COMMENT.UPDATED_AT, OffsetDateTime.now())
            .where(CONTENT_COMMENT.ID.eq(target))
            .execute()
        val after = comment(target, moderation)
        audit(actor, "edit", "comment", target, parent, state(before), state(after))
        return after
    }

    @Transactional
    fun createComment(
        authentication: Authentication,
        kind: String,
        targetId: String,
        input: CreateCommentInput,
    ): CommentView {
        val actor = identities.current(authentication)
        if (kind !in setOf("question", "answer")) invalid("Comments belong to a question or answer")
        val target = id(targetId, "targetId")
        val body = policy.text(input.body, "body", 20000, true)!!
        val result =
            questions.reserve(actor, "create_comment", policy.requestId(input.requestId), listOf(kind, target, body)) { result ->
                val parent = lock(kind, target, false)
                db
                    .insertInto(CONTENT_COMMENT)
                    .set(CONTENT_COMMENT.ID, result)
                    .set(CONTENT_COMMENT.QUESTION_ID, parent)
                    .set(CONTENT_COMMENT.ANSWER_ID, if (kind == "answer") target else null)
                    .set(CONTENT_COMMENT.BODY, body)
                    .set(CONTENT_COMMENT.AUTHOR_ID, actor.id)
                    .set(CONTENT_COMMENT.CHANNEL, actor.channel)
                    .execute()
            }
        lock(kind, target, false)
        return comment(result)
    }

    @Transactional
    fun comments(
        authentication: Authentication,
        kind: String,
        targetId: String,
        limit: Int = 20,
        cursor: String? = null,
        moderation: Boolean = false,
    ): ContentPage<CommentView> {
        actor(authentication, moderation)
        if (limit !in 1..50) invalid("limit must be between 1 and 50")
        if (kind !in setOf("question", "answer")) invalid("Comments belong to a question or answer")
        val target = id(targetId, "targetId")
        val parent = parent(kind, target)
        questions.shareQuestion(parent)
        questions.question(parent, moderation)
        if (kind == "answer") questions.answerById(target, moderation)
        var condition =
            CONTENT_COMMENT.QUESTION_ID.eq(parent).and(
                if (kind == "question") {
                    CONTENT_COMMENT.ANSWER_ID.isNull
                } else {
                    CONTENT_COMMENT.ANSWER_ID.eq(target)
                },
            )
        if (!moderation) condition = condition.and(CONTENT_COMMENT.HIDDEN.isFalse)
        val scope = "$kind:$target:$moderation"
        if (cursor != null) {
            try {
                if (cursor.length > 500) invalid("Invalid cursor")
                val parts = String(Base64.getUrlDecoder().decode(cursor)).split('|')
                if (parts.size != 3 || parts[2] != scope) invalid("Cursor does not match the collection")
                val time = OffsetDateTime.parse(parts[0])
                val last = id(parts[1], "cursor")
                condition =
                    condition.and(
                        CONTENT_COMMENT.CREATED_AT.gt(time).or(CONTENT_COMMENT.CREATED_AT.eq(time).and(CONTENT_COMMENT.ID.gt(last))),
                    )
            } catch (_: IllegalArgumentException) {
                invalid("Invalid cursor")
            } catch (_: java.time.format.DateTimeParseException) {
                invalid("Invalid cursor")
            }
        }
        val rows =
            db
                .select(*CONTENT_COMMENT.fields(), APP_USER.DISPLAY_NAME)
                .from(CONTENT_COMMENT)
                .join(APP_USER)
                .on(CONTENT_COMMENT.AUTHOR_ID.eq(APP_USER.ID))
                .where(condition)
                .orderBy(CONTENT_COMMENT.CREATED_AT, CONTENT_COMMENT.ID)
                .limit(limit + 1)
                .fetch()
        val items = rows.take(limit).map(::comment)
        val next =
            if (rows.size > limit) {
                items.last().let { Base64.getUrlEncoder().withoutPadding().encodeToString("${it.createdAt}|${it.id}|$scope".toByteArray()) }
            } else {
                null
            }
        return ContentPage(items, next)
    }

    @Transactional
    fun accept(
        authentication: Authentication,
        questionId: String,
        input: AcceptanceInput,
    ): QuestionView {
        val actor = identities.current(authentication, human = true)
        val target = id(questionId, "questionId")
        lock("question", target, false)
        val before = questions.question(target)
        if (before.author.id != actor.id && !actor.admin) {
            throw ContentFailure(403, "forbidden", "Only the question owner or an administrator may change acceptance")
        }
        version(input.expectedVersion, before.version)
        val answer = input.answerId?.let { questions.answerById(id(it, "answerId")) }
        if (answer != null && answer.questionId != target) invalid("Accepted answer must belong to this question")
        if (before.acceptedAnswerId == answer?.id) return before
        db
            .update(QUESTION)
            .set(QUESTION.ACCEPTED_ANSWER_ID, answer?.id)
            .set(QUESTION.VERSION, before.version + 1)
            .set(QUESTION.UPDATED_AT, OffsetDateTime.now())
            .where(QUESTION.ID.eq(target))
            .execute()
        val after = questions.question(target)
        audit(actor, "accept", "question", target, target, state(before), state(after))
        return after
    }

    @Transactional
    fun visibility(
        authentication: Authentication,
        kind: String,
        targetId: String,
        input: VisibilityInput,
    ): AuditState {
        val actor = identities.administrator(authentication)
        val target = id(targetId, "targetId")
        val parent = lock(kind, target, true)
        val before = snapshot(kind, target)
        version(input.expectedVersion, before.version)
        val hidden = input.hidden ?: invalid("hidden must be a boolean")
        if (hidden == before.hidden) return before
        val now = OffsetDateTime.now()
        when (kind) {
            "question" ->
                db
                    .update(QUESTION)
                    .set(QUESTION.HIDDEN, hidden)
                    .set(QUESTION.VERSION, before.version + 1)
                    .set(QUESTION.UPDATED_AT, now)
                    .where(QUESTION.ID.eq(target))
                    .execute()
            "answer" ->
                db
                    .update(ANSWER)
                    .set(ANSWER.HIDDEN, hidden)
                    .set(ANSWER.VERSION, before.version + 1)
                    .set(ANSWER.UPDATED_AT, now)
                    .where(ANSWER.ID.eq(target))
                    .execute()
            "comment" ->
                db
                    .update(CONTENT_COMMENT)
                    .set(CONTENT_COMMENT.HIDDEN, hidden)
                    .set(CONTENT_COMMENT.VERSION, before.version + 1)
                    .set(CONTENT_COMMENT.UPDATED_AT, now)
                    .where(CONTENT_COMMENT.ID.eq(target))
                    .execute()
        }
        // Clearing acceptance is part of the same transaction, with its own before/after audit.
        val question = questions.question(parent, true)
        if (hidden && ((kind == "answer" && question.acceptedAnswerId == target) || kind == "question") &&
            question.acceptedAnswerId != null
        ) {
            db
                .update(QUESTION)
                .set(QUESTION.ACCEPTED_ANSWER_ID, null as UUID?)
                .set(QUESTION.VERSION, question.version + 1)
                .set(QUESTION.UPDATED_AT, now)
                .where(QUESTION.ID.eq(parent))
                .execute()
            audit(actor, "accept", "question", parent, parent, state(question), state(questions.question(parent, true)))
        }
        val after = snapshot(kind, target)
        audit(actor, if (hidden) "hide" else "restore", kind, target, parent, before, after)
        return after
    }

    private fun comment(
        target: UUID,
        moderation: Boolean = false,
    ): CommentView {
        val row =
            db
                .select(*CONTENT_COMMENT.fields(), APP_USER.DISPLAY_NAME)
                .from(CONTENT_COMMENT)
                .join(APP_USER)
                .on(CONTENT_COMMENT.AUTHOR_ID.eq(APP_USER.ID))
                .join(QUESTION)
                .on(CONTENT_COMMENT.QUESTION_ID.eq(QUESTION.ID))
                .leftJoin(ANSWER)
                .on(CONTENT_COMMENT.ANSWER_ID.eq(ANSWER.ID))
                .where(CONTENT_COMMENT.ID.eq(target))
                .and(
                    if (moderation) {
                        DSL.trueCondition()
                    } else {
                        CONTENT_COMMENT.HIDDEN.isFalse
                            .and(QUESTION.HIDDEN.isFalse)
                            .and(CONTENT_COMMENT.ANSWER_ID.isNull.or(ANSWER.HIDDEN.isFalse))
                    },
                ).fetchOne() ?: missing()
        return comment(row)
    }

    private fun comment(row: Record) =
        CommentView(
            row[CONTENT_COMMENT.ID]!!,
            row[CONTENT_COMMENT.QUESTION_ID]!!,
            row[CONTENT_COMMENT.ANSWER_ID],
            row[CONTENT_COMMENT.BODY]!!,
            policy.html(row[CONTENT_COMMENT.BODY]!!),
            Author(row[CONTENT_COMMENT.AUTHOR_ID]!!, row[APP_USER.DISPLAY_NAME]!!),
            row[CONTENT_COMMENT.CHANNEL]!!,
            row[CONTENT_COMMENT.CREATED_AT]!!,
            row[CONTENT_COMMENT.UPDATED_AT]!!,
            row[CONTENT_COMMENT.VERSION]!!,
            row[CONTENT_COMMENT.HIDDEN]!!,
        )

    private fun hash(value: Any) =
        MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(value)).joinToString("") {
            "%02x".format(it)
        }

    private fun state(value: QuestionView) =
        AuditState(
            value.version,
            value.hidden,
            value.acceptedAnswerId,
            hash(listOf(value.title, value.body, value.tags, value.context)),
            ContentFingerprints(
                hash(value.body),
                hash(value.title),
                hash(value.tags),
                value.context.errorText?.let(::hash),
                value.context.dependencies?.let(::hash),
                value.context.versions?.let(::hash),
                value.context.repository?.let(::hash),
            ),
        )

    private fun state(value: AnswerView) =
        AuditState(
            value.version,
            value.hidden,
            null,
            hash(listOf(value.body, value.applicability, value.verification)),
            ContentFingerprints(
                hash(value.body),
                applicability = value.applicability?.let(::hash),
                verification = value.verification?.let(::hash),
            ),
        )

    private fun state(value: CommentView) =
        AuditState(value.version, value.hidden, null, hash(value.body), ContentFingerprints(hash(value.body)))

    private fun snapshot(
        kind: String,
        target: UUID,
    ) = when (kind) {
        "question" -> state(questions.question(target, true))
        "answer" -> state(questions.answerById(target, true))
        "comment" -> state(comment(target, true))
        else -> invalid("Invalid content kind")
    }

    private fun audit(
        actor: Identity,
        action: String,
        kind: String,
        target: UUID,
        parent: UUID,
        before: AuditState,
        after: AuditState,
    ) {
        db
            .insertInto(CONTENT_AUDIT)
            .set(CONTENT_AUDIT.ID, UUID.randomUUID())
            .set(CONTENT_AUDIT.ACTOR_ID, actor.id)
            .set(CONTENT_AUDIT.CHANNEL, actor.channel)
            .set(CONTENT_AUDIT.ACTION, action)
            .set(CONTENT_AUDIT.TARGET_KIND, kind)
            .set(CONTENT_AUDIT.TARGET_ID, target)
            .set(CONTENT_AUDIT.QUESTION_ID, parent)
            .set(CONTENT_AUDIT.BEFORE_STATE, json.writeValueAsString(before))
            .set(CONTENT_AUDIT.AFTER_STATE, json.writeValueAsString(after))
            .execute()
    }
}
