package com.functionaldude.aso.search

import com.sun.net.httpserver.HttpServer
import tools.jackson.databind.json.JsonMapper
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

class EmbeddingFixture : AutoCloseable {
    private val json = JsonMapper.builder().build()
    private val executor = Executors.newCachedThreadPool()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    @Volatile var failure: Boolean = false

    @Volatile var dimensions: Int = 3

    @Volatile var malformed: Boolean = false

    @Volatile var delayMillis: Long = 0
    val inputs = CopyOnWriteArrayList<String>()
    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}/v1"

    init {
        server.executor = executor
        server.createContext("/v1/embeddings") { exchange ->
            exchange.use {
                val request = json.readTree(exchange.requestBody.readAllBytes())
                val texts = request["input"].asSequence().map { it.asString() }.toList()
                inputs.addAll(texts)
                val data =
                    texts.mapIndexed { index, text ->
                        val water = listOf("water", "aqua", "hydration", "thirst").any { it in text.lowercase() }
                        val values = List(dimensions) { component -> if (component == (if (water) 0 else 1)) 1.0 else 0.0 }
                        mapOf("index" to (if (malformed) 0 else index), "embedding" to values)
                    }
                val bytes = json.writeValueAsBytes(mapOf("model" to request["model"].asString(), "data" to data))
                if (delayMillis > 0) Thread.sleep(delayMillis)
                exchange.sendResponseHeaders(if (failure) 503 else 200, bytes.size.toLong())
                exchange.responseBody.write(bytes)
            }
        }
        server.start()
    }

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
    }
}
