@file:OptIn(ExperimentalMaterial3Api::class)

package com.mtt.app.ui.translation

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.mtt.app.data.model.TranslationMode
import com.mtt.app.data.model.TranslationProgress
import com.mtt.app.data.model.TranslationUiState
import com.mtt.app.ui.theme.MttTheme
import java.text.NumberFormat
import java.util.Locale

/**
 * Main translation screen with file picker, progress, mode switching.
 */
@Composable
fun TranslationScreen(
    viewModel: TranslationViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val progress by viewModel.progress.collectAsState()
    val currentMode by viewModel.currentMode.collectAsState()
    val selectedFileName by viewModel.selectedFileName.collectAsState()

    val isTranslating = uiState is TranslationUiState.Translating
    val isPaused = uiState is TranslationUiState.Idle && progress.completedItems > 0
    val isCompleted = uiState is TranslationUiState.Completed

    val context = LocalContext.current

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            val fileName = getFileName(context, it)
            viewModel.onFileSelected(it, fileName)
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain")
    ) { uri: Uri? ->
        uri?.let { viewModel.onExportResult(it) }
    }

    TranslationScreenContent(
        uiState = uiState,
        progress = progress,
        currentMode = currentMode,
        selectedFileName = selectedFileName,
        isTranslating = isTranslating,
        isPaused = isPaused,
        isCompleted = isCompleted,
        onFilePick = { filePickerLauncher.launch(arrayOf("application/json")) },
        onModeChange = viewModel::onChangeMode,
        onStartClick = viewModel::onStartTranslation,
        onPauseClick = viewModel::onPauseTranslation,
        onResumeClick = viewModel::onResumeTranslation,
        onExportClick = { exportLauncher.launch("translated.txt") }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TranslationScreenContent(
    uiState: TranslationUiState,
    progress: TranslationProgress,
    currentMode: TranslationMode,
    selectedFileName: String?,
    isTranslating: Boolean,
    isPaused: Boolean,
    isCompleted: Boolean,
    onFilePick: () -> Unit,
    onModeChange: (TranslationMode) -> Unit,
    onStartClick: () -> Unit,
    onPauseClick: () -> Unit,
    onResumeClick: () -> Unit,
    onExportClick: () -> Unit
) {
    val scrollState = rememberScrollState()
    val languages = listOf("Japanese", "English", "Korean", "Chinese", "Auto")
    val numberFormat = NumberFormat.getNumberInstance(Locale.US)
    var sourceLanguage by remember { mutableStateOf("Japanese") }
    var targetLanguage by remember { mutableStateOf("Chinese") }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header
            Text(
                text = "翻译工具",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground
            )

            // File selection area
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "文件选择",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    when {
                        uiState is TranslationUiState.Loading -> {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.width(24.dp),
                                    color = MaterialTheme.colorScheme.primary,
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = uiState.message,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        selectedFileName == null -> {
                            Text(
                                text = "请选择 JSON 文件以开始翻译",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(
                                onClick = onFilePick,
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !isTranslating
                            ) {
                                Text("选择 JSON 文件")
                            }
                        }
                        else -> {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = selectedFileName,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "条目: ${numberFormat.format(progress.totalItems)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                OutlinedButton(
                                    onClick = onFilePick,
                                    enabled = !isTranslating
                                ) {
                                    Text("更换")
                                }
                            }
                        }
                    }
                }
            }

            // Language selection
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "语言设置",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        LanguageDropdown(
                            label = "源语言",
                            selectedLanguage = sourceLanguage,
                            languages = languages,
                            onLanguageSelected = { sourceLanguage = it },
                            enabled = !isTranslating,
                            modifier = Modifier.weight(1f)
                        )

                        LanguageDropdown(
                            label = "目标语言",
                            selectedLanguage = targetLanguage,
                            languages = languages,
                            onLanguageSelected = { targetLanguage = it },
                            enabled = !isTranslating,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // Mode selection
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "翻译模式",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TranslationMode.values().forEach { mode ->
                            val selected = currentMode == mode
                            val modeText = when (mode) {
                                TranslationMode.TRANSLATE -> "翻译"
                                TranslationMode.POLISH -> "润色"
                                TranslationMode.PROOFREAD -> "校对"
                            }
                            FilterChip(
                                selected = selected,
                                onClick = { onModeChange(mode) },
                                label = { Text(modeText) },
                                enabled = !isTranslating,
                                modifier = Modifier.weight(1f),
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                                ),
                                border = FilterChipDefaults.filterChipBorder(
                                    borderColor = MaterialTheme.colorScheme.outline,
                                    selectedBorderColor = MaterialTheme.colorScheme.primary,
                                    enabled = !isTranslating,
                                    selected = selected
                                )
                            )
                        }
                    }
                }
            }

            // Progress area
            if (isTranslating || isCompleted) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "翻译进度",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )

                        LinearProgressIndicator(
                            progress = { progress.percentage / 100f },
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.primaryContainer
                        )

                        Text(
                            text = "已完成 ${progress.completedItems}/${progress.totalItems} (${progress.percentage}%)",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )

                        Text(
                            text = progress.status,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }

            // Control buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Start button
                Button(
                    onClick = onStartClick,
                    modifier = Modifier.weight(1f),
                    enabled = selectedFileName != null && !isTranslating && !isCompleted
                ) {
                    Text("开始")
                }

                // Pause/Resume button
                if (isTranslating) {
                    if (isPaused) {
                        FilledTonalButton(
                            onClick = onResumeClick,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("继续")
                        }
                    } else {
                        OutlinedButton(
                            onClick = onPauseClick,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("暂停")
                        }
                    }
                }

                // Export button
                Button(
                    onClick = onExportClick,
                    modifier = Modifier.weight(1f),
                    enabled = isCompleted,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondary
                    )
                ) {
                    Text("导出")
                }
            }

            // Error state
            if (uiState is TranslationUiState.Error) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Text(
                        text = uiState.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguageDropdown(
    label: String,
    selectedLanguage: String,
    languages: List<String>,
    onLanguageSelected: (String) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = it },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = selectedLanguage,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor(),
            enabled = enabled
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            languages.forEach { language ->
                DropdownMenuItem(
                    text = { Text(language) },
                    onClick = {
                        onLanguageSelected(language)
                        expanded = false
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                )
            }
        }
    }
}


private fun getFileName(context: android.content.Context, uri: Uri): String {
    var fileName = "unknown.json"
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (nameIndex != -1) {
                fileName = cursor.getString(nameIndex)
            }
        }
    }
    return fileName
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
private fun TranslationScreenPreview() {
    MttTheme(dynamicColor = false) {
        TranslationScreenContent(
            uiState = TranslationUiState.Idle,
            progress = TranslationProgress.initial(),
            currentMode = TranslationMode.TRANSLATE,
            selectedFileName = null,
            isTranslating = false,
            isPaused = false,
            isCompleted = false,
            onFilePick = {},
            onModeChange = {},
            onStartClick = {},
            onPauseClick = {},
            onResumeClick = {},
            onExportClick = {}
        )
    }
}

@Preview(showBackground = true, showSystemUi = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun TranslationScreenDarkPreview() {
    MttTheme(darkTheme = true, dynamicColor = false) {
        TranslationScreenContent(
            uiState = TranslationUiState.Idle,
            progress = TranslationProgress.initial(),
            currentMode = TranslationMode.TRANSLATE,
            selectedFileName = "example.json",
            isTranslating = false,
            isPaused = false,
            isCompleted = false,
            onFilePick = {},
            onModeChange = {},
            onStartClick = {},
            onPauseClick = {},
            onResumeClick = {},
            onExportClick = {}
        )
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
private fun TranslationScreenTranslatingPreview() {
    MttTheme(dynamicColor = false) {
        TranslationScreenContent(
            uiState = TranslationUiState.Translating(
                TranslationProgress(
                    totalItems = 150,
                    completedItems = 75,
                    currentBatch = 5,
                    totalBatches = 10,
                    status = "正在翻译第 5 批..."
                )
            ),
            progress = TranslationProgress(
                totalItems = 150,
                completedItems = 75,
                currentBatch = 5,
                totalBatches = 10,
                status = "正在翻译第 5 批..."
            ),
            currentMode = TranslationMode.TRANSLATE,
            selectedFileName = "example.json",
            isTranslating = true,
            isPaused = false,
            isCompleted = false,
            onFilePick = {},
            onModeChange = {},
            onStartClick = {},
            onPauseClick = {},
            onResumeClick = {},
            onExportClick = {}
        )
    }
}
