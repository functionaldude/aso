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
                    SecurityConfiguration.audienceValidator("https://aso.example.invalid/mcp"),
                ),
            )
        }

    private fun token(
        issuer: String = "https://idp.example.invalid",
        audience: String = "https://aso.example.invalid/mcp",
        expiry: Instant = Instant.now().plusSeconds(300),
        subject: String? = "alice",
        signingKey: com.nimbusds.jose.jwk.RSAKey = key,
    ): String {
        val claims =
            JWTClaimsSet
                .Builder()
                .issuer(
                    issuer,
                ).audience(
                    audience,
                ).subject(subject)
                .issueTime(Date.from(Instant.now().minusSeconds(600)))
                .expirationTime(Date.from(expiry))
                .build()
        return SignedJWT(JWSHeader(JWSAlgorithm.RS256), claims).also { it.sign(RSASSASigner(signingKey)) }.serialize()
    }

    @Test fun `signed access token requires exact issuer intended audience lifetime subject and signature`() {
        assertEquals("alice", decoder.decode(token()).subject)
        listOf(
            token(issuer = "https://other.example.invalid"),
            token(audience = "aso-web"),
            token(expiry = Instant.now().minusSeconds(120)),
            token(subject = null),
            token(signingKey = RSAKeyGenerator(2048).generate()),
            "invalid",
        ).forEach {
            assertFailsWith<JwtException> { decoder.decode(it) }
        }
    }

    @Test fun `production identity settings reject missing configuration and non-loopback HTTP`() {
        val settings =
            IdentitySettings(
                "https://idp.example.invalid",
                "web",
                "secret",
                "https://aso.example.invalid",
                "https://aso.example.invalid/mcp",
                "groups",
                "members",
                "admins",
            )
        assertFailsWith<IllegalArgumentException> { settings.copy(webClientSecret = "") }
        assertFailsWith<IllegalArgumentException> { settings.copy(issuer = "http://idp.example.invalid") }
        assertFailsWith<IllegalArgumentException> { settings.copy(memberGroup = "admins") }
    }
}
