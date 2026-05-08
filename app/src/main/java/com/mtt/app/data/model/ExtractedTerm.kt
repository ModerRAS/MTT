package com.mtt.app.data.model

import kotlinx.serialization.Serializable

/**
 * An item extracted by AI from source text via [com.mtt.app.domain.usecase.ExtractTermsUseCase].
 *
 * Three types, matching AiNiee's analysis extraction categories:
 * - [TYPE_CHARACTER]: Character/person names (category = gender: 男性/女性/其他)
 * - [TYPE_TERM]: Proper nouns, technical terms (category = category_path: 身份/物品/组织/地名/技能/种族/其他)
 * - [TYPE_NON_TRANSLATE]: Code tags, variables, placeholders (category = category: 标签/变量/占位符/其他)
 *
 * @param sourceTerm        The source text (term/name/marker)
 * @param suggestedTarget   Suggested target translation (empty for non_translate items)
 * @param type              Item type: "character", "term", or "non_translate"
 * @param category          Category metadata (gender/category_path/category depending on type)
 * @param explanation       Brief explanation or note
 */
@Serializable
data class ExtractedTerm(
    val sourceTerm: String,
    val suggestedTarget: String,
    val type: String = TYPE_TERM,
    val category: String = "",
    val explanation: String = ""
) {
    companion object {
        const val TYPE_CHARACTER = "character"
        const val TYPE_TERM = "term"
        const val TYPE_NON_TRANSLATE = "non_translate"
    }
}
