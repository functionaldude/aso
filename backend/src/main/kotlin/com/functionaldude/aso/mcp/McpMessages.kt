package com.functionaldude.aso.mcp

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import tools.jackson.databind.JsonNode

// MCP IDs and undispatched params/arguments have method-dependent JSON shapes.
@JsonIgnoreProperties(ignoreUnknown = true)
data class McpRequest(
    val jsonrpc: String,
    val method: String,
    val id: JsonNode? = null,
    val params: JsonNode? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ToolCallParameters(
    val name: String? = null,
    val arguments: JsonNode? = null,
)

data class McpSuccess<T>(
    val id: JsonNode,
    val result: T,
    val jsonrpc: String = "2.0",
)

data class McpError(
    val code: Int,
    val message: String,
)

data class McpFailure(
    val id: JsonNode,
    val error: McpError,
    val jsonrpc: String = "2.0",
)

data class ResourceMetadata(
    val resource: String,
    @get:JsonProperty("authorization_servers") val authorizationServers: List<String>,
    @get:JsonProperty("scopes_supported") val scopesSupported: List<String>,
    @get:JsonProperty("bearer_methods_supported") val bearerMethodsSupported: List<String>,
)

data class ToolCapability(
    val listChanged: Boolean = false,
)

data class McpCapabilities(
    val tools: ToolCapability = ToolCapability(),
)

data class ServerInfo(
    val name: String = "aso",
    val version: String = "0.0.1",
)

data class InitializeResult(
    val protocolVersion: String = "2025-11-25",
    val capabilities: McpCapabilities = McpCapabilities(),
    val serverInfo: ServerInfo = ServerInfo(),
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data object EmptyResult

data class ToolDefinition(
    val name: String,
    val title: String,
    val description: String,
    val inputSchema: Map<String, Any>,
    val annotations: ToolAnnotations,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ToolAnnotations(
    val readOnlyHint: Boolean,
    val openWorldHint: Boolean = false,
    val destructiveHint: Boolean? = null,
    val idempotentHint: Boolean? = null,
)

data class ToolsResult(
    val tools: List<ToolDefinition>,
)

data class TextContent(
    val text: String,
    val type: String = "text",
)

data class ToolResult<T>(
    val content: List<TextContent>,
    val structuredContent: T,
    val isError: Boolean = false,
)

data class ToolFailure(
    val code: String,
    val message: String,
    val status: Int,
)
