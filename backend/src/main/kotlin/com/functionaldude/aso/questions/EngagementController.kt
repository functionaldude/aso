package com.functionaldude.aso.questions

import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
class EngagementController(
    private val engagement: EngagementService,
) {
    @PostMapping("/api/v1/content/{kind}/{id}/vote")
    fun vote(
        authentication: Authentication,
        @PathVariable kind: String,
        @PathVariable id: String,
        @RequestBody input: VoteInput,
    ) = engagement.vote(authentication, kind, id, input)

    @GetMapping("/api/v1/content/{kind}/{id}/votes")
    fun votes(
        authentication: Authentication,
        @PathVariable kind: String,
        @PathVariable id: String,
        @RequestParam(defaultValue = "20") limit: Int,
        @RequestParam(required = false) cursor: String?,
    ) = engagement.votes(authentication, kind, id, limit, cursor)

    @PostMapping("/api/v1/questions/{id}/occurrences")
    fun occurrence(
        authentication: Authentication,
        @PathVariable id: String,
        @RequestBody input: OccurrenceInput,
    ) = engagement.occurrence(authentication, id, input)
}
