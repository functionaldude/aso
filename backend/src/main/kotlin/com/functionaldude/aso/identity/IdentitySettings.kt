package com.functionaldude.aso.identity

import org.springframework.boot.context.properties.ConfigurationProperties
import java.net.URI

@ConfigurationProperties("aso.identity")
data class IdentitySettings(
    val issuer: String,
    val clientId: String,
    val publicUrl: String,
    val mcpAudience: String,
    val groupsClaim: String,
    val memberGroup: String,
    val adminGroup: String,
    val mcpTokenMode: String = "jwt",
) {
    init {
        listOf(issuer, clientId, publicUrl, mcpAudience, groupsClaim, memberGroup, adminGroup)
            .forEach { require(it.isNotBlank()) { "All ASO identity settings must be explicitly configured" } }
        listOf(issuer, publicUrl).forEach {
            val uri = URI(it)
            require(uri.scheme == "https" || (uri.scheme == "http" && uri.host in setOf("127.0.0.1", "localhost", "::1"))) {
                "Identity endpoints require HTTPS except on loopback"
            }
            require(uri.userInfo == null && uri.query == null && uri.fragment == null)
        }
        require(!publicUrl.endsWith("/")) { "Public URL must not end in /" }
        require(memberGroup != adminGroup) { "Member and admin groups must differ" }
        require(mcpTokenMode in setOf("jwt", "okta-org")) { "MCP token mode must be jwt or okta-org" }
        if (mcpTokenMode == "okta-org") {
            require(URI(issuer).path.orEmpty().isEmpty() && !issuer.endsWith('/')) {
                "Okta org mode requires the org issuer origin without a path or trailing slash"
            }
        }
    }

    val resource: String get() = "$publicUrl/mcp"
    val metadata: String get() = "$publicUrl/.well-known/oauth-protected-resource/mcp"
    val scopes: List<String> get() =
        listOf("openid", "profile", "email") +
            if (mcpTokenMode == "okta-org") listOf("groups") else emptyList()
}
