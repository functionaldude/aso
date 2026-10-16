package com.functionaldude.aso.questions

import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.Nulls

data class CreateQuestionInput(
    val requestId: String? = null,
    val title: String? = null,
    val body: String? = null,
    @param:JsonSetter(nulls = Nulls.FAIL)
    val tags: List<String> = emptyList(),
    val context: TechnicalContextInput? = null,
)

data class TechnicalContextInput(
    val errorText: String? = null,
    val dependencies: String? = null,
    val versions: String? = null,
    val repository: String? = null,
)

data class CreateAnswerInput(
    val requestId: String? = null,
    val body: String? = null,
    val applicability: String? = null,
    val verification: String? = null,
)

data class ListQuestionsArguments(
    val tag: String? = null,
    val state: String? = null,
    val limit: Int = 20,
    val cursor: String? = null,
)

data class GetQuestionArguments(
    val questionId: String? = null,
)

data class ListAnswersArguments(
    val questionId: String? = null,
    val limit: Int = 20,
    val cursor: String? = null,
)

data class CreateAnswerArguments(
    val questionId: String? = null,
    val requestId: String? = null,
    val body: String? = null,
    val applicability: String? = null,
    val verification: String? = null,
) {
    fun input() = CreateAnswerInput(requestId, body, applicability, verification)
}

const val INVALID_JSON_INPUT = "Invalid input: check field names, required values, and JSON types"

// Edits replace editable fields; expectedVersion is mandatory and never silently refreshed.
data class EditQuestionInput(
    val expectedVersion: Int? = null,
    val title: String? = null,
    val body: String? = null,
    @param:JsonSetter(nulls = Nulls.FAIL)
    val tags: List<String> = emptyList(),
    val context: TechnicalContextInput? = null,
)

data class EditAnswerInput(
    val expectedVersion: Int? = null,
    val body: String? = null,
    val applicability: String? = null,
    val verification: String? = null,
)

data class EditCommentInput(
    val expectedVersion: Int? = null,
    val body: String? = null,
)

data class CreateCommentInput(
    val requestId: String? = null,
    val body: String? = null,
)

data class AcceptanceInput(
    val expectedVersion: Int? = null,
    val answerId: String? = null,
)

data class VisibilityInput(
    val expectedVersion: Int? = null,
    val hidden: Boolean? = null,
)

data class ListCommentsArguments(
    val kind: String? = null,
    val targetId: String? = null,
    val limit: Int = 20,
    val cursor: String? = null,
)

data class CreateCommentArguments(
    val kind: String? = null,
    val targetId: String? = null,
    val requestId: String? = null,
    val body: String? = null,
)

data class EditQuestionArguments(
    val questionId: String? = null,
    val content: EditQuestionInput? = null,
)

data class EditAnswerArguments(
    val answerId: String? = null,
    val content: EditAnswerInput? = null,
)

data class EditCommentArguments(
    val commentId: String? = null,
    val content: EditCommentInput? = null,
)
