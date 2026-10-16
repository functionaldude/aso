package com.functionaldude.aso.questions

import org.commonmark.node.AbstractVisitor
import org.commonmark.node.Image
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import java.util.UUID

class ContentFailure(
    val status: Int,
    val code: String,
    override val message: String,
) : RuntimeException(message)

fun invalid(message: String): Nothing = throw ContentFailure(400, "invalid_input", message)

/** Rejects known credential shapes without echoing the submitted value. Not a general secret detector. */
@Component
class ContentPolicy {
    private val credentials =
        listOf(
            Regex("-----BEGIN (?:[A-Z]+ )*PRIVATE KEY-----"),
            Regex("\\b(?:AKIA|ASIA)[A-Z0-9]{16}\\b"),
            Regex("\\b(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|sk-(?:proj-)?[A-Za-z0-9_-]{20,})"),
            Regex("(?i)\\b(?:authorization\\s*[:=]\\s*(?:bearer|basic)\\s+)[A-Za-z0-9._~+/=-]{8,}"),
            Regex("\\beyJ[A-Za-z0-9_-]+\\.eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+"),
            Regex(
                "(?i)\\b(?:password|passwd|client_secret|api[_-]?key|access[_-]?token|refresh[_-]?token)\\s*[\"']?\\s*[:=]\\s*[\"']?(?!<redacted>|\\[redacted\\]|REDACTED\\b)[^\\s\"'`<>]{4,}",
            ),
            Regex("(?i)\\b[a-z][a-z0-9+.-]*://[^\\s/:]+:[^\\s/@]+@"),
        )
    private val parser = Parser.builder().build()
    private val renderer =
        HtmlRenderer
            .builder()
            .escapeHtml(true)
            .sanitizeUrls(true)
            .build()

    fun fields(
        node: JsonNode,
        allowed: Set<String>,
    ) {
        if (!node.isObject) invalid("Expected an object")
        if (node.propertyNames().any { it !in allowed }) invalid("Unknown fields are not allowed; authorship is assigned from your session")
    }

    fun text(
        node: JsonNode,
        field: String,
        max: Int,
        required: Boolean = false,
    ): String? {
        val value = node.path(field)
        if (value.isMissingNode || value.isNull) {
            if (required) invalid("$field is required")
            return null
        }
        if (!value.isString) invalid("$field must be text")
        val result = value.asString().trim()
        if (result.length > max || (required && result.isEmpty())) invalid("$field must contain ${if (required) 1 else 0}–$max characters")
        if (result.any { it == '\u0000' || (it.code < 32 && it !in "\n\r\t") }) invalid("$field contains unsupported control characters")
        if (credentials.any { it.containsMatchIn(result) }) {
            invalid("$field contains recognizable credential material; remove credentials and replace them with <redacted>")
        }
        return result.ifEmpty { null }
    }

    fun uuid(
        value: String,
        field: String,
    ): UUID =
        try {
            UUID.fromString(value).also { if (it.toString() != value.lowercase()) invalid("$field must be a canonical UUID") }
        } catch (_: IllegalArgumentException) {
            invalid("$field must be a UUID")
        }

    fun requestId(node: JsonNode): UUID = uuid(text(node, "requestId", 36, true)!!, "requestId")

    fun tag(value: String): String =
        value.lowercase().also {
            if (credentials.any { pattern -> pattern.containsMatchIn(value) }) {
                invalid("tags contains recognizable credential material; remove credentials")
            }
            if (!Regex("[a-z0-9][a-z0-9.+#-]{0,39}").matches(it)) {
                invalid("Tags must be 1–40 letters, digits, ., +, # or - and begin with a letter or digit")
            }
        }

    fun tags(node: JsonNode): List<String> {
        val tags = node.path("tags")
        if (tags.isMissingNode) return emptyList()
        if (!tags.isArray || tags.size() > 5 || tags.any { !it.isString }) invalid("tags must be an array of at most 5 strings")
        return tags
            .asSequence()
            .map { tag(it.asString()) }
            .distinct()
            .sorted()
            .toList()
    }

    fun html(markdown: String): String {
        val document = parser.parse(markdown)
        // Images can cause unsolicited external requests. Keep only their visible alternative text.
        document.accept(
            object : AbstractVisitor() {
                override fun visit(image: Image) {
                    var child = image.firstChild
                    while (child != null) {
                        val next = child.next
                        image.insertBefore(child)
                        child = next
                    }
                    image.unlink()
                }
            },
        )
        return renderer.render(document)
    }
}
