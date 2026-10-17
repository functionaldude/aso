package com.functionaldude.aso.search

import com.functionaldude.aso.jooq.tables.references.QUESTION
import com.functionaldude.aso.jooq.tables.references.SEARCH_DOCUMENT
import com.functionaldude.aso.jooq.tables.references.SEARCH_GENERATION
import com.functionaldude.aso.jooq.tables.references.SEARCH_WORK
import org.jooq.DSLContext
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.OffsetDateTime
import java.util.UUID

data class IndexStatus(
    val configuredGeneration: String,
    val activeGeneration: String?,
    val pending: Int,
    val failed: Int,
    val compatible: Boolean,
)

data class IndexSource(
    val questionId: UUID,
    val answerId: UUID?,
    val text: String,
)

data class IndexClaim(
    val questionId: UUID,
    val revision: Long,
    val lease: OffsetDateTime,
    val attempts: Int,
)

@Service
class SearchIndex(
    private val db: DSLContext,
    private val settings: EmbeddingSettings,
    private val embeddings: EmbeddingClient,
    private val transaction: TransactionTemplate,
) {
    fun status(): IndexStatus {
        val active =
            db
                .select(SEARCH_GENERATION.ID)
                .from(SEARCH_GENERATION)
                .where(SEARCH_GENERATION.ACTIVE.isTrue)
                .fetchOne(SEARCH_GENERATION.ID)
        return IndexStatus(
            settings.generation,
            active,
            db.fetchCount(SEARCH_WORK),
            db.fetchCount(SEARCH_WORK, SEARCH_WORK.FAILURE.isNotNull),
            active == settings.generation,
        )
    }

    fun rebuild(): IndexStatus {
        transaction.executeWithoutResult {
            db.execute("select pg_advisory_xact_lock(500005)")
            db.update(SEARCH_GENERATION).set(SEARCH_GENERATION.ACTIVE, false).execute()
            db.deleteFrom(SEARCH_GENERATION).where(SEARCH_GENERATION.ID.eq(settings.generation)).execute()
            db
                .insertInto(SEARCH_GENERATION)
                .set(SEARCH_GENERATION.ID, settings.generation)
                .set(SEARCH_GENERATION.PROFILE, settings.profile)
                .set(SEARCH_GENERATION.DIMENSIONS, settings.dimensions)
                .set(SEARCH_GENERATION.ACTIVE, true)
                .execute()
            db.execute(
                """
                insert into search_work (question_id, revision) select id, search_revision from question
                on conflict (question_id) do update
                set revision = greatest(search_work.revision, excluded.revision), attempts = 0,
                    next_attempt_at = current_timestamp, lease_until = null, failure = null
                """.trimIndent(),
            )
            db.deleteFrom(SEARCH_GENERATION).where(SEARCH_GENERATION.ACTIVE.isFalse).execute()
        }
        return status()
    }

    fun retry(): IndexStatus {
        db
            .update(SEARCH_WORK)
            .set(SEARCH_WORK.NEXT_ATTEMPT_AT, OffsetDateTime.now())
            .set(SEARCH_WORK.ATTEMPTS, 0)
            .set(SEARCH_WORK.FAILURE, null as String?)
            .set(SEARCH_WORK.LEASE_UNTIL, null as OffsetDateTime?)
            .execute()
        return status()
    }

    @Scheduled(fixedDelayString = "\${aso.embedding.poll-millis:1000}")
    fun tick() {
        if (!settings.enabled) return
        transaction.executeWithoutResult {
            db.execute("select pg_advisory_xact_lock(500005)")
            if (db.fetchCount(SEARCH_GENERATION) == 0) rebuild()
        }
        if (!status().compatible) return
        repeat(settings.workerBatchSize) {
            val claim = claim() ?: return
            try {
                val revision =
                    db
                        .select(QUESTION.SEARCH_REVISION)
                        .from(QUESTION)
                        .where(QUESTION.ID.eq(claim.questionId))
                        .fetchOne(QUESTION.SEARCH_REVISION)
                val sources = sources(claim.questionId)
                val capacity = settings.maxInputCharacters - settings.documentPrefix.length - settings.documentSuffix.length
                val chunks =
                    sources
                        .flatMap { source ->
                            source.text.chunked(capacity).map { source.copy(text = it) }
                        }.take(settings.maxDocumentChunks)
                val vectors = embeddings.documents(chunks.map { it.text })
                transaction.executeWithoutResult {
                    val current =
                        db
                            .select(QUESTION.SEARCH_REVISION)
                            .from(QUESTION)
                            .where(QUESTION.ID.eq(claim.questionId))
                            .forShare()
                            .fetchOne(QUESTION.SEARCH_REVISION)
                    db.execute("select pg_advisory_xact_lock_shared(500005)")
                    val owned = owned(claim)
                    val stale = current != revision || current != claim.revision
                    if (stale || !db.fetchExists(SEARCH_WORK, owned) || !status().compatible) return@executeWithoutResult
                    db
                        .deleteFrom(SEARCH_DOCUMENT)
                        .where(SEARCH_DOCUMENT.GENERATION_ID.eq(settings.generation))
                        .and(SEARCH_DOCUMENT.QUESTION_ID.eq(claim.questionId))
                        .execute()
                    chunks.forEachIndexed { ordinal, source ->
                        db
                            .insertInto(SEARCH_DOCUMENT)
                            .set(SEARCH_DOCUMENT.GENERATION_ID, settings.generation)
                            .set(SEARCH_DOCUMENT.QUESTION_ID, claim.questionId)
                            .set(SEARCH_DOCUMENT.ORDINAL, ordinal)
                            .set(SEARCH_DOCUMENT.ANSWER_ID, source.answerId)
                            .set(SEARCH_DOCUMENT.REVISION, claim.revision)
                            .set(SEARCH_DOCUMENT.EMBEDDING, vectors[ordinal].toTypedArray())
                            .execute()
                    }
                    db.deleteFrom(SEARCH_WORK).where(owned).execute()
                }
            } catch (_: Exception) {
                val delay = settings.retryDelay.multipliedBy(1L shl claim.attempts.coerceAtMost(6))
                db
                    .update(SEARCH_WORK)
                    .set(SEARCH_WORK.LEASE_UNTIL, null as OffsetDateTime?)
                    .set(SEARCH_WORK.FAILURE, "embedding_unavailable")
                    .set(SEARCH_WORK.NEXT_ATTEMPT_AT, OffsetDateTime.now().plus(delay))
                    .where(owned(claim))
                    .execute()
            }
        }
    }

    private fun owned(claim: IndexClaim) =
        SEARCH_WORK.QUESTION_ID
            .eq(claim.questionId)
            .and(SEARCH_WORK.REVISION.eq(claim.revision))
            .and(SEARCH_WORK.LEASE_UNTIL.eq(claim.lease))

    private fun claim(): IndexClaim? =
        transaction.execute {
            val now = OffsetDateTime.now()
            val row =
                db
                    .selectFrom(SEARCH_WORK)
                    .where(SEARCH_WORK.NEXT_ATTEMPT_AT.le(now))
                    .and(SEARCH_WORK.LEASE_UNTIL.isNull.or(SEARCH_WORK.LEASE_UNTIL.lt(now)))
                    .orderBy(SEARCH_WORK.NEXT_ATTEMPT_AT, SEARCH_WORK.QUESTION_ID)
                    .limit(1)
                    .forUpdate()
                    .skipLocked()
                    .fetchOne() ?: return@execute null
            val batches = (settings.maxDocumentChunks + settings.batchSize - 1) / settings.batchSize
            val lease = now.plus(settings.indexingTimeout.multipliedBy(batches.toLong())).plusSeconds(10)
            db
                .update(SEARCH_WORK)
                .set(SEARCH_WORK.LEASE_UNTIL, lease)
                .set(SEARCH_WORK.ATTEMPTS, row.attempts!! + 1)
                .where(SEARCH_WORK.QUESTION_ID.eq(row.questionId))
                .execute()
            IndexClaim(row.questionId!!, row.revision!!, lease, row.attempts!!)
        }

    fun sources(id: UUID): List<IndexSource> =
        db
            .resultQuery(
                """
                select q.id, null::uuid as answer_id,
                  concat_ws(E'\n', q.title, q.body, q.error_text, q.dependencies, q.versions, q.repository,
                    (select string_agg(tag_name, ' ' order by tag_name) from question_tag where question_id=q.id)) as text
                from question q where q.id = ? and not q.hidden
                union all
                select q.id, a.id, concat_ws(E'\n', q.title, a.body, a.applicability, a.verification)
                from question q join answer a on a.question_id=q.id where q.id = ? and not q.hidden and not a.hidden
                order by answer_id nulls first
                """.trimIndent(),
                id,
                id,
            ).fetch {
                IndexSource(it.get("id", UUID::class.java)!!, it.get("answer_id", UUID::class.java), it.get("text", String::class.java)!!)
            }
}
