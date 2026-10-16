package com.functionaldude.aso.questions

import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
class LifecycleController(
    private val lifecycle: LifecycleService,
    private val questions: QuestionService,
) {
    @GetMapping("/api/v1/content/{kind}/{id}")
    fun read(
        authentication: Authentication,
        @PathVariable kind: String,
        @PathVariable id: String,
    ) = lifecycle.read(authentication, kind, id)

    @GetMapping("/api/v1/admin/content/{kind}/{id}")
    fun moderationRead(
        authentication: Authentication,
        @PathVariable kind: String,
        @PathVariable id: String,
    ) = lifecycle.read(authentication, kind, id, moderation = true)

    @PostMapping("/api/v1/questions/{id}/edit")
    fun editQuestion(
        authentication: Authentication,
        @PathVariable id: String,
        @RequestBody input: EditQuestionInput,
    ) = lifecycle.editQuestion(authentication, id, input)

    @PostMapping("/api/v1/answers/{id}/edit")
    fun editAnswer(
        authentication: Authentication,
        @PathVariable id: String,
        @RequestBody input: EditAnswerInput,
    ) = lifecycle.editAnswer(authentication, id, input)

    @PostMapping("/api/v1/comments/{id}/edit")
    fun editComment(
        authentication: Authentication,
        @PathVariable id: String,
        @RequestBody input: EditCommentInput,
    ) = lifecycle.editComment(authentication, id, input)

    @GetMapping("/api/v1/content/{kind}/{id}/comments")
    fun comments(
        authentication: Authentication,
        @PathVariable kind: String,
        @PathVariable id: String,
        @RequestParam(defaultValue = "20") limit: Int,
        @RequestParam(required = false) cursor: String?,
    ) = lifecycle.comments(authentication, kind, id, limit, cursor)

    @PostMapping("/api/v1/content/{kind}/{id}/comments")
    fun comment(
        authentication: Authentication,
        @PathVariable kind: String,
        @PathVariable id: String,
        @RequestBody input: CreateCommentInput,
    ) = lifecycle.createComment(authentication, kind, id, input)

    @PostMapping("/api/v1/questions/{id}/acceptance")
    fun accept(
        authentication: Authentication,
        @PathVariable id: String,
        @RequestBody input: AcceptanceInput,
    ) = lifecycle.accept(authentication, id, input)

    @GetMapping("/api/v1/admin/questions")
    fun moderation(
        authentication: Authentication,
        @RequestParam(defaultValue = "20") limit: Int,
        @RequestParam(required = false) cursor: String?,
    ) = questions.list(authentication, state = "all", limit = limit, cursor = cursor, moderation = true)

    @GetMapping("/api/v1/admin/questions/{id}")
    fun moderationDetail(
        authentication: Authentication,
        @PathVariable id: String,
    ) = questions.detail(authentication, id, moderation = true)

    @GetMapping("/api/v1/admin/questions/{id}/answers")
    fun moderationAnswers(
        authentication: Authentication,
        @PathVariable id: String,
        @RequestParam(defaultValue = "20") limit: Int,
        @RequestParam(required = false) cursor: String?,
    ) = questions.answers(authentication, id, limit, cursor, moderation = true)

    @GetMapping("/api/v1/admin/content/{kind}/{id}/comments")
    fun moderationComments(
        authentication: Authentication,
        @PathVariable kind: String,
        @PathVariable id: String,
        @RequestParam(defaultValue = "20") limit: Int,
        @RequestParam(required = false) cursor: String?,
    ) = lifecycle.comments(authentication, kind, id, limit, cursor, moderation = true)

    @PostMapping("/api/v1/admin/content/{kind}/{id}/visibility")
    fun visibility(
        authentication: Authentication,
        @PathVariable kind: String,
        @PathVariable id: String,
        @RequestBody input: VisibilityInput,
    ) = lifecycle.visibility(authentication, kind, id, input)

    @PostMapping("/api/v1/admin/questions/{id}/edit")
    fun moderateQuestion(
        authentication: Authentication,
        @PathVariable id: String,
        @RequestBody input: EditQuestionInput,
    ) = lifecycle.editQuestion(authentication, id, input, moderation = true)

    @PostMapping("/api/v1/admin/answers/{id}/edit")
    fun moderateAnswer(
        authentication: Authentication,
        @PathVariable id: String,
        @RequestBody input: EditAnswerInput,
    ) = lifecycle.editAnswer(authentication, id, input, moderation = true)

    @PostMapping("/api/v1/admin/comments/{id}/edit")
    fun moderateComment(
        authentication: Authentication,
        @PathVariable id: String,
        @RequestBody input: EditCommentInput,
    ) = lifecycle.editComment(authentication, id, input, moderation = true)
}
