package com.mtt.app.data.model

import kotlinx.serialization.Serializable

/**
 * A term extracted by AI from source text, with a suggested translation and explanation.
 *
 * Used by [com.mtt.app.domain.usecase.ExtractTermsUseCase] during AI-powered term extraction.
 *
 * @param sourceTerm        The source-language term as it appears in the text
 * @param suggestedTarget   Suggested target-language (Chinese) translation
 * @param explanation       Brief explanation of what this term means in context
 */
@Serializable
data class ExtractedTerm(
    val sourceTerm: String,
    val suggestedTarget: String,
    val explanation: String = ""
)
