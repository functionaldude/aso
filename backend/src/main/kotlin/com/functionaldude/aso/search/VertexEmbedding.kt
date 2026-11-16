package com.functionaldude.aso.search

import com.fasterxml.jackson.annotation.JsonProperty
import com.google.auth.oauth2.GoogleCredentials
import org.springframework.stereotype.Component

fun interface EmbeddingAuthorization {
    fun token(): String
}

@Component
class VertexAuthorization : EmbeddingAuthorization {
    private val credentials by lazy {
        GoogleCredentials.getApplicationDefault().createScoped("https://www.googleapis.com/auth/cloud-platform")
    }

    override fun token(): String {
        credentials.refreshIfExpired()
        return checkNotNull(credentials.accessToken).tokenValue
    }
}

data class VertexEmbeddingInstance(
    val content: String,
    @get:JsonProperty("task_type") val taskType: String,
)

data class VertexEmbeddingParameters(
    val outputDimensionality: Int,
    val autoTruncate: Boolean = false,
)

data class VertexEmbeddingRequest(
    val instances: List<VertexEmbeddingInstance>,
    val parameters: VertexEmbeddingParameters,
)

data class VertexEmbeddingStatistics(
    val truncated: Boolean,
)

data class VertexEmbeddingValues(
    val values: List<Double>,
    val statistics: VertexEmbeddingStatistics,
)

data class VertexEmbeddingPrediction(
    val embeddings: VertexEmbeddingValues,
)

data class VertexEmbeddingResponse(
    val predictions: List<VertexEmbeddingPrediction>,
)
