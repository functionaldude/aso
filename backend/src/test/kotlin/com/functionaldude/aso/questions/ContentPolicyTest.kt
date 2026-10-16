package com.functionaldude.aso.questions

import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContentPolicyTest {
    private val policy = ContentPolicy()
    private val json = JsonMapper.builder().build()

    @Test
    fun `markdown preserves code and links while preventing active HTML unsafe URLs and remote images`() {
        val html =
            policy.html(
                """
                # Failure
                <script>window.pwned = true</script>
                <img src=x onerror=alert(1)>

                [unsafe](javascript:alert%281%29)
                [safe](https://example.invalid/docs)
                ![diagram](https://example.invalid/tracker.png)
                ```kotlin
                println("<script>code only</script>")
                ```
                """.trimIndent(),
            )
        assertFalse(html.contains("<script>"))
        assertFalse(html.contains("<img"))
        assertFalse(html.contains("href=\"javascript:"))
        assertTrue(html.contains("&lt;script&gt;"))
        assertTrue(html.contains("<pre><code"))
        assertTrue(html.contains("href=\"https://example.invalid/docs\""))
        assertTrue(html.contains("diagram"))
    }

    @Test
    fun `credential shapes are rejected without reflecting secrets while redacted technical diagnostics remain useful`() {
        listOf(
            "-----BEGIN RSA PRIVATE KEY-----",
            "AKIA" + "A".repeat(16),
            "ghp_" + "a".repeat(30),
            "Authorization: Bearer " + "x".repeat(30),
            "password=super-secret-fixture",
            "client_secret: \"private-fixture\"",
            "api_key=private-fixture",
            "https://alice:private-fixture@example.invalid",
            "eyJhbGci.eyJzdWIi.c2lnbmF0dXJl",
        ).forEach { secret ->
            val error = assertFailsWith<ContentFailure> { policy.text(json.valueToTree(mapOf("body" to secret)), "body", 20000, true) }
            assertTrue(error.message.contains("remove credentials"))
            assertFalse(error.message.contains(secret))
        }
        assertEquals(
            "password=<redacted>\norg.example:lib:1.2",
            policy.text(
                json.valueToTree(
                    mapOf("body" to "password=<redacted>\norg.example:lib:1.2"),
                ),
                "body",
                20000,
                true,
            ),
        )
    }

    @Test
    fun `input bounds types tags and unknown authorship are checked`() {
        listOf("", "a".repeat(201), "a\u0000b").forEach {
            assertFailsWith<ContentFailure> { policy.text(json.valueToTree(mapOf("title" to it)), "title", 200, true) }
        }
        assertFailsWith<ContentFailure> { policy.text(json.readTree("""{"title":4}"""), "title", 200, true) }
        assertFailsWith<ContentFailure> { policy.fields(json.readTree("""{"authorId":"other"}"""), setOf("body")) }
        assertEquals(listOf("c++", "kotlin"), policy.tags(json.readTree("""{"tags":["Kotlin","c++","kotlin"]}""")))
        listOf(
            """{"tags":["sk-aaaaaaaaaaaaaaaaaaaaaaaaaaaa"]}""",
            """{"tags":["eyJhbGci.eyJzdWIi.c2lnbmF0dXJl"]}""",
            """{"tags":null}""",
            """{"tags":["bad tag"]}""",
            """{"tags":[1]}""",
        ).forEach {
            assertFailsWith<ContentFailure> { policy.tags(json.readTree(it)) }
        }
    }
}
