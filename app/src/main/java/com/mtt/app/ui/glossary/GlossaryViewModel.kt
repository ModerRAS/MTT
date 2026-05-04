package com.mtt.app.ui.glossary

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mtt.app.data.local.dao.GlossaryDao
import com.mtt.app.data.model.GlossaryEntryEntity
import com.mtt.app.domain.glossary.GlossaryEntry
import com.mtt.app.domain.glossary.GlossaryEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UI state for the glossary screen.
 */
data class GlossaryUiState(
    val glossaryCount: Int = 0,
    val prohibitionCount: Int = 0,
    val previewEntries: List<GlossaryEntry> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val successMessage: String? = null
)

/**
 * ViewModel for GlossaryScreen.
 * Handles glossary and prohibition list import, preview, and clear operations.
 */
@HiltViewModel
class GlossaryViewModel @Inject constructor(
    private val glossaryDao: GlossaryDao
) : ViewModel() {

    private val _uiState = MutableStateFlow(GlossaryUiState())
    val uiState: StateFlow<GlossaryUiState> = _uiState.asStateFlow()

    // Current project ID - in a real app, this would come from a project manager
    private var currentProjectId: String = "default_project"

    init {
        loadGlossaryData()
    }

    /**
     * Load glossary and prohibition counts from database.
     */
    fun loadGlossaryData() {
        viewModelScope.launch {
            try {
                val entries = glossaryDao.getByProjectId(currentProjectId)
                val glossaryEntries = entries.map { it.toGlossaryEntry() }
                val prohibitionEntries = glossaryEntries.filter { it.target.isEmpty() }
                val glossaryOnlyEntries = glossaryEntries.filter { it.target.isNotEmpty() }

                _uiState.update {
                    it.copy(
                        glossaryCount = glossaryOnlyEntries.size,
                        prohibitionCount = prohibitionEntries.size,
                        previewEntries = glossaryEntries.take(20)
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(errorMessage = "加载术语表失败: ${e.message}")
                }
            }
        }
    }

    /**
     * Import glossary from CSV file.
     * CSV format: "source, target" per line, no header.
     */
    fun importGlossaryFromCsv(uri: Uri, content: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null, successMessage = null) }
            try {
                val entries = parseCsvContent(content)
                val entities = entries.map { entry ->
                    GlossaryEntryEntity(
                        projectId = currentProjectId,
                        sourceTerm = entry.source,
                        targetTerm = entry.target,
                        matchType = GlossaryEntryEntity.MATCH_TYPE_EXACT
                    )
                }

                glossaryDao.insertAll(entities)
                loadGlossaryData()

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        successMessage = "成功导入 ${entries.size} 条术语"
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "导入术语表失败: ${e.message}"
                    )
                }
            }
        }
    }

    /**
     * Import prohibition list from text file.
     * One term per line.
     */
    fun importProhibitionList(uri: Uri, content: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null, successMessage = null) }
            try {
                val terms = content.lines()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }

                val entities = terms.map { term ->
                    GlossaryEntryEntity(
                        projectId = currentProjectId,
                        sourceTerm = term,
                        targetTerm = "", // Empty target indicates prohibition
                        matchType = GlossaryEntryEntity.MATCH_TYPE_EXACT
                    )
                }

                glossaryDao.insertAll(entities)
                loadGlossaryData()

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        successMessage = "成功导入 ${terms.size} 条禁翻术语"
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "导入禁翻表失败: ${e.message}"
                    )
                }
            }
        }
    }

    /**
     * Clear current project's glossary and prohibition list.
     */
    fun clearGlossary() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null, successMessage = null) }
            try {
                glossaryDao.deleteByProjectId(currentProjectId)
                loadGlossaryData()

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        successMessage = "已清空术语表"
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "清空术语表失败: ${e.message}"
                    )
                }
            }
        }
    }

    /**
     * Clear success/error messages.
     */
    fun clearMessages() {
        _uiState.update { it.copy(errorMessage = null, successMessage = null) }
    }

    /**
     * Parse CSV content into glossary entries.
     * Format: "source, target" per line, no header.
     */
    private fun parseCsvContent(content: String): List<GlossaryEntry> {
        return content.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { line ->
                val parts = line.split(",", limit = 2)
                if (parts.size == 2) {
                    val source = parts[0].trim()
                    val target = parts[1].trim()
                    if (source.isNotEmpty() && target.isNotEmpty()) {
                        GlossaryEntry(source = source, target = target)
                    } else {
                        null
                    }
                } else {
                    null
                }
            }
    }

    /**
     * Convert GlossaryEntryEntity to GlossaryEntry.
     */
    private fun GlossaryEntryEntity.toGlossaryEntry(): GlossaryEntry {
        return GlossaryEntry(
            source = sourceTerm,
            target = targetTerm,
            isRegex = matchType == GlossaryEntryEntity.MATCH_TYPE_REGEX,
            isCaseSensitive = matchType != GlossaryEntryEntity.MATCH_TYPE_CASE_INSENSITIVE
        )
    }
}