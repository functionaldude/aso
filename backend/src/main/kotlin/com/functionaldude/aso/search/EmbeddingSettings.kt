package com.functionaldude.aso.search

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import java.net.InetAddress
import java.net.URI
import java.security.MessageDigest
import java.time.Duration

@Configuration
@EnableConfigurationProperties(EmbeddingSettings::class)
@EnableScheduling
class SearchConfiguration

@ConfigurationProperties("aso.embedding")
class EmbeddingSettings(
    val enabled: Boolean = false,
    val provider: String = "local",
    val vertexProject: String = "",
    val vertexLocation: String = "europe-west1",
    val baseUrl: String = "http://127.0.0.1:1234/v1",
    val allowedHosts: List<String> = listOf("127.0.0.1", "localhost", "::1"),
    val model: String = "text-embedding-qwen3-embedding-0.6b",
    val dimensions: Int = 1024,
    val apiKey: String = "",
    val preparationVersion: String = "qwen3-retrieval-v1",
    val queryPrefix: String = "Instruct: Given a technical problem, retrieve related engineering questions and solutions.\nQuery: ",
    val querySuffix: String = "",
    val documentPrefix: String = "",
    val documentSuffix: String = "",
    val maxInputCharacters: Int = 4000,
    val maxQueryCharacters: Int = 1000,
    val maxDocumentChunks: Int = 64,
    val batchSize: Int = 8,
    val maxResponseBytes: Int = 4_000_000,
    val connectTimeout: Duration = Duration.ofMillis(300),
    val requestTimeout: Duration = Duration.ofMillis(1200),
    val indexingTimeout: Duration = Duration.ofSeconds(30),
    val workerBatchSize: Int = 10,
    val retryDelay: Duration = Duration.ofSeconds(5),
    val minSimilarity: Double = 0.45,
) {
    init {
        require(provider in setOf("local", "vertex")) { "Embedding provider must be local or vertex" }
        if (provider == "vertex") {
            require(vertexProject.matches(Regex("[a-z][a-z0-9-]{4,61}[a-z0-9]"))) { "Vertex requires an explicit project ID" }
            require(vertexLocation.matches(Regex("[a-z]+-[a-z]+[0-9]+"))) { "Vertex requires a regional location" }
            require(model.matches(Regex("[a-zA-Z0-9@._-]+")))
            require(batchSize == 1) { "Vertex gemini-embedding requests support one input at a time" }
        }
        require(dimensions in 1..16000 && model.isNotBlank() && preparationVersion.isNotBlank())
        require(maxInputCharacters in 128..100000 && maxQueryCharacters in 1..1000 && maxDocumentChunks in 1..1024)
        require(batchSize in 1..64 && maxResponseBytes in 1024..64_000_000 && workerBatchSize in 1..100)
        require(minSimilarity in -1.0..1.0)
        listOf(connectTimeout, requestTimeout, indexingTimeout, retryDelay).forEach { require(!it.isNegative && !it.isZero) }
        require(queryPrefix.length + querySuffix.length + maxQueryCharacters <= maxInputCharacters)
        require(documentPrefix.length + documentSuffix.length < maxInputCharacters)
        val uri = URI(baseUrl)
        require(uri.scheme in setOf("http", "https") && uri.host in allowedHosts) {
            "Embedding endpoint must use an explicitly allowed internal host"
        }
        require(uri.userInfo == null && uri.query == null && uri.fragment == null && !baseUrl.endsWith('/'))
    }

    val profile: String
        get() =
            listOf(
                if (provider == "vertex") "vertex:$vertexProject/$vertexLocation" else baseUrl,
                model,
                dimensions,
                preparationVersion,
                queryPrefix,
                querySuffix,
                documentPrefix,
                documentSuffix,
                maxInputCharacters,
                maxDocumentChunks,
            ).joinToString("\u001f")
    val generation: String
        get() = MessageDigest.getInstance("SHA-256").digest(profile.toByteArray()).joinToString("") { "%02x".format(it) }

    fun endpoint(): URI {
        if (provider == "vertex") {
            return URI(
                "https://$vertexLocation-aiplatform.googleapis.com/v1/projects/$vertexProject/locations/$vertexLocation/" +
                    "publishers/google/models/$model:predict",
            )
        }
        val uri = URI("$baseUrl/embeddings")
        require(InetAddress.getAllByName(uri.host).all { it.isLoopbackAddress || it.isSiteLocalAddress || it.isLinkLocalAddress }) {
            "Embedding endpoint must resolve only to local infrastructure"
        }
        return uri
    }
}
