package com.functionaldude.aso.system

import com.functionaldude.aso.identity.IdentitySettings
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AgentSetupControllerTest {
    private val identity =
        IdentitySettings(
            "https://idp.example.invalid",
            "engineering-memory",
            "https://knowledge.example.invalid",
            "https://knowledge.example.invalid/mcp",
            "groups",
            "members",
            "admins",
        )

    @Test
    fun `public setup defaults the Codex callback for unset and blank settings`() {
        val default = "http://127.0.0.1:8766/callback"
        assertEquals(default, AgentSetupController(identity).info().codexCallbackUrl)
        listOf("", "  ").forEach { blank ->
            val setup = AgentSetupController(identity, blank).info()
            assertEquals("https://knowledge.example.invalid/mcp", setup.mcpUrl)
            assertEquals("engineering-memory", setup.clientId)
            assertEquals(listOf("openid", "profile", "email"), setup.scopes)
            assertEquals(default, setup.codexCallbackUrl)
            assertTrue(setup.recommendedParagraph.startsWith("Use the configured ASO MCP server"))
        }
    }

    @Test
    fun `callback overrides are public values and cannot expose embedded credentials or query tokens`() {
        listOf("http://127.0.0.1:9876/callback", "https://devbox.example.invalid:8443/callback").forEach { callback ->
            assertEquals(callback, AgentSetupController(identity, callback).info().codexCallbackUrl)
        }
        listOf(
            "https://user:secret@example.invalid/callback",
            "https://example.invalid/callback?token=private",
            "https://example.invalid/callback#private",
            "http://example.invalid/callback",
            "https:/callback",
        ).forEach { invalid ->
            assertFailsWith<IllegalArgumentException> { AgentSetupController(identity, invalid) }
        }
    }
}
