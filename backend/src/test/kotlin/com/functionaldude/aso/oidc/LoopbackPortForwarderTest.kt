package com.functionaldude.aso.oidc

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LoopbackPortForwarderTest {
    @Test
    fun `concurrent HTTP requests preserve the loopback host path query and body`() {
        val backend = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        backend.createContext("/") { exchange ->
            val body = exchange.requestBody.readAllBytes().decodeToString()
            val response =
                "${exchange.requestHeaders.getFirst(
                    "Host",
                )} ${exchange.requestMethod} ${exchange.requestURI} $body".toByteArray()
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
            exchange.close()
        }
        backend.start()
        try {
            LoopbackPortForwarder("127.0.0.1", backend.address.port).use { forwarder ->
                HttpClient.newHttpClient().use { http ->
                    val body = "payload-".repeat(16384)
                    val requests =
                        (1..4).map { index ->
                            http.sendAsync(
                                HttpRequest
                                    .newBuilder(URI("${forwarder.baseUrl}/auth%20path?request=$index"))
                                    .timeout(Duration.ofSeconds(5))
                                    .POST(HttpRequest.BodyPublishers.ofString(body))
                                    .build(),
                                HttpResponse.BodyHandlers.ofString(),
                            )
                        }
                    requests.forEachIndexed { index, future ->
                        val response = future.get(10, TimeUnit.SECONDS)
                        assertEquals(200, response.statusCode())
                        assertEquals(
                            "${URI(forwarder.baseUrl).rawAuthority} POST /auth%20path?request=${index + 1} $body",
                            response.body(),
                        )
                    }
                }
            }
        } finally {
            backend.stop(0)
        }
    }

    @Test
    fun `closing the forwarder closes active connections and releases its listener`() {
        ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")).use { backend ->
            Executors.newVirtualThreadPerTaskExecutor().use { workers ->
                val accepted = workers.submit<Socket> { backend.accept() }
                val forwarder = LoopbackPortForwarder("127.0.0.1", backend.localPort)
                val port = URI(forwarder.baseUrl).port
                try {
                    Socket("127.0.0.1", port).use { client ->
                        accepted.get(5, TimeUnit.SECONDS).use { upstream ->
                            client.soTimeout = 5000
                            upstream.soTimeout = 5000
                            client.getOutputStream().write(42)
                            assertEquals(42, upstream.getInputStream().read())
                            forwarder.close()
                            assertEquals(-1, client.getInputStream().read())
                            assertEquals(-1, upstream.getInputStream().read())
                        }
                    }
                    assertFailsWith<IOException> {
                        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 1000) }
                    }
                } finally {
                    forwarder.close()
                }
            }
        }
    }
}
