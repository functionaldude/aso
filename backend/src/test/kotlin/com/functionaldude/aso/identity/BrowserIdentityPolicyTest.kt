package com.functionaldude.aso.identity

import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken
import org.springframework.security.oauth2.core.oidc.OidcIdToken
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser
import java.time.Instant
import kotlin.test.assertFailsWith

class BrowserIdentityPolicyTest {
    private val database = mock(DSLContext::class.java)
    private val settings =
        IdentitySettings(
            "https://idp.example.invalid",
            "web",
            "https://aso.example.invalid",
            "https://aso.example.invalid/mcp",
            "groups",
            "members",
            "admins",
        )
    private val service = IdentityService(database, settings)

    @Test
    fun `a live servlet session does not extend expired OIDC authorization`() {
        assertFailsWith<AccessDeniedException> {
            service.current(session(listOf("members"), Instant.now().minusSeconds(1)), human = true)
        }
        verifyNoInteractions(database)
    }

    @Test
    fun `a user without member or admin group cannot create an identity`() {
        assertFailsWith<AccessDeniedException> {
            service.current(session(listOf("other"), Instant.now().plusSeconds(60)), human = true)
        }
        verifyNoInteractions(database)
    }

    private fun session(
        groups: List<String>,
        expiry: Instant,
    ): OAuth2AuthenticationToken {
        val token =
            OidcIdToken(
                "signed-token-already-validated-by-spring",
                Instant.now().minusSeconds(120),
                expiry,
                mapOf("iss" to settings.issuer, "sub" to "alice", "aud" to listOf("web"), "groups" to groups),
            )
        val user = DefaultOidcUser(emptyList(), token)
        return OAuth2AuthenticationToken(user, user.authorities, "oidc")
    }
}
