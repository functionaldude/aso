package com.functionaldude.aso.mcp

import com.functionaldude.aso.identity.IdentityService
import com.functionaldude.aso.identity.IdentitySettings
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/** Stateless Streamable HTTP, JSON responses; no sessions, server notifications, or SSE stream. */
@RestController
class McpController(
    private val identities: IdentityService,
    private val settings: IdentitySettings,
    private val json: JsonMapper,
) {
    private fun originAllowed(request: HttpServletRequest) = request.getHeader("Origin").let { it == null || it == settings.publicUrl }

    @GetMapping("/.well-known/oauth-protected-resource", "/.well-known/oauth-protected-resource/mcp")
    fun metadata() =
        mapOf(
            "resource" to settings.resource,
            "authorization_servers" to listOf(settings.issuer),
            "scopes_supported" to listOf("openid", "profile", "email"),
            "bearer_methods_supported" to listOf("header"),
        )

    @GetMapping("/mcp")
    fun stream(
        authentication: Authentication,
        request: HttpServletRequest,
    ): ResponseEntity<Void> {
        if (!originAllowed(request)) return ResponseEntity.status(403).build()
        identities.current(authentication)
        return ResponseEntity.status(405).header("Allow", "POST").build()
    }

    @PostMapping("/mcp", consumes = ["application/json"], produces = ["application/json"])
    fun call(
        @RequestBody message: JsonNode,
        authentication: Authentication,
        request: HttpServletRequest,
    ): ResponseEntity<Any> {
        if (!originAllowed(request)) return ResponseEntity.status(403).build()
        val identity = identities.current(authentication)
        val id = message.path("id")

        fun error(
            code: Int,
            text: String,
        ) = ResponseEntity.ok<Any>(
            mapOf(
                "jsonrpc" to "2.0",
                "id" to id,
                "error" to mapOf("code" to code, "message" to text),
            ),
        )
        if (!message.isObject || message.path("jsonrpc").asString() != "2.0" ||
            !message.path("method").isString
        ) {
            return error(-32600, "Invalid Request")
        }
        val method = message["method"].asString()
        if (method == "notifications/initialized" || method == "notifications/cancelled") return ResponseEntity.accepted().build()
        if (id.isMissingNode || !(id.isString || id.isNumber)) return error(-32600, "A request ID is required")
        val params = message.path("params")
        val result: Any =
            when (method) {
                "initialize" ->
                    mapOf(
                        "protocolVersion" to "2025-11-25",
                        "capabilities" to mapOf("tools" to mapOf("listChanged" to false)),
                        "serverInfo" to mapOf("name" to "aso", "version" to "0.0.1"),
                    )
                "ping" -> emptyMap<String, Any>()
                "tools/list" ->
                    mapOf(
                        "tools" to
                            listOf(
                                mapOf(
                                    "name" to "identity",
                                    "title" to "Your ASO identity",
                                    "description" to "Return your persisted ASO identity and trusted workspace role.",
                                    "inputSchema" to
                                        mapOf(
                                            "type" to "object",
                                            "properties" to emptyMap<String, Any>(),
                                            "additionalProperties" to false,
                                        ),
                                    "annotations" to mapOf("readOnlyHint" to true, "openWorldHint" to false),
                                ),
                            ),
                    )
                "tools/call" -> {
                    if (params.path("name").asString() != "identity") return error(-32602, "Unknown tool")
                    val arguments = params.path("arguments")
                    if (!arguments.isMissingNode &&
                        (!arguments.isObject || arguments.size() != 0)
                    ) {
                        return error(-32602, "identity accepts no arguments")
                    }
                    mapOf(
                        "content" to listOf(mapOf("type" to "text", "text" to json.writeValueAsString(identity))),
                        "structuredContent" to identity,
                        "isError" to false,
                    )
                }
                else -> return error(-32601, "Method not found")
            }
        return ResponseEntity.ok(mapOf("jsonrpc" to "2.0", "id" to id, "result" to result))
    }
}
