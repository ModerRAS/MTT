package com.mtt.app.data.model

import kotlinx.serialization.Serializable

/**
 * A term extracted by AI from source text, with a suggested translation and category.
 *
 * Used by [com.mtt.app.domain.usecase.ExtractTermsUseCase] during AI-powered term extraction.
 *
 * @param sourceTerm     The source-language term as it appears in the text
 * @param suggestedTarget  Suggested target-language translation (may be empty if LLM cannot infer)
 * @param category       Semantic category: "person", "place", "tech", "other", or "" if unknown
 */
@Serializable
data class ExtractedTerm(
    val sourceTerm: String,
    val suggestedTarget: String,
    val category: String = ""
)
