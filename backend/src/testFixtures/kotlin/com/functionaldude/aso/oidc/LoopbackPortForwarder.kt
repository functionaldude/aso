package com.functionaldude.aso.oidc

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/** Keeps the test issuer on loopback when Docker's published ports use a remote host or bridge gateway. */
class LoopbackPortForwarder(
    private val targetHost: String,
    private val targetPort: Int,
) : AutoCloseable {
    private val listener = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val workers = Executors.newVirtualThreadPerTaskExecutor()
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    val baseUrl = "http://127.0.0.1:${listener.localPort}"

    init {
        workers.execute {
            while (!listener.isClosed) {
                val client =
                    try {
                        listener.accept()
                    } catch (failure: SocketException) {
                        if (listener.isClosed) break
                        throw failure
                    }
                if (!track(client)) continue
                try {
                    workers.execute { forward(client) }
                } catch (_: RejectedExecutionException) {
                    client.close()
                    sockets.remove(client)
                }
            }
        }
    }

    private fun forward(client: Socket) {
        val upstream = Socket()
        try {
            client.use {
                upstream.use {
                    if (!track(upstream)) return
                    upstream.connect(InetSocketAddress(targetHost, targetPort), 5000)
                    workers.execute { relay(client, upstream) }
                    relay(upstream, client)
                }
            }
        } catch (_: IOException) {
            // Connection failures reach the real browser/HTTP client as a closed connection.
        } finally {
            sockets.remove(client)
            sockets.remove(upstream)
        }
    }

    private fun track(socket: Socket): Boolean {
        sockets.add(socket)
        if (!listener.isClosed) return true
        socket.close()
        sockets.remove(socket)
        return false
    }

    private fun relay(
        source: Socket,
        target: Socket,
    ) {
        try {
            source.getInputStream().copyTo(target.getOutputStream())
            target.shutdownOutput()
        } catch (_: IOException) {
            source.close()
            target.close()
        }
    }

    override fun close() {
        listener.close()
        workers.shutdownNow()
        sockets.forEach { it.close() }
        check(workers.awaitTermination(5, TimeUnit.SECONDS)) { "Loopback forwarding workers did not stop" }
    }
}
