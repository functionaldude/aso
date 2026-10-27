package com.functionaldude.aso.identity

import com.functionaldude.aso.jooq.tables.references.APP_USER
import org.jooq.DSLContext
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.Authentication
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

data class Identity(
    val id: UUID,
    val issuer: String,
    val subject: String,
    val displayName: String,
    val email: String?,
    val admin: Boolean,
    val channel: String,
)

/** Shared by adapters: only Spring-validated principals can supply identity and trusted claims. */
@Service
class IdentityService(
    private val database: DSLContext,
    private val settings: IdentitySettings,
) {
    fun current(
        authentication: Authentication,
        human: Boolean = false,
    ): Identity {
        val claims: Map<String, Any>
        val channel: String
        when (authentication) {
            is OAuth2AuthenticationToken -> {
                val user = authentication.principal as? OidcUser ?: throw AccessDeniedException("OIDC session required")
                if (user.idToken.expiresAt?.isAfter(Instant.now()) != true) throw AccessDeniedException("Session expired; sign in again")
                // Authorization claims come from the signed ID token, not mutable request fields/userinfo.
                claims = user.idToken.claims
                channel = "web"
            }
            is JwtAuthenticationToken -> {
                if (human) throw AccessDeniedException("An authenticated browser session is required")
                claims = authentication.token.claims
                channel = "mcp"
            }
            else -> throw AccessDeniedException("Validated identity required")
        }
        if (claims["iss"].toString() != settings.issuer) throw AccessDeniedException("Unexpected issuer")
        val subject = claims["sub"] as? String ?: throw AccessDeniedException("Subject required")
        if (subject.isBlank() || subject.length > 255) throw AccessDeniedException("Invalid subject")
        val groups = (claims[settings.groupsClaim] as? Collection<*>)?.filterIsInstance<String>().orEmpty()
        if (settings.memberGroup !in groups && settings.adminGroup !in groups) throw AccessDeniedException("Workspace membership required")
        val name = ((claims["name"] ?: claims["preferred_username"] ?: subject) as? String ?: subject).take(255)
        val email = (claims["email"] as? String)?.take(320)
        val user =
            database
                .insertInto(APP_USER)
                .set(APP_USER.ID, UUID.randomUUID())
                .set(APP_USER.ISSUER, settings.issuer)
                .set(APP_USER.SUBJECT, subject)
                .set(APP_USER.DISPLAY_NAME, name)
                .set(APP_USER.EMAIL, email)
                .onConflict(APP_USER.ISSUER, APP_USER.SUBJECT)
                .doUpdate()
                .set(APP_USER.DISPLAY_NAME, name)
                .set(APP_USER.EMAIL, email)
                .set(APP_USER.LAST_SEEN_AT, OffsetDateTime.now())
                .returning(APP_USER.ID)
                .fetchOne()!!
        return Identity(user.id!!, settings.issuer, subject, name, email, settings.adminGroup in groups, channel)
    }

    fun administrator(authentication: Authentication): Identity =
        current(authentication, human = true).also {
            if (!it.admin) throw AccessDeniedException("Administrator membership required")
        }
}
