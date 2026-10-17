package com.functionaldude.aso.search

import org.springframework.stereotype.Component
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.math.sqrt

data class EmbeddingRequest(
    val model: String,
    val input: List<String>,
)

data class EmbeddingRow(
    val index: Int,
    val embedding: List<Double>,
)

data class EmbeddingResponse(
    val model: String,
    val data: List<EmbeddingRow>,
)

class EmbeddingUnavailable : RuntimeException("Local embeddings unavailable or incompatible")

@Component
class EmbeddingClient(
    private val settings: EmbeddingSettings,
) {
    private val json = JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build()
    private val http =
        HttpClient
            .newBuilder()
            .connectTimeout(settings.connectTimeout)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()

    fun query(text: String): List<Double> = embed(listOf(settings.queryPrefix + text + settings.querySuffix), false).single()

    fun documents(texts: List<String>): List<List<Double>> =
        texts.chunked(settings.batchSize).flatMap { batch ->
            embed(batch.map { settings.documentPrefix + it + settings.documentSuffix }, true)
        }

    private fun embed(
        inputs: List<String>,
        indexing: Boolean,
    ): List<List<Double>> {
        try {
            if (!settings.enabled || inputs.any { it.length > settings.maxInputCharacters }) throw EmbeddingUnavailable()
            val builder =
                HttpRequest
                    .newBuilder(settings.endpoint())
                    .timeout(if (indexing) settings.indexingTimeout else settings.requestTimeout)
                    .header("Content-Type", "application/json")
            if (settings.apiKey.isNotEmpty()) builder.header("Authorization", "Bearer ${settings.apiKey}")
            val request =
                builder
                    .POST(
                        HttpRequest.BodyPublishers.ofString(json.writeValueAsString(EmbeddingRequest(settings.model, inputs))),
                    ).build()
            val response =
                http.send(
                    request,
                    HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofByteArray(), settings.maxResponseBytes.toLong()),
                )
            val body = response.body()
            if (response.statusCode() != 200 || body.size > settings.maxResponseBytes) throw EmbeddingUnavailable()
            val result = json.readValue(body, EmbeddingResponse::class.java)
            if (result.model != settings.model || result.data.size != inputs.size ||
                result.data.map { it.index }.sorted() != inputs.indices.toList()
            ) {
                throw EmbeddingUnavailable()
            }
            return result.data.sortedBy { it.index }.map { row ->
                if (row.embedding.size != settings.dimensions || row.embedding.any { !it.isFinite() }) throw EmbeddingUnavailable()
                val norm = sqrt(row.embedding.sumOf { it * it })
                if (!norm.isFinite() || norm <= 0.0) throw EmbeddingUnavailable()
                row.embedding.map { it / norm }
            }
        } catch (_: Exception) {
            throw EmbeddingUnavailable()
        }
    }
}
