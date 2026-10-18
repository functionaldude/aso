package com.functionaldude.aso.system

import com.functionaldude.aso.identity.IdentitySettings
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
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
    fun `public setup retains the installation URL and shared ID when tool callbacks are missing`() {
        val setup = AgentSetupController(identity, "", "").info()
        assertEquals("https://knowledge.example.invalid/mcp", setup.mcpUrl)
        assertEquals("engineering-memory", setup.clientId)
        assertEquals(listOf("openid", "profile", "email"), setup.scopes)
        assertNull(setup.codexCallbackUrl)
        assertNull(setup.mcpJamCallbackUrl)
        assertTrue(setup.recommendedParagraph.startsWith("Use the configured ASO MCP server"))
    }

    @Test
    fun `tool callbacks are independent public values and cannot expose embedded credentials or query tokens`() {
        val codex = "http://127.0.0.1:8766/callback"
        val jam = "http://localhost:6274/oauth/callback"
        val setup = AgentSetupController(identity, codex, jam).info()
        assertEquals(codex, setup.codexCallbackUrl)
        assertEquals(jam, setup.mcpJamCallbackUrl)
        listOf(
            "https://user:secret@example.invalid/callback",
            "https://example.invalid/callback?token=private",
            "https://example.invalid/callback#private",
            "http://example.invalid/callback",
        ).forEach { invalid ->
            assertFailsWith<IllegalArgumentException> { AgentSetupController(identity, invalid, jam).info() }
            assertFailsWith<IllegalArgumentException> { AgentSetupController(identity, codex, invalid).info() }
        }
    }
}
