package com.functionaldude.aso.system

import org.springframework.beans.factory.annotation.Value
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

data class ApplicationInfo(
    val name: String,
    val version: String,
)

@RestController
class InfoController(
    @Value("\${aso.version}") private val version: String,
) {
    @GetMapping("/api/v1/info")
    fun info() = ApplicationInfo(name = "aso", version = version)
}
