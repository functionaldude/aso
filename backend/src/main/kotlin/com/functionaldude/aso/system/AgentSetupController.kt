package com.functionaldude.aso.system

import com.functionaldude.aso.identity.IdentitySettings
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.ClassPathResource
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI

data class AgentClientRegistration(
    val clientId: String,
    val callbackUrl: String,
)

data class AgentSetupInfo(
    val mcpUrl: String,
    val issuer: String,
    val scopes: List<String>,
    val codex: AgentClientRegistration?,
    val mcpJam: AgentClientRegistration?,
    val recommendedParagraph: String,
)

/** Only deliberately public connection details are returned; no session or workspace state is read. */
@RestController
class AgentSetupController(
    private val identity: IdentitySettings,
    @Value("\${aso.agent-setup.codex-client-id:}") private val codexClientId: String,
    @Value("\${aso.agent-setup.codex-callback-url:}") private val codexCallbackUrl: String,
    @Value("\${aso.agent-setup.mcp-jam-client-id:}") private val mcpJamClientId: String,
    @Value("\${aso.agent-setup.mcp-jam-callback-url:}") private val mcpJamCallbackUrl: String,
) {
    private val paragraph =
        ClassPathResource("agent-setup/agent-setup.md").inputStream.bufferedReader().use { source ->
            val match =
                Regex("(?s)<!-- aso-agents-paragraph:start -->\\s*```markdown\\s*\\n(.*?)\\n```\\s*<!-- aso-agents-paragraph:end -->")
                    .find(source.readText())
            requireNotNull(match) { "Canonical agent paragraph is missing" }.groupValues[1]
        }

    @GetMapping("/api/v1/agent-setup")
    fun info() =
        AgentSetupInfo(
            mcpUrl = identity.resource,
            issuer = identity.issuer,
            scopes = listOf("openid", "profile", "email"),
            codex = registration(codexClientId, codexCallbackUrl),
            mcpJam = registration(mcpJamClientId, mcpJamCallbackUrl),
            recommendedParagraph = paragraph,
        )

    private fun registration(
        clientId: String,
        callbackUrl: String,
    ): AgentClientRegistration? {
        if (clientId.isBlank() || callbackUrl.isBlank()) return null
        val callback = URI(callbackUrl)
        require(callback.userInfo == null && callback.query == null && callback.fragment == null) {
            "Public agent callbacks must not contain credentials, query parameters or fragments"
        }
        require(callback.scheme == "https" || (callback.scheme == "http" && callback.host in setOf("127.0.0.1", "localhost", "::1"))) {
            "Public agent callbacks require HTTPS except on loopback"
        }
        return AgentClientRegistration(clientId, callbackUrl)
    }
}
