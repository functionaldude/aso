package com.functionaldude.aso.questions

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContentPolicyTest {
    private val policy = ContentPolicy()

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
            val error = assertFailsWith<ContentFailure> { policy.text(secret, "body", 20000, true) }
            assertTrue(error.message.contains("remove credentials"))
            assertFalse(error.message.contains(secret))
        }
        assertEquals(
            "password=<redacted>\norg.example:lib:1.2",
            policy.text(
                "password=<redacted>\norg.example:lib:1.2",
                "body",
                20000,
                true,
            ),
        )
    }

    @Test
    fun `typed values retain bounds tag normalization and credential rejection`() {
        listOf("", "a".repeat(201), "a\u0000b").forEach {
            assertFailsWith<ContentFailure> { policy.text(it, "title", 200, true) }
        }
        assertFailsWith<ContentFailure> { policy.text(null, "title", 200, true) }
        assertEquals(listOf("c++", "kotlin"), policy.tags(listOf("Kotlin", "c++", "kotlin")))
        listOf(
            listOf("sk-aaaaaaaaaaaaaaaaaaaaaaaaaaaa"),
            listOf("eyJhbGci.eyJzdWIi.c2lnbmF0dXJl"),
            listOf("bad tag"),
            List(6) { "kotlin" },
        ).forEach {
            assertFailsWith<ContentFailure> { policy.tags(it) }
        }
    }
}
