package com.functionaldude.aso.identity

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.core.Authentication
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthentication
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import java.time.Clock
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

@ConfigurationProperties("aso.rate-limit")
data class RateLimitSettings(
    val requests: Int = 600,
    val writes: Int = 120,
    val window: Duration = Duration.ofMinutes(1),
) {
    init {
        require(requests > 0 && writes > 0 && writes <= requests) { "Rate limits require 0 < writes <= requests" }
        require(window >= Duration.ofSeconds(1) && window <= Duration.ofHours(1)) { "Rate limit window must be 1 second to 1 hour" }
    }
}

data class LimitRejection(
    val retryAfterSeconds: Long,
)

/** One process owns the laptop MVP's counters. A rejected attempt consumes no capacity. */
@Component
class RateLimits(
    private val settings: RateLimitSettings,
    private val clock: Clock,
) {
    private data class Window(
        val started: Long,
        var requests: Int = 0,
        var writes: Int = 0,
    )

    private val windows = ConcurrentHashMap<String, Window>()
    private val checks = AtomicLong()

    fun acquire(
        authentication: Authentication,
        write: Boolean,
    ): LimitRejection? {
        val principal =
            when (authentication) {
                is OAuth2AuthenticationToken ->
                    (authentication.principal as? OidcUser)?.idToken?.takeIf { it.expiresAt?.isAfter(clock.instant()) == true }?.claims
                is JwtAuthenticationToken -> authentication.token.claims
                is BearerTokenAuthentication -> authentication.tokenAttributes
                else -> null
            } ?: return null // Security rejects unauthenticated requests independently.
        val issuer = principal["iss"]?.toString() ?: return null
        val subject = principal["sub"] as? String ?: return null
        val now = clock.millis()
        val interval = settings.window.toMillis()
        var rejection: LimitRejection? = null
        val key = "$issuer\u0000$subject"
        windows.compute(key) { _, previous ->
            val current = if (previous == null || now - previous.started >= interval) Window(now) else previous
            if (current.requests >= settings.requests || (write && current.writes >= settings.writes)) {
                rejection = LimitRejection(((current.started + interval - now + 999) / 1000).coerceAtLeast(1))
            } else {
                current.requests++
                if (write) current.writes++
            }
            current
        }
        if (checks.incrementAndGet() % 1024 == 0L) windows.entries.removeIf { now - it.value.started >= interval }
        return rejection
    }
}

class ApiRateLimitInterceptor(
    private val limits: RateLimits,
) : HandlerInterceptor {
    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
    ): Boolean {
        if (request.requestURI in PUBLIC_API_PATHS) return true
        val authentication = SecurityContextHolder.getContext().authentication ?: return true
        val rejected = limits.acquire(authentication, request.method !in setOf("GET", "HEAD")) ?: return true
        response.status = 429
        response.contentType = "application/problem+json"
        response.setHeader("Retry-After", rejected.retryAfterSeconds.toString())
        response.writer.write(
            """{"status":429,"title":"rate_limited","detail":"Per-user request limit reached; retry after the indicated seconds.","code":"rate_limited"}""",
        )
        return false
    }

    private companion object {
        val PUBLIC_API_PATHS = setOf("/api/v1/info", "/api/v1/agent-setup", "/api/v1/csrf")
    }
}

@Configuration
@EnableConfigurationProperties(RateLimitSettings::class)
class RateLimitWebConfiguration(
    private val limits: RateLimits,
) : WebMvcConfigurer {
    @Bean
    fun apiRateLimitInterceptor(): ApiRateLimitInterceptor = ApiRateLimitInterceptor(limits)

    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(apiRateLimitInterceptor()).addPathPatterns("/api/v1/**")
    }
}
