package com.functionaldude.aso.questions

import jakarta.servlet.FilterChain
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.io.ByteArrayInputStream

/** Bound JSON before parsing, including chunked requests. Never retain or log the body. */
@Component
class ContentSizeFilter : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest) =
        request.method != "POST" || !(request.requestURI == "/mcp" || request.requestURI.startsWith("/api/v1/"))

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val bytes = request.inputStream.readNBytes(262145)
        if (bytes.size > 262144) {
            response.status = 413
            response.contentType = "application/problem+json"
            response.writer.write(
                """{"status":413,"title":"payload_too_large","detail":"Request exceeds 256 KiB; submit a focused, sanitized problem or answer."}""",
            )
            return
        }
        val wrapped =
            object : HttpServletRequestWrapper(request) {
                override fun getInputStream(): ServletInputStream {
                    val stream = ByteArrayInputStream(bytes)
                    return object : ServletInputStream() {
                        override fun read() = stream.read()

                        override fun isFinished() = stream.available() == 0

                        override fun isReady() = true

                        override fun setReadListener(listener: ReadListener): Unit =
                            throw UnsupportedOperationException("Synchronous requests only")
                    }
                }
            }
        filterChain.doFilter(wrapped, response)
    }
}
