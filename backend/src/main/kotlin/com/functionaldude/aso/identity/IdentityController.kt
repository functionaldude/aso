package com.functionaldude.aso.identity

import org.springframework.security.core.Authentication
import org.springframework.security.web.csrf.CsrfToken
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

data class CsrfResponse(
    val token: String,
    val parameterName: String,
)

@RestController
class IdentityController(
    private val identities: IdentityService,
) {
    @GetMapping("/api/v1/identity")
    fun current(authentication: Authentication) = identities.current(authentication, human = true)

    // A read-only boundary probe; no role assignment or product operation exists in goal 001.
    @GetMapping("/api/v1/admin/identity")
    fun administrator(authentication: Authentication) = identities.administrator(authentication)

    @GetMapping("/api/v1/csrf")
    fun csrf(token: CsrfToken) = CsrfResponse(token.token, token.parameterName)
}
