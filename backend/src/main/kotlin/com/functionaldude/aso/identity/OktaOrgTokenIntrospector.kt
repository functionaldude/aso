package com.functionaldude.aso.identity

import com.fasterxml.jackson.annotation.JsonProperty
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.core.DefaultOAuth2AuthenticatedPrincipal
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal
import org.springframework.security.oauth2.server.resource.introspection.BadOpaqueTokenException
import org.springframework.security.oauth2.server.resource.introspection.OpaqueTokenIntrospector
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.MapperFeature
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Duration
import java.time.Instant

internal data class OktaIntrospection(
    val active: Boolean? = null,
    @get:JsonProperty("client_id") val clientId: String? = null,
    @get:JsonProperty("token_type") val tokenType: String? = null,
    val iss: String? = null,
    val exp: Long? = null,
    val iat: Long? = null,
    val nbf: Long? = null,
    val scope: String? = null,
    val sub: String? = null,
    val uid: String? = null,
)

internal data class OktaUserInfo(
    val sub: String? = null,
    val name: String? = null,
    val email: String? = null,
)

/** Org access tokens remain opaque: only Okta's responses, never locally decoded token claims, are trusted. */
class OktaOrgTokenIntrospector(
    private val settings: IdentitySettings,
    private val clock: Clock,
) : OpaqueTokenIntrospector {
    private val json =
        JsonMapper
            .builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .build()
    private val http =
        HttpClient
            .newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()

    override fun introspect(token: String): OAuth2AuthenticatedPrincipal {
        try {
            val response =
                json.readValue(
                    send(
                        HttpRequest
                            .newBuilder(URI("${settings.issuer}/oauth2/v1/introspect?client_id=${form(settings.clientId)}"))
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .POST(HttpRequest.BodyPublishers.ofString("token=${form(token)}&token_type_hint=access_token")),
                    ),
                    OktaIntrospection::class.java,
                )
            val now = clock.instant()
            val expiry = response.exp?.let(Instant::ofEpochSecond) ?: invalid()
            val scopes =
                response.scope
                    ?.split(' ')
                    ?.takeIf { it.all(::validScope) }
                    ?.toSet() ?: invalid()
            if (response.active != true || response.iss != settings.issuer || response.clientId != settings.clientId ||
                response.tokenType != "Bearer" || !expiry.isAfter(now) || response.sub.isNullOrBlank() ||
                response.uid.isNullOrBlank() || response.uid.length > 255 || !scopes.containsAll(listOf("openid", "groups")) ||
                response.nbf?.let { Instant.ofEpochSecond(it).isAfter(now) } == true ||
                response.iat?.let { Instant.ofEpochSecond(it).isAfter(now) || it >= response.exp } == true
            ) {
                invalid()
            }
            // The group-claim name is configurable, so this protocol boundary selects it from JSON before returning typed claims.
            val userInfo =
                json.readTree(
                    send(
                        HttpRequest
                            .newBuilder(URI("${settings.issuer}/oauth2/v1/userinfo"))
                            .header("Authorization", "Bearer $token")
                            .GET(),
                    ),
                )
            val user = json.treeToValue(userInfo, OktaUserInfo::class.java)
            if (user.sub != response.uid) invalid()
            val groupClaim = userInfo[settings.groupsClaim] ?: invalid()
            if (!groupClaim.isArray || groupClaim.size() > 1024 || groupClaim.any { !it.isString || it.asString().isBlank() }) invalid()
            val groups = groupClaim.toList().map { it.asString() }
            // Calls can cross the expiration boundary; never create an already-expired authenticated principal.
            if (!expiry.isAfter(clock.instant())) invalid()
            val claims =
                mutableMapOf<String, Any>(
                    "iss" to settings.issuer,
                    "sub" to response.uid,
                    "exp" to expiry,
                    "client_id" to settings.clientId,
                    "scope" to scopes,
                    settings.groupsClaim to groups,
                )
            response.iat?.let { claims["iat"] = Instant.ofEpochSecond(it) }
            user.name?.let { claims["name"] = it }
            user.email?.let { claims["email"] = it }
            return DefaultOAuth2AuthenticatedPrincipal(response.uid, claims, scopes.map { SimpleGrantedAuthority("SCOPE_$it") })
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            invalid()
        } catch (_: Exception) {
            invalid()
        }
    }

    private fun send(request: HttpRequest.Builder): ByteArray {
        val response =
            http.send(
                request.timeout(Duration.ofSeconds(5)).header("Accept", "application/json").build(),
                HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofByteArray(), 65536),
            )
        if (response.statusCode() != 200) invalid()
        return response.body()
    }

    private fun form(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    private fun validScope(scope: String): Boolean = scope.isNotEmpty() && scope.all { it.code in 0x21..0x7e && it != '"' && it != '\\' }

    private fun invalid(): Nothing = throw BadOpaqueTokenException("Invalid ASO access token")
}
