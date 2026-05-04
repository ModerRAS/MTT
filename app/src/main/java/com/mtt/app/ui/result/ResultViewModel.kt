package com.mtt.app.ui.result

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mtt.app.data.cache.CacheManager
import com.mtt.app.data.io.MtoolFileWriter
import com.mtt.app.data.model.CacheItemEntity
import com.mtt.app.data.model.TranslationStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Data class representing a translation result item for display.
 */
data class TranslationResultItem(
    val sourceText: String,
    val translatedText: String,
    val status: TranslationStatus,
    val model: String
)

/**
 * UI state for the result screen.
 */
sealed class ResultUiState {
    object Loading : ResultUiState()
    data class Success(val items: List<TranslationResultItem>) : ResultUiState()
    data class Error(val message: String) : ResultUiState()
}

/**
 * Filter options for translation results.
 */
data class ResultFilter(
    val statusFilter: Set<TranslationStatus> = emptySet(),
    val searchText: String = ""
)

/**
 * ViewModel for ResultScreen.
 * Handles loading translation results, filtering, and JSON export.
 */
@HiltViewModel
class ResultViewModel @Inject constructor(
    private val cacheManager: CacheManager,
    private val fileWriter: MtoolFileWriter
) : ViewModel() {

    private val _uiState = MutableStateFlow<ResultUiState>(ResultUiState.Loading)
    val uiState: StateFlow<ResultUiState> = _uiState.asStateFlow()

    private val _filter = MutableStateFlow(ResultFilter())
    val filter: StateFlow<ResultFilter> = _filter.asStateFlow()

    private val _allItems = MutableStateFlow<List<TranslationResultItem>>(emptyList())
    
    /**
     * Filtered items based on current filter state.
     */
    private val _filteredItems = MutableStateFlow<List<TranslationResultItem>>(emptyList())
    val filteredItems: StateFlow<List<TranslationResultItem>> = _filteredItems.asStateFlow()
    
    init {
        viewModelScope.launch {
            combine(_allItems, _filter) { items, filter ->
                items.filter { item ->
                    // Status filter
                    val statusMatch = filter.statusFilter.isEmpty() || 
                        filter.statusFilter.contains(item.status)
                    
                    // Search text filter (case-insensitive)
                    val searchMatch = filter.searchText.isBlank() ||
                        item.sourceText.contains(filter.searchText, ignoreCase = true) ||
                        item.translatedText.contains(filter.searchText, ignoreCase = true)
                    
                    statusMatch && searchMatch
                }
            }.collect { filtered ->
                _filteredItems.value = filtered
            }
        }
    }

    init {
        loadResults()
    }

    /**
     * Load translation results from cache.
     */
    fun loadResults() {
        viewModelScope.launch {
            _uiState.update { ResultUiState.Loading }
            try {
                val items = cacheManager.exportToJson()
                    .map { (sourceText, translatedText) ->
                        TranslationResultItem(
                            sourceText = sourceText,
                            translatedText = translatedText,
                            status = TranslationStatus.TRANSLATED,
                            model = ""
                        )
                    }
                _allItems.update { items }
                _uiState.update { ResultUiState.Success(items) }
            } catch (e: Exception) {
                _uiState.update { ResultUiState.Error("加载结果失败: ${e.message}") }
            }
        }
    }

    /**
     * Update status filter.
     */
    fun setStatusFilter(statuses: Set<TranslationStatus>) {
        _filter.update { it.copy(statusFilter = statuses) }
    }

    /**
     * Update search text filter.
     */
    fun setSearchText(text: String) {
        _filter.update { it.copy(searchText = text) }
    }

    /**
     * Clear all filters.
     */
    fun clearFilters() {
        _filter.update { ResultFilter() }
    }

    /**
     * Export results to JSON file via SAF.
     */
    fun exportToJson(uri: Uri) {
        viewModelScope.launch {
            try {
                val data = _allItems.value.associate { it.sourceText to it.translatedText }
                when (val result = fileWriter.writeToUri(uri, data)) {
                    is com.mtt.app.core.error.Result.Success -> {
                        // Export succeeded
                    }
                    is com.mtt.app.core.error.Result.Failure -> {
                        _uiState.update { 
                            ResultUiState.Error("导出失败: ${result.exception.message}") 
                        }
                    }
                }
            } catch (e: Exception) {
                _uiState.update { ResultUiState.Error("导出失败: ${e.message}") }
            }
        }
    }

    /**
     * Get status icon for display.
     */
    fun getStatusIcon(status: TranslationStatus): String {
        return when (status) {
            TranslationStatus.TRANSLATED -> "✓"
            TranslationStatus.POLISHED -> "✓"
            TranslationStatus.UNTRANSLATED -> "⏳"
            TranslationStatus.EXCLUDED -> "⊗"
        }
    }

    /**
     * Get status color for display.
     */
    fun getStatusColor(status: TranslationStatus): androidx.compose.ui.graphics.Color {
        return when (status) {
            TranslationStatus.TRANSLATED -> androidx.compose.ui.graphics.Color(0xFF4CAF50) // Green
            TranslationStatus.POLISHED -> androidx.compose.ui.graphics.Color(0xFF2196F3) // Blue
            TranslationStatus.UNTRANSLATED -> androidx.compose.ui.graphics.Color(0xFFFF9800) // Orange
            TranslationStatus.EXCLUDED -> androidx.compose.ui.graphics.Color(0xFFF44336) // Red
        }
    }
}
