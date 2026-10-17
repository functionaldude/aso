package com.functionaldude.aso.questions

import com.functionaldude.aso.identity.IdentityService
import com.functionaldude.aso.jooq.tables.references.ANSWER
import com.functionaldude.aso.jooq.tables.references.APP_USER
import com.functionaldude.aso.jooq.tables.references.CONTENT_VOTE
import com.functionaldude.aso.jooq.tables.references.OCCURRENCE
import org.jooq.DSLContext
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.util.Base64
import java.util.UUID

data class VoteInput(
    val active: Boolean? = null,
    val verification: String? = null,
)

data class VoteArguments(
    val kind: String? = null,
    val targetId: String? = null,
    val active: Boolean? = null,
    val verification: String? = null,
)

data class ListVotesArguments(
    val kind: String? = null,
    val targetId: String? = null,
    val limit: Int = 20,
    val cursor: String? = null,
)

data class OccurrenceInput(
    val eventId: String? = null,
)

data class OccurrenceArguments(
    val questionId: String? = null,
    val eventId: String? = null,
)

data class VoteEvidence(
    val id: UUID,
    val voter: Author,
    val channel: String,
    val createdAt: OffsetDateTime,
    val callerReportedVerification: String?,
)

data class OccurrenceView(
    val eventId: UUID,
    val questionId: UUID,
    val reporterId: UUID,
    val channel: String,
    val createdAt: OffsetDateTime,
)

@Service
class EngagementService(
    private val db: DSLContext,
    private val identities: IdentityService,
    private val policy: ContentPolicy,
    private val questions: QuestionService,
    private val reads: EngagementReads,
) {
    private data class Target(
        val question: UUID,
        val answer: UUID?,
        val author: UUID,
    )

    private fun target(
        kind: String,
        id: String,
    ): Target {
        val uuid = policy.uuid(id, "targetId")
        val parent =
            when (kind) {
                "question" -> uuid
                "answer" ->
                    db
                        .select(ANSWER.QUESTION_ID)
                        .from(ANSWER)
                        .where(ANSWER.ID.eq(uuid))
                        .fetchOne(ANSWER.QUESTION_ID)
                        ?: throw ContentFailure(404, "not_found", "Answer not found")
                else -> invalid("kind must be question or answer")
            }
        questions.lockQuestion(parent)
        val question = questions.question(parent)
        val answerId = if (kind == "answer") uuid else null
        val author = if (answerId != null) questions.answerById(answerId).author.id else question.author.id
        return Target(parent, answerId, author)
    }

    @Transactional
    fun vote(
        authentication: Authentication,
        kind: String,
        id: String,
        input: VoteInput,
    ): VoteSummary {
        val actor = identities.current(authentication)
        val active = input.active ?: invalid("active is required")
        val verification = policy.text(input.verification, "verification", 500, active && kind == "answer" && actor.channel == "mcp")
        val target = target(kind, id)
        if (active && target.author == actor.id) {
            throw ContentFailure(403, "self_vote", "You cannot upvote your own content, including through another client")
        }
        val condition = reads.voteTarget(target.question, target.answer).and(CONTENT_VOTE.ACTOR_ID.eq(actor.id))
        if (active) {
            db
                .insertInto(CONTENT_VOTE)
                .set(CONTENT_VOTE.ID, UUID.randomUUID())
                .set(CONTENT_VOTE.QUESTION_ID, target.question)
                .set(CONTENT_VOTE.ANSWER_ID, target.answer)
                .set(CONTENT_VOTE.ACTOR_ID, actor.id)
                .set(CONTENT_VOTE.CHANNEL, actor.channel)
                .set(CONTENT_VOTE.VERIFICATION, verification)
                .set(CONTENT_VOTE.CREATED_AT, reads.now())
                .onConflictDoNothing()
                .execute()
        } else {
            db.deleteFrom(CONTENT_VOTE).where(condition).execute()
        }
        return reads.votes(target.question, target.answer, actor.id)
    }

    @Transactional
    fun votes(
        authentication: Authentication,
        kind: String,
        id: String,
        limit: Int = 20,
        cursor: String? = null,
    ): ContentPage<VoteEvidence> {
        identities.current(authentication)
        if (limit !in 1..50) invalid("limit must be between 1 and 50")
        val target = target(kind, id)
        val scope = "votes:$kind:$id"
        var condition = reads.voteTarget(target.question, target.answer)
        if (cursor != null) {
            val parts =
                try {
                    if (cursor.length > 500) invalid("Invalid cursor")
                    String(Base64.getUrlDecoder().decode(cursor)).split('|')
                } catch (_: IllegalArgumentException) {
                    invalid("Invalid cursor")
                }
            if (parts.size != 3 || parts[2] != scope) invalid("Cursor does not match the collection")
            val time =
                try {
                    OffsetDateTime.parse(parts[0])
                } catch (_: DateTimeParseException) {
                    invalid("Invalid cursor")
                }
            val after = policy.uuid(parts[1], "cursor")
            condition = condition.and(CONTENT_VOTE.CREATED_AT.gt(time).or(CONTENT_VOTE.CREATED_AT.eq(time).and(CONTENT_VOTE.ID.gt(after))))
        }
        val rows =
            db
                .select(*CONTENT_VOTE.fields(), APP_USER.DISPLAY_NAME)
                .from(CONTENT_VOTE)
                .join(APP_USER)
                .on(CONTENT_VOTE.ACTOR_ID.eq(APP_USER.ID))
                .where(condition)
                .orderBy(CONTENT_VOTE.CREATED_AT.asc(), CONTENT_VOTE.ID.asc())
                .limit(limit + 1)
                .fetch()
        val items =
            rows.take(limit).map {
                VoteEvidence(
                    it[CONTENT_VOTE.ID]!!,
                    Author(it[CONTENT_VOTE.ACTOR_ID]!!, it[APP_USER.DISPLAY_NAME]!!),
                    it[CONTENT_VOTE.CHANNEL]!!,
                    it[CONTENT_VOTE.CREATED_AT]!!,
                    it[CONTENT_VOTE.VERIFICATION],
                )
            }
        val next =
            if (rows.size > limit) {
                items.last().let {
                    Base64.getUrlEncoder().withoutPadding().encodeToString("${it.createdAt}|${it.id}|$scope".toByteArray())
                }
            } else {
                null
            }
        return ContentPage(items, next)
    }

    @Transactional
    fun occurrence(
        authentication: Authentication,
        questionId: String,
        input: OccurrenceInput,
    ): OccurrenceView {
        val actor = identities.current(authentication)
        val question = policy.uuid(questionId, "questionId")
        val event = policy.uuid(input.eventId ?: invalid("eventId is required"), "eventId")

        // Check identity before parent visibility, so changed/missing parents cannot disguise ID conflicts.
        fun previous() =
            db
                .selectFrom(OCCURRENCE)
                .where(OCCURRENCE.REPORTER_ID.eq(actor.id))
                .and(OCCURRENCE.EVENT_ID.eq(event))
                .fetchOne()

        fun conflict(): Unit =
            throw ContentFailure(
                409,
                "event_conflict",
                "eventId was already used for another question; replay the original event or use a new ID only for an independent encounter",
            )
        val existing = previous()
        if (existing != null && existing.questionId != question) conflict()
        questions.lockQuestion(question)
        questions.question(question)
        db
            .insertInto(OCCURRENCE)
            .set(OCCURRENCE.REPORTER_ID, actor.id)
            .set(OCCURRENCE.EVENT_ID, event)
            .set(OCCURRENCE.QUESTION_ID, question)
            .set(OCCURRENCE.CHANNEL, actor.channel)
            .set(OCCURRENCE.CREATED_AT, reads.now())
            .onConflictDoNothing()
            .execute()
        val saved = previous()!!
        if (saved.questionId != question) conflict()
        return OccurrenceView(saved.eventId!!, saved.questionId!!, saved.reporterId!!, saved.channel!!, saved.createdAt!!)
    }
}
