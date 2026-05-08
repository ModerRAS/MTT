@file:OptIn(ExperimentalMaterial3Api::class)

package com.mtt.app.ui.home

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.runtime.collectAsState
import com.mtt.app.data.model.ExtractedTerm
import com.mtt.app.ui.components.ExtractionProgressSection
import com.mtt.app.ui.components.ExtractionReviewDialog
import com.mtt.app.ui.translation.TokenChartData
import com.mtt.app.ui.translation.TokenDonutChart
import java.text.NumberFormat
import java.util.Locale

/**
 * Home screen — the main dashboard of the app.
 *
 * Shows model indicator, task selector (translate/polish/proofread/extract),
 * file picker, config area, token chart, progress, and control buttons.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel = hiltViewModel(),
    onNavigateToSettings: () -> Unit = {},
    onNavigateToResult: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Show extraction result messages as snackbar
    LaunchedEffect(uiState.extractionMessage) {
        uiState.extractionMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearExtractionMessage()
        }
    }

    // Reload settings when screen resumes
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

    val isTranslating = uiState.screenState is ScreenState.Translating
    val isPaused = uiState.screenState is ScreenState.Idle && uiState.progress.completedItems > 0
    val isCompleted = uiState.screenState is ScreenState.Completed
    val isResumable = uiState.screenState is ScreenState.Resumable
    val resumableJob = uiState.screenState as? ScreenState.Resumable

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

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { filePickerLauncher.launch(arrayOf("application/json")) },
                containerColor = MaterialTheme.colorScheme.primaryContainer
            ) {
                Icon(Icons.Default.FileOpen, contentDescription = "选择文件")
            }
        }
    ) { paddingValues ->
        HomeScreenContent(
            uiState = uiState,
            isTranslating = isTranslating,
            isPaused = isPaused,
            isCompleted = isCompleted,
            isResumable = isResumable,
            resumableJob = resumableJob,
            onFilePick = { filePickerLauncher.launch(arrayOf("application/json")) },
            onTaskTypeChange = viewModel::onChangeTaskType,
            onSourceLangChange = viewModel::updateSourceLang,
            onTargetLangChange = viewModel::updateTargetLang,
            onStartClick = viewModel::onStartTask,
            onPauseClick = viewModel::onPauseTranslation,
            onResumeClick = viewModel::onResumeTranslation,
            onExportClick = { exportLauncher.launch("translated.txt") },
            onNavigateToSettings = onNavigateToSettings,
            onResumeJobClick = { jobId -> viewModel.resumeFromJob(jobId) },
            onDismissResumeClick = viewModel::dismissResumable,
            modifier = Modifier.padding(paddingValues)
        )

        // Extraction review dialog
        if (uiState.showExtractionReview) {
            ExtractionReviewDialog(
                terms = uiState.extractedTerms,
                existingTerms = emptyList(),
                onDismiss = { viewModel.cancelExtraction() },
                onConfirm = { selected -> viewModel.confirmExtraction(selected) }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun HomeScreenContent(
    uiState: HomeUiState,
    isTranslating: Boolean,
    isPaused: Boolean,
    isCompleted: Boolean,
    isResumable: Boolean = false,
    resumableJob: ScreenState.Resumable? = null,
    onFilePick: () -> Unit,
    onTaskTypeChange: (TaskType) -> Unit,
    onSourceLangChange: (String) -> Unit,
    onTargetLangChange: (String) -> Unit,
    onStartClick: () -> Unit,
    onPauseClick: () -> Unit,
    onResumeClick: () -> Unit,
    onExportClick: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onResumeJobClick: (String) -> Unit = {},
    onDismissResumeClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    val numberFormat = NumberFormat.getNumberInstance(Locale.US)
    val languages = listOf("中文", "英语", "日语", "韩语", "法语", "德语", "俄语", "西班牙语", "葡萄牙语")
    var showTaskHelpDialog by remember { mutableStateOf(false) }
    val isBusy = isTranslating || uiState.isExtracting

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header: title + model indicator
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "主页",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }

            // Model indicator chip
            val modelText = uiState.currentModelName.ifEmpty { "未配置模型" }
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

            // Task selector card
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
                            text = "任务选择",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        IconButton(
                            onClick = { showTaskHelpDialog = true },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = "任务模式说明",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TaskType.values().forEach { taskType ->
                            val selected = uiState.taskType == taskType
                            val label = when (taskType) {
                                TaskType.TRANSLATE -> "翻译"
                                TaskType.POLISH -> "润色"
                                TaskType.PROOFREAD -> "校对"
                                TaskType.EXTRACT -> "提取术语"
                            }
                            FilterChip(
                                selected = selected,
                                onClick = { onTaskTypeChange(taskType) },
                                label = { Text(label, maxLines = 1) },
                                enabled = !isBusy,
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                                ),
                                border = FilterChipDefaults.filterChipBorder(
                                    borderColor = MaterialTheme.colorScheme.outline,
                                    selectedBorderColor = MaterialTheme.colorScheme.primary,
                                    enabled = !isBusy,
                                    selected = selected
                                )
                            )
                        }
                    }
                }
            }

            // File selection card
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
                        uiState.screenState is ScreenState.Loading -> {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    color = MaterialTheme.colorScheme.primary,
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = (uiState.screenState as ScreenState.Loading).message,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        uiState.selectedFileName == null -> {
                            Text(
                                text = "请选择 JSON 文件以开始任务",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(
                                onClick = onFilePick,
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !isBusy
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
                                        text = uiState.selectedFileName,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "条目: ${numberFormat.format(uiState.progress.totalItems)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                OutlinedButton(
                                    onClick = onFilePick,
                                    enabled = !isBusy
                                ) {
                                    Text("更换")
                                }
                            }
                        }
                    }
                }
            }

            // Config area — changes based on taskType
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
                    when (uiState.taskType) {
                        TaskType.EXTRACT -> {
                            // Extract mode: source language only
                            Text(
                                text = "提取配置",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                LanguageDropdown(
                                    label = "源语言",
                                    selectedLanguage = uiState.sourceLang,
                                    languages = languages,
                                    onLanguageSelected = { onSourceLangChange(it) },
                                    enabled = !isBusy,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Button(
                                onClick = onStartClick,
                                modifier = Modifier.fillMaxWidth(),
                                enabled = uiState.selectedFileName != null && !isBusy && !isCompleted
                            ) {
                                if (uiState.isExtracting) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onPrimary
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        if (uiState.extractionProgress.total > 0) {
                                            "验证候选术语 ${uiState.extractionProgress.completed}/${uiState.extractionProgress.total}"
                                        } else {
                                            "正在分析文本..."
                                        }
                                    )
                                } else {
                                    Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("开始提取")
                                }
                            }

                            // Extraction progress
                            if (uiState.isExtracting && uiState.extractionProgress.total > 0) {
                                Spacer(modifier = Modifier.height(4.dp))
                                ExtractionProgressSection(progress = uiState.extractionProgress)
                            }
                        }
                        else -> {
                            // Translate/Polish/Proofread: source + target language
                            Text(
                                text = "翻译配置",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                LanguageDropdown(
                                    label = "源语言",
                                    selectedLanguage = uiState.sourceLang,
                                    languages = languages,
                                    onLanguageSelected = { onSourceLangChange(it) },
                                    enabled = !isBusy,
                                    modifier = Modifier.weight(1f)
                                )
                                LanguageDropdown(
                                    label = "目标语言",
                                    selectedLanguage = uiState.targetLang,
                                    languages = languages,
                                    onLanguageSelected = { onTargetLangChange(it) },
                                    enabled = !isBusy,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            }

            // Prohibition warning for non-translate modes
            if (uiState.taskType != TaskType.TRANSLATE && uiState.taskType != TaskType.EXTRACT && uiState.prohibitionCount > 0) {
                val modeName = when (uiState.taskType) {
                    TaskType.POLISH -> "润色"
                    TaskType.PROOFREAD -> "校对"
                    else -> ""
                }
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(12.dp)
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "当前为${modeName}模式，禁翻术语不生效（已配置 ${uiState.prohibitionCount} 条）",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            // Resume incomplete job card
            if (isResumable && resumableJob != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "发现未完成的翻译任务",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                        Text(
                            text = "文件: ${resumableJob.sourceFileName ?: "未知"}\n" +
                                    "进度: ${resumableJob.completedItems}/${resumableJob.totalItems}" +
                                    " (${if (resumableJob.totalItems > 0) (resumableJob.completedItems * 100 / resumableJob.totalItems) else 0}%)",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { onResumeJobClick(resumableJob.jobId) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("继续翻译")
                            }
                            OutlinedButton(
                                onClick = onDismissResumeClick,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("重新开始")
                            }
                        }
                    }
                }
            }

            // Token statistics
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Token 统计",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    val hasTokenData = uiState.progress.totalInputTokens > 0 ||
                            uiState.progress.totalOutputTokens > 0 ||
                            uiState.progress.totalCacheTokens > 0

                    if (hasTokenData) {
                        Spacer(modifier = Modifier.height(8.dp))
                        TokenDonutChart(
                            data = TokenChartData(
                                inputTokens = uiState.progress.totalInputTokens,
                                outputTokens = uiState.progress.totalOutputTokens,
                                cacheTokens = uiState.progress.totalCacheTokens
                            )
                        )
                    } else {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "开始任务后将在此处显示 Token 用量统计",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
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
                            progress = { uiState.progress.percentage / 100f },
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.primaryContainer
                        )

                        Text(
                            text = "已完成 ${uiState.progress.completedItems}/${uiState.progress.totalItems} (${uiState.progress.percentage}%)",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )

                        Text(
                            text = uiState.progress.status,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }

            // Control buttons for translation
            if (uiState.taskType != TaskType.EXTRACT) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Start button
                    Button(
                        onClick = onStartClick,
                        modifier = Modifier.weight(1f),
                        enabled = uiState.selectedFileName != null && !isTranslating && !isCompleted
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
            }

            // Error state
            if (uiState.screenState is ScreenState.Error) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Text(
                        text = (uiState.screenState as ScreenState.Error).message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        }
    }

    // Task mode help dialog
    if (showTaskHelpDialog) {
        AlertDialog(
            onDismissRequest = { showTaskHelpDialog = false },
            title = { Text("任务模式说明") },
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
                        modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                    )

                    Text(
                        text = "提取术语（EXTRACT）",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "AI 分析原文，自动提取术语、角色名和代码占位符，用于构建术语表。不执行翻译。",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showTaskHelpDialog = false }) {
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
