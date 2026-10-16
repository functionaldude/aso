package com.functionaldude.aso.mcp

import com.functionaldude.aso.identity.IdentityService
import com.functionaldude.aso.identity.IdentitySettings
import com.functionaldude.aso.questions.ContentFailure
import com.functionaldude.aso.questions.QuestionTools
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/** Stateless Streamable HTTP, JSON responses; no sessions, server notifications, or SSE stream. */
@RestController
class McpController(
    private val identities: IdentityService,
    private val settings: IdentitySettings,
    private val json: JsonMapper,
    private val questions: QuestionTools,
) {
    private fun originAllowed(request: HttpServletRequest) = request.getHeader("Origin").let { it == null || it == settings.publicUrl }

    @GetMapping("/.well-known/oauth-protected-resource", "/.well-known/oauth-protected-resource/mcp")
    fun metadata() = ResourceMetadata(settings.resource, listOf(settings.issuer), listOf("openid", "profile", "email"), listOf("header"))

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
        ) = ResponseEntity.ok<Any>(McpFailure(id, McpError(code, text)))
        if (!message.isObject || !message.path("jsonrpc").isString ||
            message.path("jsonrpc").asString() != "2.0" || !message.path("method").isString
        ) {
            return error(-32600, "Invalid Request")
        }
        // Inspect the dynamic envelope first so invalid requests retain JSON-RPC errors.
        val envelope = json.treeToValue(message, McpRequest::class.java)
        val method = envelope.method
        if (method == "notifications/initialized" || method == "notifications/cancelled") return ResponseEntity.accepted().build()
        if (id.isMissingNode || !(id.isString || id.isNumber)) return error(-32600, "A request ID is required")
        val result: Any =
            when (method) {
                "initialize" -> InitializeResult()
                "ping" -> EmptyResult
                "tools/list" -> ToolsResult(listOf(identityTool) + questions.definitions)
                "tools/call" -> {
                    val params =
                        try {
                            envelope.params?.let { json.treeToValue(it, ToolCallParameters::class.java) }
                        } catch (_: JacksonException) {
                            return error(-32602, "Invalid tool parameters")
                        }
                    val name = params?.name
                    if (name != "identity" && questions.definitions.none { it.name == name }) return error(-32602, "Unknown tool")
                    val arguments = params?.arguments ?: json.createObjectNode()
                    if (!arguments.isObject) return error(-32602, "Tool arguments must be an object")
                    try {
                        val value =
                            if (name == "identity") {
                                if (arguments.size() != 0) return error(-32602, "identity accepts no arguments")
                                identity
                            } else {
                                questions.call(name!!, authentication, arguments)
                            }
                        ToolResult(listOf(TextContent(json.writeValueAsString(value))), value)
                    } catch (failure: ContentFailure) {
                        ToolResult(
                            listOf(TextContent(failure.message)),
                            ToolFailure(failure.code, failure.message, failure.status),
                            isError = true,
                        )
                    }
                }
                else -> return error(-32601, "Method not found")
            }
        return ResponseEntity.ok(McpSuccess(id, result))
    }

    private val identityTool =
        ToolDefinition(
            "identity",
            "Your ASO identity",
            "Return your persisted ASO identity and trusted workspace role.",
            mapOf("type" to "object", "properties" to emptyMap<String, Any>(), "additionalProperties" to false),
            ToolAnnotations(readOnlyHint = true),
        )
}
