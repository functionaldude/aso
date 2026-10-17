package com.functionaldude.aso.identity

import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken
import org.springframework.security.oauth2.core.oidc.OidcIdToken
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RateLimitsTest {
    private class MovingClock(
        var instant: Instant,
    ) : Clock() {
        override fun getZone(): ZoneId = ZoneId.of("UTC")

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = instant
    }

    private fun bearer(subject: String): JwtAuthenticationToken {
        val jwt =
            Jwt
                .withTokenValue("fixture")
                .header("alg", "none")
                .claim("iss", "https://issuer.example")
                .claim("sub", subject)
                .build()
        return JwtAuthenticationToken(jwt)
    }

    private fun browser(subject: String): OAuth2AuthenticationToken {
        val now = Instant.parse("2026-01-01T00:00:00Z")
        val token = OidcIdToken("fixture", now, now.plusSeconds(3600), mapOf("iss" to "https://issuer.example", "sub" to subject))
        return OAuth2AuthenticationToken(DefaultOidcUser(emptyList(), token), emptyList(), "oidc")
    }

    @Test
    fun `browser and MCP share per-user request and write capacity and retry after expiry`() {
        val clock = MovingClock(Instant.parse("2026-01-01T00:00:00Z"))
        val limits = RateLimits(RateLimitSettings(requests = 3, writes = 1, window = Duration.ofSeconds(10)), clock)
        assertNull(limits.acquire(browser("alice"), write = false))
        assertNull(limits.acquire(bearer("alice"), write = true))
        assertEquals(LimitRejection(10), limits.acquire(browser("alice"), write = true))
        assertNull(limits.acquire(bearer("alice"), write = false))
        assertEquals(LimitRejection(10), limits.acquire(bearer("alice"), write = false))
        assertNull(limits.acquire(bearer("bob"), write = true))
        clock.instant = clock.instant.plusSeconds(9)
        assertEquals(LimitRejection(1), limits.acquire(browser("alice"), write = false))
        clock.instant = clock.instant.plusSeconds(1)
        assertNull(limits.acquire(browser("alice"), write = true))
    }

    @Test
    fun `invalid settings fail at startup`() {
        org.junit.jupiter.api
            .assertThrows<IllegalArgumentException> { RateLimitSettings(requests = 0) }
        org.junit.jupiter.api
            .assertThrows<IllegalArgumentException> { RateLimitSettings(requests = 1, writes = 2) }
        org.junit.jupiter.api
            .assertThrows<IllegalArgumentException> { RateLimitSettings(window = Duration.ZERO) }
    }
}
