package com.functionaldude.aso.search

import com.functionaldude.aso.identity.IdentityService
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
class SearchController(
    private val search: SearchService,
    private val identities: IdentityService,
    private val index: SearchIndex,
) {
    @GetMapping("/api/v1/search")
    fun search(
        authentication: Authentication,
        @RequestParam query: String,
        @RequestParam(required = false) tag: String?,
        @RequestParam(defaultValue = "all") state: String,
        @RequestParam(defaultValue = "20") limit: Int,
        @RequestParam(required = false) cursor: String?,
    ) = search.search(authentication, SearchInput(query, tag, state, limit, cursor))

    @GetMapping("/api/v1/admin/search/index")
    fun status(authentication: Authentication): IndexStatus {
        identities.administrator(authentication)
        return index.status()
    }

    @PostMapping("/api/v1/admin/search/index/retry")
    fun retry(authentication: Authentication): IndexStatus {
        identities.administrator(authentication)
        return index.retry()
    }

    @PostMapping("/api/v1/admin/search/index/rebuild")
    fun rebuild(authentication: Authentication): IndexStatus {
        identities.administrator(authentication)
        return index.rebuild()
    }
}
