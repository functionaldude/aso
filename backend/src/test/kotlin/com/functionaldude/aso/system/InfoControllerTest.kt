package com.functionaldude.aso.system

import com.functionaldude.aso.identity.RateLimits
import com.functionaldude.aso.questions.EngagementTime
import com.functionaldude.aso.web.SpaController
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

@AutoConfigureMockMvc(addFilters = false)
@WebMvcTest(InfoController::class, SpaController::class)
@Import(RateLimits::class, EngagementTime::class)
@TestPropertySource(properties = ["aso.version=0.0.1-SNAPSHOT"])
class InfoControllerTest(
    @Autowired private val mvc: MockMvc,
) {
    @Test
    fun `info exposes application name and version`() {
        mvc.get("/api/v1/info").andExpect {
            status { isOk() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
            jsonPath("$.name") { value("aso") }
            jsonPath("$.version") { value("0.0.1-SNAPSHOT") }
        }
    }

    @Test
    fun `unknown API routes return a problem instead of the frontend`() {
        mvc.get("/api/v1/missing").andExpect {
            status { isNotFound() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
            jsonPath("$.status") { value(404) }
        }
    }

    @Test
    fun `known client routes forward to the entry point`() {
        mvc.get("/about").andExpect {
            status { isOk() }
            forwardedUrl("/index.html")
        }
    }
}
