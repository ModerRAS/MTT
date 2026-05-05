package com.mtt.app.data.io

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory repository for sharing source texts across ViewModels.
 *
 * Stores the key-value map loaded from MTool JSON files, accessible by
 * both [com.mtt.app.ui.translation.TranslationViewModel] (writer) and
 * [com.mtt.app.ui.glossary.GlossaryViewModel] (reader).
 *
 * This repository holds no file I/O — it is a pure in-memory cache.
 */
@Singleton
class SourceTextRepository @Inject constructor() {

    private val _sourceTexts = MutableStateFlow<Map<String, String>>(emptyMap())
    val sourceTexts: StateFlow<Map<String, String>> = _sourceTexts.asStateFlow()

    /**
     * Update the stored source texts map.
     * Called by [TranslationViewModel] after successfully loading a JSON file.
     */
    fun setSourceTexts(texts: Map<String, String>) {
        _sourceTexts.value = texts
    }

    /** Clear all stored source texts. */
    fun clear() {
        _sourceTexts.value = emptyMap()
    }
}