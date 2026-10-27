package com.functionaldude.aso.identity

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.jwt.JwtException
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import java.time.Instant
import java.util.Date
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TokenValidationTest {
    private val key = RSAKeyGenerator(2048).generate()
    private val decoder =
        NimbusJwtDecoder.withPublicKey(key.toRSAPublicKey()).build().also {
            it.setJwtValidator(
                DelegatingOAuth2TokenValidator(
                    JwtValidators.createDefaultWithIssuer("https://idp.example.invalid"),
                    SecurityConfiguration.mcpTokenValidator("https://aso.example.invalid/mcp", "aso"),
                ),
            )
        }

    private fun token(
        issuer: String = "https://idp.example.invalid",
        audience: List<String> = listOf("https://aso.example.invalid/mcp"),
        expiry: Instant = Instant.now().plusSeconds(300),
        subject: String? = "alice",
        signingKey: com.nimbusds.jose.jwk.RSAKey = key,
        scope: Any? = "openid profile email",
        scp: Any? = null,
    ): String {
        val builder =
            JWTClaimsSet
                .Builder()
                .issuer(
                    issuer,
                ).audience(
                    audience,
                ).subject(subject)
                .issueTime(Date.from(Instant.now().minusSeconds(600)))
                .expirationTime(Date.from(expiry))
        if (scope != null) builder.claim("scope", scope)
        if (scp != null) builder.claim("scp", scp)
        return SignedJWT(JWSHeader(JWSAlgorithm.RS256), builder.build()).also { it.sign(RSASSASigner(signingKey)) }.serialize()
    }

    @Test fun `signed access token requires exact issuer intended audience lifetime subject and signature`() {
        assertEquals("alice", decoder.decode(token()).subject)
        listOf(
            token(issuer = "https://other.example.invalid"),
            token(audience = listOf("other-client")),
            token(audience = listOf("aso-other")),
            token(expiry = Instant.now().minusSeconds(120)),
            token(subject = null),
            token(signingKey = RSAKeyGenerator(2048).generate()),
            "invalid",
        ).forEach {
            assertFailsWith<JwtException> { decoder.decode(it) }
        }
    }

    @Test fun `access tokens accept either exact ASO audience including audience arrays`() {
        listOf(
            listOf("aso"),
            listOf("https://aso.example.invalid/mcp"),
            listOf("other-api", "aso"),
            listOf("aso", "https://aso.example.invalid/mcp"),
        ).forEach { audience ->
            assertEquals("alice", decoder.decode(token(audience = audience)).subject)
        }
    }

    @Test fun `access tokens accept string or array scopes and consistent representations for either audience`() {
        listOf("aso", "https://aso.example.invalid/mcp").forEach { audience ->
            listOf(
                token(audience = listOf(audience), scope = "openid profile email"),
                token(audience = listOf(audience), scope = null, scp = listOf("openid", "profile", "email")),
                token(audience = listOf(audience), scope = "openid profile email", scp = listOf("email", "openid", "profile")),
            ).forEach { assertEquals("alice", decoder.decode(it).subject) }
        }
    }

    @Test fun `ID tokens and missing malformed or unrelated access scopes are rejected for either audience`() {
        listOf("aso", "https://aso.example.invalid/mcp").forEach { audience ->
            listOf(null, "", "profile email", "not-openid", listOf("openid", "profile")).forEach { scope ->
                assertFailsWith<JwtException> { decoder.decode(token(audience = listOf(audience), scope = scope)) }
            }
        }
    }

    @Test fun `array scope claims and conflicting or malformed dual claims are rejected`() {
        listOf("aso", "https://aso.example.invalid/mcp").forEach { audience ->
            listOf(
                token(audience = listOf(audience), scope = null, scp = emptyList<String>()),
                token(audience = listOf(audience), scope = null, scp = "openid profile"),
                token(audience = listOf(audience), scope = null, scp = listOf("profile", "email")),
                token(audience = listOf(audience), scope = null, scp = listOf("openid", 123)),
                token(audience = listOf(audience), scope = null, scp = listOf("openid profile")),
                token(audience = listOf(audience), scope = "openid profile", scp = listOf("openid")),
                token(audience = listOf(audience), scope = "openid profile", scp = "openid profile"),
                token(audience = listOf(audience), scope = listOf("openid"), scp = listOf("openid")),
                token(audience = listOf(audience), scope = "openid  profile"),
            ).forEach { assertFailsWith<JwtException> { decoder.decode(it) } }
        }
    }

    @Test fun `production identity settings reject missing configuration and non-loopback HTTP`() {
        val settings =
            IdentitySettings(
                "https://idp.example.invalid",
                "web",
                "https://aso.example.invalid",
                "https://aso.example.invalid/mcp",
                "groups",
                "members",
                "admins",
            )
        assertFailsWith<IllegalArgumentException> { settings.copy(clientId = "") }
        assertFailsWith<IllegalArgumentException> { settings.copy(issuer = "http://idp.example.invalid") }
        assertFailsWith<IllegalArgumentException> { settings.copy(memberGroup = "admins") }
    }
}
