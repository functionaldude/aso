package com.functionaldude.aso.identity

import jakarta.servlet.DispatcherType
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
import org.springframework.security.oauth2.client.registration.ClientRegistrations
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers
import org.springframework.security.oauth2.core.ClientAuthenticationMethod
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.core.OAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtDecoders
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.HttpStatusEntryPoint

@Configuration
@EnableConfigurationProperties(IdentitySettings::class)
class SecurityConfiguration(
    private val settings: IdentitySettings,
) {
    @Bean
    fun clients(): ClientRegistrationRepository =
        InMemoryClientRegistrationRepository(
            ClientRegistrations
                .fromIssuerLocation(settings.issuer)
                .registrationId("oidc")
                .clientId(settings.clientId)
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .scope("openid", "profile", "email")
                .redirectUri("${settings.publicUrl}/login/oauth2/code/oidc")
                .build(),
        )

    @Bean
    fun jwtDecoder(): JwtDecoder {
        val decoder = JwtDecoders.fromIssuerLocation<NimbusJwtDecoder>(settings.issuer)
        decoder.setJwtValidator(
            DelegatingOAuth2TokenValidator(
                JwtValidators.createDefaultWithIssuer(settings.issuer),
                mcpTokenValidator(settings.mcpAudience, settings.clientId),
            ),
        )
        return decoder
    }

    @Bean
    @Order(1)
    fun mcp(
        http: HttpSecurity,
        decoder: JwtDecoder,
    ): SecurityFilterChain {
        http
            .securityMatcher("/mcp", "/mcp/**")
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .csrf { it.disable() }
            .requestCache { it.disable() }
            .authorizeHttpRequests { it.anyRequest().authenticated() }
            .oauth2ResourceServer { resource ->
                resource.jwt { it.decoder(decoder) }
                resource.authenticationEntryPoint { _, response, _ ->
                    response.status = 401
                    response.setHeader("WWW-Authenticate", "Bearer resource_metadata=\"${settings.metadata}\"")
                    response.contentType = "application/json"
                    response.writer.write("{\"error\":\"unauthorized\"}")
                }
                resource.accessDeniedHandler { _, response, _ -> response.sendError(403) }
            }
        return http.build()
    }

    @Bean
    @Order(2)
    fun web(
        http: HttpSecurity,
        clients: ClientRegistrationRepository,
    ): SecurityFilterChain {
        val resolver = DefaultOAuth2AuthorizationRequestResolver(clients, "/oauth2/authorization")
        resolver.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce())
        val logout = OidcClientInitiatedLogoutSuccessHandler(clients)
        logout.setPostLogoutRedirectUri(settings.publicUrl + "/?signedOut")
        http
            .authorizeHttpRequests {
                it
                    .dispatcherTypeMatchers(DispatcherType.ERROR, DispatcherType.FORWARD)
                    .permitAll()
                    .requestMatchers(
                        "/",
                        "/index.html",
                        "/about",
                        "/agent-setup",
                        "/api/v1/agent-setup",
                        "/moderation",
                        "/moderation/{id:[0-9a-fA-F-]{36}}",
                        "/questions",
                        "/questions/new",
                        "/questions/{id:[0-9a-fA-F-]{36}}",
                        "/*.js",
                        "/*.css",
                        "/favicon.svg",
                        "/media/**",
                        "/api/v1/info",
                        "/api/v1/csrf",
                        "/actuator/health",
                        "/.well-known/oauth-protected-resource",
                        "/.well-known/oauth-protected-resource/mcp",
                        "/oauth2/**",
                        "/login/**",
                        "/error",
                    ).permitAll()
                    .requestMatchers("/api/**")
                    .authenticated()
                    .anyRequest()
                    .denyAll()
            }.exceptionHandling { it.authenticationEntryPoint(HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)) }
            .oauth2Login {
                it
                    .authorizationEndpoint { endpoint -> endpoint.authorizationRequestResolver(resolver) }
                    .defaultSuccessUrl(settings.publicUrl + "/", true)
                    .failureUrl(settings.publicUrl + "/?loginError")
            }.logout { it.logoutSuccessHandler(logout) }
        return http.build()
    }

    companion object {
        fun mcpTokenValidator(
            resourceAudience: String,
            clientId: String,
        ) = OAuth2TokenValidator<Jwt> { token ->
            val intendedAudience = token.audience.orEmpty().any { it == resourceAudience || it == clientId }
            // The provider must emit scope/scp only in access tokens, so ID tokens cannot be replayed.
            if (intendedAudience && !token.subject.isNullOrBlank() && hasAccessScope(token.claims)) {
                OAuth2TokenValidatorResult.success()
            } else {
                OAuth2TokenValidatorResult.failure(
                    OAuth2Error("invalid_token", "Expected ASO audience, subject and access-token scope", null),
                )
            }
        }

        // Reject a malformed claim even if the other is valid; both forms must describe the same scope set.
        private fun hasAccessScope(claims: Map<String, Any>): Boolean {
            val scope = if (claims.containsKey("scope")) scopeString(claims["scope"]) ?: return false else null
            val scp = if (claims.containsKey("scp")) scopeArray(claims["scp"]) ?: return false else null
            if (scope != null && scp != null && scope != scp) return false
            return "openid" in (scope ?: scp ?: return false)
        }

        // OAuth's scope claim is a space-delimited string; null means it is malformed.
        private fun scopeString(value: Any?): Set<String>? =
            (value as? String)?.split(' ')?.takeIf { scopes -> scopes.all(::validScope) }?.toSet()

        // Okta's scp claim is an array; do not silently discard malformed entries.
        private fun scopeArray(value: Any?): Set<String>? {
            val scopes = value as? Collection<*> ?: return null
            if (scopes.isEmpty() || scopes.any { it !is String || !validScope(it) }) return null
            return scopes.filterIsInstance<String>().toSet()
        }

        // RFC 6749 scope tokens allow printable ASCII except space, double quote, and backslash.
        private fun validScope(scope: String): Boolean =
            scope.isNotEmpty() && scope.all { it.code in 0x21..0x7e && it != '"' && it != '\\' }
    }
}
