package com.functionaldude.aso.questions

import com.functionaldude.aso.jooq.tables.references.CONTENT_VOTE
import com.functionaldude.aso.jooq.tables.references.OCCURRENCE
import com.functionaldude.aso.jooq.tables.references.QUESTION
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

@Configuration
class EngagementTime {
    @Bean
    fun engagementClock(): Clock = Clock.systemUTC()
}

data class VoteSummary(
    val upvotes: Int,
    val votedByYou: Boolean,
)

data class EncounterCounts(
    val encounters: Int,
    val distinctDevelopers: Int,
)

data class QuestionMetrics(
    val allTime: EncounterCounts,
    val sevenDay: EncounterCounts,
)

/** Derived from committed events; no mutable counters or daily per-user caps. */
@Component
class EngagementReads(
    private val db: DSLContext,
    private val clock: Clock,
) {
    fun now(): OffsetDateTime = OffsetDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC)

    fun voteTarget(
        question: UUID,
        answer: UUID?,
    ) = CONTENT_VOTE.QUESTION_ID.eq(question).and(if (answer == null) CONTENT_VOTE.ANSWER_ID.isNull else CONTENT_VOTE.ANSWER_ID.eq(answer))

    fun votes(
        question: UUID,
        answer: UUID?,
        viewer: UUID?,
    ): VoteSummary {
        val mine = if (viewer == null) DSL.falseCondition() else CONTENT_VOTE.ACTOR_ID.eq(viewer)
        val row =
            db
                .select(DSL.count(), DSL.count().filterWhere(mine))
                .from(CONTENT_VOTE)
                .where(voteTarget(question, answer))
                .fetchOne()!!
        return VoteSummary(row.value1(), row.value2() > 0)
    }

    fun metrics(
        question: UUID,
        asOf: OffsetDateTime,
    ): QuestionMetrics {
        val sevenDay = OCCURRENCE.CREATED_AT.ge(asOf.minusDays(7))
        val row =
            db
                .select(
                    DSL.count(),
                    DSL.countDistinct(OCCURRENCE.REPORTER_ID),
                    DSL.count().filterWhere(sevenDay),
                    DSL.countDistinct(OCCURRENCE.REPORTER_ID).filterWhere(sevenDay),
                ).from(OCCURRENCE)
                .where(OCCURRENCE.QUESTION_ID.eq(question))
                .and(OCCURRENCE.CREATED_AT.le(asOf))
                .fetchOne()!!
        return QuestionMetrics(EncounterCounts(row.value1(), row.value2()), EncounterCounts(row.value3(), row.value4()))
    }

    fun window(asOf: OffsetDateTime) =
        OCCURRENCE.QUESTION_ID
            .eq(QUESTION.ID)
            .and(OCCURRENCE.CREATED_AT.ge(asOf.minusDays(7)))
            .and(OCCURRENCE.CREATED_AT.le(asOf))

    fun reporters(asOf: OffsetDateTime) =
        db
            .select(DSL.countDistinct(OCCURRENCE.REPORTER_ID))
            .from(OCCURRENCE)
            .where(window(asOf))
            .asField<Int>()

    fun encounters(asOf: OffsetDateTime) =
        db
            .select(DSL.count())
            .from(OCCURRENCE)
            .where(window(asOf))
            .asField<Int>()

    fun latest(asOf: OffsetDateTime) =
        db
            .select(DSL.max(OCCURRENCE.CREATED_AT))
            .from(OCCURRENCE)
            .where(window(asOf))
            .asField<OffsetDateTime>()
}
