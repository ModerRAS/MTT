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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.mtt.app.data.model.LlmProvider
import com.mtt.app.data.model.ModelInfo
import com.mtt.app.data.model.TranslationMode
import com.mtt.app.data.model.TranslationProgress
import com.mtt.app.data.model.TranslationUiState
import com.mtt.app.ui.glossary.ExtractionProgress
import com.mtt.app.ui.glossary.ExtractionProgressSection
import com.mtt.app.ui.theme.MttTheme
import java.text.NumberFormat
import java.util.Locale

/**
 * Main translation screen with file picker, progress, mode switching.
 */
@Composable
fun TranslationScreen(
    viewModel: TranslationViewModel = hiltViewModel(),
    onNavigateToSettings: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val progress by viewModel.progress.collectAsState()
    val currentMode by viewModel.currentMode.collectAsState()
    val selectedFileName by viewModel.selectedFileName.collectAsState()
    val currentModel by viewModel.currentModel.collectAsState()
    val prohibitionCount by viewModel.prohibitionCount.collectAsState()
    val extractedTerms by viewModel.extractedTerms.collectAsState()
    val showExtractionReview by viewModel.showExtractionReview.collectAsState()
    val isExtracting by viewModel.isExtracting.collectAsState()
    val extractionProgress by viewModel.extractionProgress.collectAsState()

    // Reload settings when screen resumes (e.g., after returning from Settings)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.reloadSettings()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

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
        currentModel = currentModel,
        prohibitionCount = prohibitionCount,
        selectedFileName = selectedFileName,
        isTranslating = isTranslating,
        isPaused = isPaused,
        isCompleted = isCompleted,
        isExtracting = isExtracting,
        extractionProgress = extractionProgress,
        sourceLang = viewModel.sourceLang,
        targetLang = viewModel.targetLang,
        onSourceLangChange = viewModel::updateSourceLang,
        onTargetLangChange = viewModel::updateTargetLang,
        onFilePick = { filePickerLauncher.launch(arrayOf("application/json")) },
        onModeChange = viewModel::onChangeMode,
        onStartClick = viewModel::onStartTranslation,
        onPauseClick = viewModel::onPauseTranslation,
        onResumeClick = viewModel::onResumeTranslation,
        onExportClick = { exportLauncher.launch("translated.txt") },
        onExtractTermsClick = viewModel::extractTerms,
        onNavigateToSettings = onNavigateToSettings
    )

    // Extraction review dialog
    if (showExtractionReview) {
        com.mtt.app.ui.glossary.ExtractionReviewDialog(
            terms = extractedTerms,
            existingTerms = emptyList(), // let dialog show all candidates
            onDismiss = { viewModel.cancelExtraction() },
            onConfirm = { selected -> viewModel.confirmExtraction(selected) }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TranslationScreenContent(
    uiState: TranslationUiState,
    progress: TranslationProgress,
    currentMode: TranslationMode,
    currentModel: ModelInfo?,
    prohibitionCount: Int,
    selectedFileName: String?,
    isTranslating: Boolean,
    isPaused: Boolean,
    isCompleted: Boolean,
    isExtracting: Boolean = false,
    extractionProgress: ExtractionProgress = ExtractionProgress(0, 0),
    sourceLang: String,
    targetLang: String,
    onSourceLangChange: (String) -> Unit,
    onTargetLangChange: (String) -> Unit,
    onFilePick: () -> Unit,
    onModeChange: (TranslationMode) -> Unit,
    onStartClick: () -> Unit,
    onPauseClick: () -> Unit,
    onResumeClick: () -> Unit,
    onExportClick: () -> Unit,
    onExtractTermsClick: () -> Unit = {},
    onNavigateToSettings: () -> Unit
) {
    val scrollState = rememberScrollState()
    val languages = listOf("日语", "英语", "韩语", "中文", "自动检测")
    val numberFormat = NumberFormat.getNumberInstance(Locale.US)
    var sourceLanguage by remember { mutableStateOf(sourceLang) }
    var targetLanguage by remember { mutableStateOf(targetLang) }
    var showModeHelpDialog by remember { mutableStateOf(false) }

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

            // Model indicator chip
            val modelText = currentModel?.let { model ->
                val providerName = when (model.provider) {
                    is LlmProvider.OpenAI -> "OpenAI"
                    is LlmProvider.Anthropic -> "Anthropic"
                }
                "$providerName: ${model.displayName}"
            } ?: "未配置模型"

            AssistChip(
                onClick = onNavigateToSettings,
                label = { Text(modelText) },
                modifier = Modifier.fillMaxWidth(),
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    labelColor = MaterialTheme.colorScheme.onSecondaryContainer
                ),
                border = AssistChipDefaults.assistChipBorder(
                    borderColor = MaterialTheme.colorScheme.outline,
                    enabled = true
                )
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
                            onLanguageSelected = { lang ->
                                sourceLanguage = lang
                                onSourceLangChange(lang)
                            },
                            enabled = !isTranslating,
                            modifier = Modifier.weight(1f)
                        )

                        LanguageDropdown(
                            label = "目标语言",
                            selectedLanguage = targetLanguage,
                            languages = languages,
                            onLanguageSelected = { lang ->
                                targetLanguage = lang
                                onTargetLangChange(lang)
                            },
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
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "翻译模式",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        IconButton(
                            onClick = { showModeHelpDialog = true },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = "翻译模式说明",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

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

            // Prohibition not active warning banner
            if (currentMode != TranslationMode.TRANSLATE && prohibitionCount > 0) {
                val modeName = when (currentMode) {
                    TranslationMode.POLISH -> "润色"
                    TranslationMode.PROOFREAD -> "校对"
                    else -> ""
                }
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(12.dp)
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "当前为${modeName}模式，禁翻术语不生效（已配置 ${prohibitionCount} 条）",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
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

            // Glossary extraction button
            if (selectedFileName != null && !isTranslating && !isCompleted) {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onExtractTermsClick,
                    enabled = !isTranslating && !isExtracting,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isExtracting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            if (extractionProgress.total > 0) {
                                "验证候选术语 ${extractionProgress.completed}/${extractionProgress.total}"
                            } else {
                                "正在分析文本..."
                            }
                        )
                    } else {
                        Text("从原文提取术语 (AI)")
                    }
                }
            }

            // Extraction progress section
            if (isExtracting && extractionProgress.total > 0) {
                Spacer(modifier = Modifier.height(4.dp))
                ExtractionProgressSection(progress = extractionProgress)
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
    
    if (showModeHelpDialog) {
        AlertDialog(
            onDismissRequest = { showModeHelpDialog = false },
            title = { Text("翻译模式说明") },
            text = {
                Column {
                    Text(
                        text = "翻译（TRANSLATE）",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "将源文本翻译为目标语言。应用术语表和禁翻规则。适用于首次翻译。",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                    )
                    
                    Text(
                        text = "润色（POLISH）",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "优化目标语言文本的表达，使其更自然流畅。需要已有译文作为输入。禁翻规则不生效。",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                    )
                    
                    Text(
                        text = "校对（PROOFREAD）",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "对比原文检查目标语言文本的准确性，修正语法和漏译错误。需要原文+译文对照。禁翻规则不生效。",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showModeHelpDialog = false }) {
                    Text("知道了")
                }
            }
        )
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
            currentModel = ModelInfo(
                modelId = "gpt-4o-mini",
                displayName = "GPT-4o Mini",
                contextWindow = 128000,
                provider = LlmProvider.OpenAI("")
            ),
            prohibitionCount = 0,
            selectedFileName = null,
            isTranslating = false,
            isPaused = false,
            isCompleted = false,
            sourceLang = "日语",
            targetLang = "中文",
            onSourceLangChange = {},
            onTargetLangChange = {},
            onFilePick = {},
            onModeChange = {},
            onStartClick = {},
            onPauseClick = {},
            onResumeClick = {},
            onExportClick = {},
            onNavigateToSettings = {}
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
            currentModel = ModelInfo(
                modelId = "claude-3-5-haiku",
                displayName = "Claude 3.5 Haiku",
                contextWindow = 200000,
                provider = LlmProvider.Anthropic("")
            ),
            prohibitionCount = 0,
            selectedFileName = "example.json",
            isTranslating = false,
            isPaused = false,
            isCompleted = false,
            sourceLang = "日语",
            targetLang = "中文",
            onSourceLangChange = {},
            onTargetLangChange = {},
            onFilePick = {},
            onModeChange = {},
            onStartClick = {},
            onPauseClick = {},
            onResumeClick = {},
            onExportClick = {},
            onNavigateToSettings = {}
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
            currentModel = ModelInfo(
                modelId = "gpt-4o-mini",
                displayName = "GPT-4o Mini",
                contextWindow = 128000,
                provider = LlmProvider.OpenAI("")
            ),
            prohibitionCount = 0,
            selectedFileName = "example.json",
            isTranslating = true,
            isPaused = false,
            isCompleted = false,
            sourceLang = "日语",
            targetLang = "中文",
            onSourceLangChange = {},
            onTargetLangChange = {},
            onFilePick = {},
            onModeChange = {},
            onStartClick = {},
            onPauseClick = {},
            onResumeClick = {},
            onExportClick = {},
            onNavigateToSettings = {}
        )
    }
}
