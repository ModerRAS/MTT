package com.mtt.app.data.model

/**
 * UI model for glossary entries used in the presentation layer.
 * This is a plain data class, not a Room entity.
 *
 * @param id Database ID (0 for new entries)
 * @param projectId Project identifier this entry belongs to
 * @param sourceTerm The source language term
 * @param targetTerm The target language term
 * @param matchType Matching strategy: "EXACT", "REGEX", or "CASE_INSENSITIVE"
 * @param isProhibition Whether this is a prohibition rule (empty targetTerm)
 * @param info Optional description/remarks for the glossary entry
 */
data class GlossaryEntryUiModel(
    val id: Long = 0,
    val projectId: String,
    val sourceTerm: String,
    val targetTerm: String,
    val matchType: String = "EXACT",
    val isProhibition: Boolean = targetTerm.isEmpty(),
    val info: String = ""
)

/**
 * Extension function to convert entity to UI model.
 */
fun GlossaryEntryEntity.toUiModel(): GlossaryEntryUiModel = GlossaryEntryUiModel(
    id = id,
    projectId = projectId,
    sourceTerm = sourceTerm,
    targetTerm = targetTerm,
    matchType = matchType,
    isProhibition = targetTerm.isEmpty(),
    info = info
)

/**
 * Extension function to convert UI model to entity.
 */
fun GlossaryEntryUiModel.toEntity(): GlossaryEntryEntity = GlossaryEntryEntity(
    id = id,
    projectId = projectId,
    sourceTerm = sourceTerm,
    targetTerm = targetTerm,
    matchType = matchType,
    info = info
)