package com.functionaldude.aso.questions

import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice
import tools.jackson.databind.JsonNode

@RestController
class QuestionController(
    private val questions: QuestionService,
) {
    @GetMapping("/api/v1/questions")
    fun list(
        authentication: Authentication,
        @RequestParam(required = false) tag: String?,
        @RequestParam(defaultValue = "open") state: String,
        @RequestParam(defaultValue = "20") limit: Int,
        @RequestParam(required = false) cursor: String?,
    ) = questions.list(authentication, tag, state, limit, cursor)

    @GetMapping("/api/v1/questions/{id}")
    fun detail(
        authentication: Authentication,
        @PathVariable id: String,
    ) = questions.detail(authentication, id)

    @PostMapping("/api/v1/questions")
    fun create(
        authentication: Authentication,
        @RequestBody input: JsonNode,
    ) = questions.createQuestion(authentication, input)

    @GetMapping("/api/v1/questions/{id}/answers")
    fun answers(
        authentication: Authentication,
        @PathVariable id: String,
        @RequestParam(defaultValue = "20") limit: Int,
        @RequestParam(required = false) cursor: String?,
    ) = questions.answers(authentication, id, limit, cursor)

    @PostMapping("/api/v1/questions/{id}/answers")
    fun answer(
        authentication: Authentication,
        @PathVariable id: String,
        @RequestBody input: JsonNode,
    ) = questions.createAnswer(authentication, id, input)
}

@RestControllerAdvice
class ContentErrors {
    @ExceptionHandler(ContentFailure::class)
    fun content(error: ContentFailure): ResponseEntity<ProblemDetail> {
        val problem = ProblemDetail.forStatus(error.status)
        problem.title = error.code
        problem.detail = error.message
        problem.setProperty("code", error.code)
        return ResponseEntity.status(error.status).body(problem)
    }
}
