package com.functionaldude.aso.web

import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping

@Controller
class SpaController {
    // Keep this list aligned with Angular's routes. Never forward /api or /actuator to HTML.
    @GetMapping("/", "/about")
    fun index() = "forward:/index.html"
}
