package com.functionaldude.aso.questions

import com.functionaldude.aso.mcp.EmptyResult
import com.functionaldude.aso.mcp.McpSuccess
import com.functionaldude.aso.mcp.TextContent
import com.functionaldude.aso.mcp.ToolAnnotations
import com.functionaldude.aso.mcp.ToolCallParameters
import com.functionaldude.aso.mcp.ToolResult
import com.functionaldude.aso.web.JsonConfiguration
import org.junit.jupiter.api.Test
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ContentInputsTest {
    private val json =
        JsonMapper
            .builder()
            .addModule(KotlinModule.Builder().build())
            .also {
                JsonConfiguration().strictInputs().customize(it)
            }.build()

    @Test
    fun `known inputs reject unknown properties incorrect types coercion and null collections`() {
        listOf(
            "[]",
            "4",
            "true",
            """{"authorId":"other"}""",
            """{"context":{"role":"admin"}}""",
            """{"title":4}""",
            """{"body":true}""",
            """{"requestId":4}""",
            """{"tags":null}""",
            """{"tags":"kotlin"}""",
            """{"tags":[1]}""",
            """{"tags":[null]}""",
            """{"context":false}""",
            """{"context":{"versions":25}}""",
        ).forEach { payload ->
            assertFailsWith<JacksonException>(payload) { json.readValue(payload, CreateQuestionInput::class.java) }
        }
        listOf("1.5", "1.0", "\"20\"", "true", "null", "2147483648").forEach { limit ->
            assertFailsWith<JacksonException>(limit) {
                json.readValue("""{"limit":$limit}""", ListQuestionsArguments::class.java)
            }
        }
        assertFailsWith<JacksonException> { json.readValue("""{"body":4}""", CreateAnswerInput::class.java) }
        assertFailsWith<JacksonException> { json.readValue("""{"questionId":4}""", CreateAnswerArguments::class.java) }
    }

    @Test
    fun `missing tags and optional nulls preserve defaults and domain validation`() {
        val input = json.readValue("""{"context":null,"title":null}""", CreateQuestionInput::class.java)
        assertEquals(emptyList(), input.tags)
        assertEquals(null, input.context)
        assertEquals(20, json.readValue("{}", ListQuestionsArguments::class.java).limit)
        val failure = assertFailsWith<ContentFailure> { ContentPolicy().text(input.title, "title", 200, true) }
        assertEquals("title is required", failure.message)
    }

    @Test
    fun `typed MCP results retain external property names IDs and empty results`() {
        val response = ToolResult(listOf(TextContent("ok")), EmptyResult)
        val result = json.valueToTree<JsonNode>(response)
        assertEquals(setOf("content", "structuredContent", "isError"), result.propertyNames().toSet())
        assertEquals(false, result["isError"].asBoolean())
        assertEquals(json.readTree("{}"), result["structuredContent"])
        assertEquals(json.readTree("""{"readOnlyHint":true,"openWorldHint":false}"""), json.valueToTree<JsonNode>(ToolAnnotations(true)))
        listOf("7", "1.5", "\"request-1\"").forEach { id ->
            assertEquals(json.readTree(id), json.valueToTree<JsonNode>(McpSuccess(json.readTree(id), EmptyResult))["id"])
        }
        val params = json.readValue("""{"name":"identity","arguments":null}""", ToolCallParameters::class.java)
        assertTrue(params.arguments != null && params.arguments.isNull)
    }
}
