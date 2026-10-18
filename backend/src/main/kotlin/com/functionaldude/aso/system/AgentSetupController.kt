package com.functionaldude.aso.system

import com.functionaldude.aso.identity.IdentitySettings
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.ClassPathResource
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI

data class AgentSetupInfo(
    val mcpUrl: String,
    val issuer: String,
    val scopes: List<String>,
    val clientId: String,
    val codexCallbackUrl: String,
    val recommendedParagraph: String,
)

/** Only deliberately public connection details are returned; no session or workspace state is read. */
@RestController
class AgentSetupController(
    private val identity: IdentitySettings,
    @Value("\${aso.agent-setup.codex-callback-url:http://127.0.0.1:8766/callback}")
    codexCallbackUrl: String = DEFAULT_CODEX_CALLBACK_URL,
) {
    private val callbackUrl = callback(codexCallbackUrl.ifBlank { DEFAULT_CODEX_CALLBACK_URL })
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
            clientId = identity.clientId,
            codexCallbackUrl = callbackUrl,
            recommendedParagraph = paragraph,
        )

    private fun callback(callbackUrl: String): String {
        val callback = URI(callbackUrl)
        require(callback.host != null) { "Public agent callbacks require a host" }
        require(callback.userInfo == null && callback.query == null && callback.fragment == null) {
            "Public agent callbacks must not contain credentials, query parameters or fragments"
        }
        require(callback.scheme == "https" || (callback.scheme == "http" && callback.host in setOf("127.0.0.1", "localhost", "::1"))) {
            "Public agent callbacks require HTTPS except on loopback"
        }
        return callbackUrl
    }

    companion object {
        const val DEFAULT_CODEX_CALLBACK_URL = "http://127.0.0.1:8766/callback"
    }
}
