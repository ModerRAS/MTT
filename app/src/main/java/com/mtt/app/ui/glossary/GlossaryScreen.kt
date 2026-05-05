package com.mtt.app.ui.glossary

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Checkbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.mtt.app.data.model.ExtractedTerm
import com.mtt.app.data.model.GlossaryEntryUiModel
import com.mtt.app.domain.glossary.GlossaryEntry
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * GlossaryScreen composable for managing glossary and prohibition list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlossaryScreen(
    viewModel: GlossaryViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val glossaryUiEntries by viewModel.glossaryUiEntries.collectAsState()
    val pendingDeleteEntry by viewModel.pendingDeleteEntry.collectAsState()
    val isExtracting by viewModel.isExtracting.collectAsState()
    val showExtractionReview by viewModel.showExtractionReview.collectAsState()
    val extractedTerms by viewModel.extractedTerms.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    
    // Dialog states
    var showAddDialog by remember { mutableStateOf(false) }
    var editingEntry by remember { mutableStateOf<GlossaryEntryUiModel?>(null) }

    // CSV file picker for glossary import
    val csvFilePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            val content = readUriContent(context, it)
            if (content != null) {
                viewModel.importGlossaryFromCsv(it, content)
            }
        }
    }

    // Text file picker for prohibition list import
    val textFilePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            val content = readUriContent(context, it)
            if (content != null) {
                viewModel.importProhibitionList(it, content)
            }
        }
    }

    // Show snackbar for success/error messages
    LaunchedEffect(uiState.successMessage, uiState.errorMessage) {
        uiState.successMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessages()
        }
        uiState.errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessages()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("术语表管理") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "添加术语")
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
        ) {
            // Glossary Import Section
            GlossaryImportSection(
                glossaryCount = uiState.glossaryCount,
                isLoading = uiState.isLoading,
                onImportClick = { csvFilePicker.launch("text/*") }
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Prohibition List Import Section
            ProhibitionImportSection(
                prohibitionCount = uiState.prohibitionCount,
                isLoading = uiState.isLoading,
                onImportClick = { textFilePicker.launch("text/*") }
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Clear Button
            ClearSection(
                isLoading = uiState.isLoading,
                onClearClick = { viewModel.clearGlossary() }
            )

            Spacer(modifier = Modifier.height(24.dp))

            // AI Extraction Button
            OutlinedButton(
                onClick = { viewModel.extractTerms() },
                enabled = uiState.previewEntries.isNotEmpty() && !isExtracting,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isExtracting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null)
                }
                Spacer(Modifier.width(8.dp))
                Text("从原文提取术语 (AI)")
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Preview List
            PreviewSection(
                entries = glossaryUiEntries,
                isLoading = uiState.isLoading,
                onEntryClick = { entry -> editingEntry = entry },
                onEntryDelete = { entry -> viewModel.showDeleteConfirmation(entry) }
            )
        }
    }
    
    // Add/Edit Dialog
    if (showAddDialog) {
        GlossaryEntryDialog(
            editingEntry = null,
            onDismiss = { showAddDialog = false },
            onConfirm = { source, target, type ->
                viewModel.addEntry(source, target, type)
                showAddDialog = false
            }
        )
    }
    
    if (editingEntry != null) {
        GlossaryEntryDialog(
            editingEntry = editingEntry,
            onDismiss = { editingEntry = null },
            onConfirm = { source, target, type ->
                editingEntry?.let { 
                    viewModel.updateEntry(it.copy(sourceTerm = source, targetTerm = target, matchType = type)) 
                }
                editingEntry = null
            }
        )
    }
    
    // Delete Confirmation Dialog
    pendingDeleteEntry?.let { entry ->
        AlertDialog(
            onDismissRequest = { viewModel.cancelDelete() },
            title = { Text("确认删除") },
            text = { Text("确定要删除术语 '${entry.sourceTerm}' 吗？") },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmDelete() }) {
                    Text("确认删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelDelete() }) {
                    Text("取消")
                }
            }
        )
    }
    
    // Extraction Review Dialog
    if (showExtractionReview) {
        ExtractionReviewDialog(
            terms = extractedTerms,
            existingTerms = glossaryUiEntries.map { it.sourceTerm },
            onDismiss = { viewModel.cancelExtraction() },
            onConfirm = { selected -> viewModel.confirmExtraction(selected) }
        )
    }
}

/**
 * Glossary import section with count and import button.
 */
@Composable
private fun GlossaryImportSection(
    glossaryCount: Int,
    isLoading: Boolean,
    onImportClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = "术语表",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = "已导入 $glossaryCount 条术语",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Button(
                onClick = onImportClick,
                enabled = !isLoading,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .height(20.dp)
                            .width(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text("导入术语表 (CSV)")
            }
            
            Spacer(modifier = Modifier.height(4.dp))
            
            Text(
                text = "格式: source, target (每行一条，无表头)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Prohibition list import section with count and import button.
 */
@Composable
private fun ProhibitionImportSection(
    prohibitionCount: Int,
    isLoading: Boolean,
    onImportClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = "禁翻表",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = "已导入 $prohibitionCount 条禁翻术语",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Button(
                onClick = onImportClick,
                enabled = !isLoading,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .height(20.dp)
                            .width(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text("导入禁翻表 (TXT)")
            }
            
            Spacer(modifier = Modifier.height(4.dp))
            
            Text(
                text = "格式: 每行一个术语",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Clear section with clear button.
 */
@Composable
private fun ClearSection(
    isLoading: Boolean,
    onClearClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = "清空术语表",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = "清空当前项目的所有术语和禁翻术语",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            OutlinedButton(
                onClick = onClearClick,
                enabled = !isLoading,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                )
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .height(20.dp)
                            .width(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text("清空术语表")
            }
        }
    }
}

/**
 * Preview section showing glossary and prohibition entries with section headers.
 */
@Composable
private fun PreviewSection(
    entries: List<GlossaryEntryUiModel>,
    isLoading: Boolean,
    onEntryClick: (GlossaryEntryUiModel) -> Unit,
    onEntryDelete: (GlossaryEntryUiModel) -> Unit
) {
    val glossaryEntries = entries.filter { it.targetTerm.isNotEmpty() }
    val prohibitionEntries = entries.filter { it.targetTerm.isEmpty() }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = "术语表管理",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else if (entries.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "暂无术语",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                // Glossary Section
                if (glossaryEntries.isNotEmpty()) {
                    Text(
                        text = "术语表",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(glossaryEntries) { entry ->
                            GlossaryEntryItem(
                                entry = entry,
                                onClick = { onEntryClick(entry) },
                                onDelete = { onEntryDelete(entry) }
                            )
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                }
                
                // Prohibition Section
                if (prohibitionEntries.isNotEmpty()) {
                    Text(
                        text = "禁翻表",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(prohibitionEntries) { entry ->
                            GlossaryEntryItem(
                                entry = entry,
                                onClick = { onEntryClick(entry) },
                                onDelete = { onEntryDelete(entry) }
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Individual glossary entry item with click and swipe-to-delete support.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GlossaryEntryItem(
    entry: GlossaryEntryUiModel,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val isProhibition = entry.targetTerm.isEmpty()
    
    SwipeToDismissBox(
        state = rememberSwipeToDismissBoxState(confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                onDelete()
            }
            false
        }),
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(vertical = 4.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "删除",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(end = 16.dp)
                )
            }
        },
        modifier = Modifier.padding(vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onClick() }
                .background(MaterialTheme.colorScheme.surface)
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = entry.sourceTerm,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                if (!isProhibition) {
                    Text(
                        text = "→ ${entry.targetTerm}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    Text(
                        text = "🚫 禁翻",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
    
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 4.dp),
        color = MaterialTheme.colorScheme.outlineVariant
    )
}

/**
 * Dialog for adding or editing a glossary entry.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlossaryEntryDialog(
    editingEntry: GlossaryEntryUiModel?,
    onDismiss: () -> Unit,
    onConfirm: (sourceTerm: String, targetTerm: String, matchType: String) -> Unit
) {
    var sourceTerm by rememberSaveable { mutableStateOf(editingEntry?.sourceTerm ?: "") }
    var targetTerm by rememberSaveable { mutableStateOf(editingEntry?.targetTerm ?: "") }
    var matchType by rememberSaveable { mutableStateOf(editingEntry?.matchType ?: "EXACT") }
    var matchTypeExpanded by remember { mutableStateOf(false) }
    
    val matchTypes = listOf("EXACT", "REGEX", "CASE_INSENSITIVE")
    val matchTypeLabels = mapOf(
        "EXACT" to "精确匹配",
        "REGEX" to "正则表达式",
        "CASE_INSENSITIVE" to "忽略大小写"
    )
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (editingEntry == null) "添加术语" else "编辑术语") },
        text = {
            Column {
                OutlinedTextField(
                    value = sourceTerm,
                    onValueChange = { sourceTerm = it },
                    label = { Text("原文术语") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                OutlinedTextField(
                    value = targetTerm,
                    onValueChange = { targetTerm = it },
                    label = { Text("译文（留空=禁翻）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                ExposedDropdownMenuBox(
                    expanded = matchTypeExpanded,
                    onExpandedChange = { matchTypeExpanded = it }
                ) {
                    OutlinedTextField(
                        value = matchTypeLabels[matchType] ?: matchType,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("匹配类型") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = matchTypeExpanded) },
                        modifier = Modifier.menuAnchor()
                    )
                    
                    ExposedDropdownMenu(
                        expanded = matchTypeExpanded,
                        onDismissRequest = { matchTypeExpanded = false }
                    ) {
                        matchTypes.forEach { type ->
                            DropdownMenuItem(
                                text = { Text(matchTypeLabels[type] ?: type) },
                                onClick = {
                                    matchType = type
                                    matchTypeExpanded = false
                                },
                                contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(sourceTerm, targetTerm, matchType) },
                enabled = sourceTerm.isNotBlank()
            ) {
                Text("确认")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

/**
 * Extraction review dialog for AI-extracted terms.
 * Shows candidate terms with checkboxes, category, and "already exists" tag.
 */
@Composable
fun ExtractionReviewDialog(
    terms: List<ExtractedTerm>,
    existingTerms: List<String>,  // list of existing source terms for dedup check
    onDismiss: () -> Unit,
    onConfirm: (selected: List<ExtractedTerm>) -> Unit
) {
    var selected by remember { mutableStateOf(terms.toSet()) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("确认导入术语 (${selected.size}/${terms.size})") },
        text = {
            LazyColumn {
                items(terms) { term ->
                    val alreadyExists = existingTerms.contains(term.sourceTerm)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = term in selected && !alreadyExists,
                            onCheckedChange = { if (!alreadyExists) {
                                selected = if (term in selected) {
                                    selected - term
                                } else {
                                    selected + term
                                }
                            } },
                            enabled = !alreadyExists
                        )
                        Column {
                            Text(term.sourceTerm, fontWeight = FontWeight.Bold)
                            Text("→ ${term.suggestedTarget}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (alreadyExists) {
                                Text("已存在", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                            }
                            if (term.category.isNotEmpty()) {
                                Text("类别: ${term.category}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(selected.toList()) },
                enabled = selected.isNotEmpty()
            ) {
                Text("确认导入 (${selected.size})")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

/**
 * Read content from URI.
 */
private fun readUriContent(context: android.content.Context, uri: Uri): String? {
    return try {
        val inputStream = context.contentResolver.openInputStream(uri)
        val reader = BufferedReader(InputStreamReader(inputStream))
        val content = reader.readText()
        reader.close()
        content
    } catch (e: Exception) {
        null
    }
}