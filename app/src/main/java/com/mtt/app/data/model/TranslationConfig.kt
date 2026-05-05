package com.mtt.app.data.model

/**
 * Configuration for a translation execution run.
 *
 * @param mode Translation, polish, or proofread
 * @param model Target LLM model with context window info
 * @param sourceLang Source language display name (e.g. "英语")
 * @param targetLang Target language display name (e.g. "中文")
 * @param glossaryEntries Glossary term entries for consistent translation
 * @param temperature LLM temperature (0.0–1.0, lower = more deterministic)
 * @param maxTokens Maximum output tokens per batch
 * @param batchSize Number of texts to send in a single API call (1–200, default 50)
 * @param concurrency Number of batches to process in parallel (1–10, default 1)
 */
data class TranslationConfig(
    val mode: TranslationMode,
    val model: ModelInfo,
    val sourceLang: String,
    val targetLang: String,
    val glossaryEntries: List<GlossaryEntryEntity> = emptyList(),
    val temperature: Float = 0.3f,
    val maxTokens: Int = 4096,
    val batchSize: Int = 50,
    val concurrency: Int = 1
)
