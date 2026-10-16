package com.functionaldude.aso.web

import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping

@Controller
class SpaController {
    // Keep this list aligned with Angular's routes. Never forward /api or /actuator to HTML.
    @GetMapping(
        "/",
        "/about",
        "/moderation",
        "/moderation/{id:[0-9a-fA-F-]{36}}",
        "/questions",
        "/questions/new",
        "/questions/{id:[0-9a-fA-F-]{36}}",
    )
    fun index() = "forward:/index.html"
}
