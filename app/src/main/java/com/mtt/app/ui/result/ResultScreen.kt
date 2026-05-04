@file:OptIn(ExperimentalMaterial3Api::class)

package com.mtt.app.ui.result

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.mtt.app.data.model.TranslationStatus
import com.mtt.app.ui.theme.MttTheme

/**
 * Main result screen showing translation results with filtering and export.
 */
@Composable
fun ResultScreen(
    viewModel: ResultViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val filter by viewModel.filter.collectAsState()
    val filteredItems by viewModel.filteredItems.collectAsState()
    
    val context = LocalContext.current
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        uri?.let {
            viewModel.exportToJson(it)
        }
    }

    ResultScreenContent(
        uiState = uiState,
        filter = filter,
        filteredItems = filteredItems,
        onStatusFilterChange = viewModel::setStatusFilter,
        onSearchTextChange = viewModel::setSearchText,
        onClearFilters = viewModel::clearFilters,
        onExportClick = { filePickerLauncher.launch("translation_results.json") },
        onRefresh = viewModel::loadResults
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ResultScreenContent(
    uiState: ResultUiState,
    filter: ResultFilter,
    filteredItems: List<TranslationResultItem>,
    onStatusFilterChange: (Set<TranslationStatus>) -> Unit,
    onSearchTextChange: (String) -> Unit,
    onClearFilters: () -> Unit,
    onExportClick: () -> Unit,
    onRefresh: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("翻译结果") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                actions = {
                    IconButton(onClick = onRefresh) {
                        Icon(
                            painter = painterResource(android.R.drawable.ic_popup_sync),
                            contentDescription = "刷新"
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Search and filter section
                SearchAndFilterSection(
                    filter = filter,
                    onSearchTextChange = onSearchTextChange,
                    onStatusFilterChange = onStatusFilterChange,
                    onClearFilters = onClearFilters
                )
                
                // Export button
                Button(
                    onClick = onExportClick,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = filteredItems.isNotEmpty()
                ) {
                    Text("导出 JSON")
                }
                
                // Results list
                when (uiState) {
                    is ResultUiState.Loading -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                LinearProgressIndicator()
                                Text(
                                    text = "加载结果中...",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    
                    is ResultUiState.Error -> {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer
                            )
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = uiState.message,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Button(onClick = onRefresh) {
                                    Text("重试")
                                }
                            }
                        }
                    }
                    
                    is ResultUiState.Success -> {
                        if (filteredItems.isEmpty()) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "没有找到匹配的结果",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            ResultsList(
                                items = filteredItems,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchAndFilterSection(
    filter: ResultFilter,
    onSearchTextChange: (String) -> Unit,
    onStatusFilterChange: (Set<TranslationStatus>) -> Unit,
    onClearFilters: () -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Search field
        OutlinedTextField(
            value = filter.searchText,
            onValueChange = onSearchTextChange,
            label = { Text("搜索原文或译文") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        
        // Status filter chips
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StatusFilterChip(
                status = TranslationStatus.TRANSLATED,
                label = "已翻译",
                selected = filter.statusFilter.contains(TranslationStatus.TRANSLATED),
                onSelected = { selected ->
                    val newFilter = if (selected) {
                        filter.statusFilter + TranslationStatus.TRANSLATED
                    } else {
                        filter.statusFilter - TranslationStatus.TRANSLATED
                    }
                    onStatusFilterChange(newFilter)
                }
            )
            
            StatusFilterChip(
                status = TranslationStatus.UNTRANSLATED,
                label = "未翻译",
                selected = filter.statusFilter.contains(TranslationStatus.UNTRANSLATED),
                onSelected = { selected ->
                    val newFilter = if (selected) {
                        filter.statusFilter + TranslationStatus.UNTRANSLATED
                    } else {
                        filter.statusFilter - TranslationStatus.UNTRANSLATED
                    }
                    onStatusFilterChange(newFilter)
                }
            )
            
            StatusFilterChip(
                status = TranslationStatus.EXCLUDED,
                label = "已排除",
                selected = filter.statusFilter.contains(TranslationStatus.EXCLUDED),
                onSelected = { selected ->
                    val newFilter = if (selected) {
                        filter.statusFilter + TranslationStatus.EXCLUDED
                    } else {
                        filter.statusFilter - TranslationStatus.EXCLUDED
                    }
                    onStatusFilterChange(newFilter)
                }
            )
            
            Spacer(modifier = Modifier.weight(1f))
            
            if (filter.statusFilter.isNotEmpty() || filter.searchText.isNotBlank()) {
                TextButton(onClick = onClearFilters) {
                    Text("清除筛选")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StatusFilterChip(
    status: TranslationStatus,
    label: String,
    selected: Boolean,
    onSelected: (Boolean) -> Unit
) {
    FilterChip(
        selected = selected,
        onClick = { onSelected(!selected) },
        label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
        ),
        border = FilterChipDefaults.filterChipBorder(
            borderColor = MaterialTheme.colorScheme.outline,
            selectedBorderColor = MaterialTheme.colorScheme.primary,
            enabled = true,
            selected = selected
        )
    )
}

@Composable
private fun ResultsList(
    items: List<TranslationResultItem>,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(items) { item ->
            ResultItemCard(item = item)
        }
    }
}

@Composable
private fun ResultItemCard(
    item: TranslationResultItem
) {
    var showDetails by remember { mutableStateOf(false) }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        onClick = { showDetails = !showDetails }
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Status and text row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top
            ) {
                // Status icon
                Text(
                    text = getStatusIcon(item.status),
                    style = MaterialTheme.typography.titleLarge,
                    color = getStatusColor(item.status)
                )
                
                // Original and translated text
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Original text
                    Text(
                        text = item.sourceText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = if (showDetails) Int.MAX_VALUE else 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    
                    // Arrow and translated text
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "→",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = item.translatedText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = if (showDetails) Int.MAX_VALUE else 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            
            // Details section (expanded)
            if (showDetails) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    DetailItem(
                        label = "状态",
                        value = getStatusText(item.status)
                    )
                    if (item.model.isNotBlank()) {
                        DetailItem(
                            label = "模型",
                            value = item.model
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailItem(
    label: String,
    value: String
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

private fun getStatusIcon(status: TranslationStatus): String {
    return when (status) {
        TranslationStatus.TRANSLATED -> "✓"
        TranslationStatus.POLISHED -> "✓"
        TranslationStatus.UNTRANSLATED -> "⏳"
        TranslationStatus.EXCLUDED -> "⊗"
    }
}

private fun getStatusColor(status: TranslationStatus): androidx.compose.ui.graphics.Color {
    return when (status) {
        TranslationStatus.TRANSLATED -> androidx.compose.ui.graphics.Color(0xFF4CAF50) // Green
        TranslationStatus.POLISHED -> androidx.compose.ui.graphics.Color(0xFF2196F3) // Blue
        TranslationStatus.UNTRANSLATED -> androidx.compose.ui.graphics.Color(0xFFFF9800) // Orange
        TranslationStatus.EXCLUDED -> androidx.compose.ui.graphics.Color(0xFFF44336) // Red
    }
}

private fun getStatusText(status: TranslationStatus): String {
    return when (status) {
        TranslationStatus.TRANSLATED -> "已翻译"
        TranslationStatus.POLISHED -> "已润色"
        TranslationStatus.UNTRANSLATED -> "未翻译"
        TranslationStatus.EXCLUDED -> "已排除"
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
private fun ResultScreenPreview() {
    MttTheme(dynamicColor = false) {
        ResultScreenContent(
            uiState = ResultUiState.Success(
                items = listOf(
                    TranslationResultItem(
                        sourceText = "こんにちは",
                        translatedText = "你好",
                        status = TranslationStatus.TRANSLATED,
                        model = "gpt-4"
                    ),
                    TranslationResultItem(
                        sourceText = "おはようございます",
                        translatedText = "早上好",
                        status = TranslationStatus.TRANSLATED,
                        model = "gpt-4"
                    ),
                    TranslationResultItem(
                        sourceText = "さようなら",
                        translatedText = "",
                        status = TranslationStatus.UNTRANSLATED,
                        model = ""
                    )
                )
            ),
            filter = ResultFilter(),
            filteredItems = listOf(
                TranslationResultItem(
                    sourceText = "こんにちは",
                    translatedText = "你好",
                    status = TranslationStatus.TRANSLATED,
                    model = "gpt-4"
                ),
                TranslationResultItem(
                    sourceText = "おはようございます",
                    translatedText = "早上好",
                    status = TranslationStatus.TRANSLATED,
                    model = "gpt-4"
                ),
                TranslationResultItem(
                    sourceText = "さようなら",
                    translatedText = "",
                    status = TranslationStatus.UNTRANSLATED,
                    model = ""
                )
            ),
            onStatusFilterChange = {},
            onSearchTextChange = {},
            onClearFilters = {},
            onExportClick = {},
            onRefresh = {}
        )
    }
}

@Preview(showBackground = true, showSystemUi = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ResultScreenDarkPreview() {
    MttTheme(darkTheme = true, dynamicColor = false) {
        ResultScreenContent(
            uiState = ResultUiState.Success(
                items = listOf(
                    TranslationResultItem(
                        sourceText = "こんにちは",
                        translatedText = "你好",
                        status = TranslationStatus.TRANSLATED,
                        model = "gpt-4"
                    )
                )
            ),
            filter = ResultFilter(),
            filteredItems = listOf(
                TranslationResultItem(
                    sourceText = "こんにちは",
                    translatedText = "你好",
                    status = TranslationStatus.TRANSLATED,
                    model = "gpt-4"
                )
            ),
            onStatusFilterChange = {},
            onSearchTextChange = {},
            onClearFilters = {},
            onExportClick = {},
            onRefresh = {}
        )
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
private fun ResultScreenLoadingPreview() {
    MttTheme(dynamicColor = false) {
        ResultScreenContent(
            uiState = ResultUiState.Loading,
            filter = ResultFilter(),
            filteredItems = emptyList(),
            onStatusFilterChange = {},
            onSearchTextChange = {},
            onClearFilters = {},
            onExportClick = {},
            onRefresh = {}
        )
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
private fun ResultScreenErrorPreview() {
    MttTheme(dynamicColor = false) {
        ResultScreenContent(
            uiState = ResultUiState.Error("加载结果失败: 网络连接错误"),
            filter = ResultFilter(),
            filteredItems = emptyList(),
            onStatusFilterChange = {},
            onSearchTextChange = {},
            onClearFilters = {},
            onExportClick = {},
            onRefresh = {}
        )
    }
}
