package com.functionaldude.aso.search

import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.io.ByteArrayOutputStream
import java.net.http.HttpRequest
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Flow
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

class VertexEmbeddingTest {
    private val settings =
        EmbeddingSettings(
            enabled = true,
            provider = "vertex",
            vertexProject = "agentic-stack-overfl-mka5b8",
            model = "gemini-embedding-001",
            dimensions = 768,
            batchSize = 1,
            queryPrefix = "",
        )
    private val client = EmbeddingClient(settings, EmbeddingAuthorization { "fixture-access-token" })
    private val json = JsonMapper.builder().build()

    @Test
    fun `Vertex uses project scoped IAM requests and retrieval task types`() {
        val query = client.request(listOf("database timeout"), false)
        assertEquals(
            "https://europe-west1-aiplatform.googleapis.com/v1/projects/agentic-stack-overfl-mka5b8/" +
                "locations/europe-west1/publishers/google/models/gemini-embedding-001:predict",
            query.uri().toString(),
        )
        assertEquals("Bearer fixture-access-token", query.headers().firstValue("Authorization").orElseThrow())
        val body = json.readTree(body(query))
        assertEquals("RETRIEVAL_QUERY", body["instances"][0]["task_type"].asString())
        assertEquals("database timeout", body["instances"][0]["content"].asString())
        assertEquals(768, body["parameters"]["outputDimensionality"].asInt())
        assertFalse(body["parameters"]["autoTruncate"].asBoolean())
        val document = json.readTree(body(client.request(listOf("restart the connection pool"), true)))
        assertEquals("RETRIEVAL_DOCUMENT", document["instances"][0]["task_type"].asString())
    }

    @Test
    fun `Vertex parses predictions and refuses truncated or missing outputs`() {
        val response =
            """{"predictions":[{"embeddings":{"statistics":{"truncated":false,"token_count":2},"values":[0.1,0.2,0.3]}}]}"""
        assertEquals(listOf(listOf(0.1, 0.2, 0.3)), client.vectors(response.toByteArray(), 1))
        assertFailsWith<EmbeddingUnavailable> { client.vectors(response.replace("false", "true").toByteArray(), 1) }
        assertFailsWith<EmbeddingUnavailable> { client.vectors("""{"predictions":[]}""".toByteArray(), 1) }
    }

    @Test
    fun `Vertex is explicit and has a separate index generation`() {
        assertFailsWith<IllegalArgumentException> { EmbeddingSettings(provider = "vertex", batchSize = 1) }
        assertFailsWith<IllegalArgumentException> { EmbeddingSettings(provider = "arbitrary-cloud") }
        assertNotEquals(EmbeddingSettings().generation, settings.generation)
    }

    private fun body(request: HttpRequest): ByteArray {
        val result = CompletableFuture<ByteArray>()
        val bytes = ByteArrayOutputStream()
        request.bodyPublisher().orElseThrow().subscribe(
            object : Flow.Subscriber<ByteBuffer> {
                override fun onSubscribe(subscription: Flow.Subscription) = subscription.request(Long.MAX_VALUE)

                override fun onNext(item: ByteBuffer) {
                    val chunk = ByteArray(item.remaining())
                    item.get(chunk)
                    bytes.write(chunk)
                }

                override fun onError(throwable: Throwable) {
                    result.completeExceptionally(throwable)
                }

                override fun onComplete() {
                    result.complete(bytes.toByteArray())
                }
            },
        )
        return result.get(1, TimeUnit.SECONDS)
    }
}
