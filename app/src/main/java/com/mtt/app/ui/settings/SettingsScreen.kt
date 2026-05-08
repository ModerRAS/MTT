@file:OptIn(ExperimentalMaterial3Api::class)

package com.mtt.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.mtt.app.data.model.ChannelConfig
import com.mtt.app.data.model.ChannelType
import com.mtt.app.data.model.FetchedModel

/**
 * Settings screen for managing LLM provider channels, active model selection,
 * and translation pipeline configuration.
 */
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Show snackbar when globalMessage changes
    LaunchedEffect(uiState.globalMessage) {
        uiState.globalMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearMessage()
        }
    }

    // Auto-save on exit
    DisposableEffect(Unit) {
        onDispose {
            viewModel.saveSettings()
        }
    }

    val scrollState = rememberScrollState()

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("设置") })
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ── Active Config Card ────────────────────
            ActiveConfigCard(
                channels = uiState.channels,
                activeChannelId = uiState.activeChannelId,
                activeModelId = uiState.activeModelId,
                onChannelSelected = viewModel::setActiveChannel,
                onModelSelected = viewModel::setActiveModel
            )

            HorizontalDivider()

            // ── Channel List ──────────────────────────
            Text(
                text = "所有渠道",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )

            uiState.channels.forEach { channel ->
                ChannelCard(
                    channel = channel,
                    fetchState = uiState.modelFetchStates[channel.id] ?: FetchState.Idle,
                    onEdit = { viewModel.startEditChannel(channel.id) },
                    onDelete = { viewModel.deleteChannel(channel.id) },
                    onFetchModels = { viewModel.fetchModelsForChannel(channel.id) }
                )
            }

            // ── Add Channel Button ────────────────────
            OutlinedButton(
                onClick = viewModel::toggleAddChannel,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Add, contentDescription = "添加渠道")
                Spacer(Modifier.width(8.dp))
                Text("添加渠道")
            }

            HorizontalDivider()

            // ── Advanced Settings ─────────────────────
            PipelineConfigCard(
                batchSize = uiState.batchSize,
                concurrency = uiState.concurrency,
                onBatchSizeChange = viewModel::onBatchSizeChange,
                onConcurrencyChange = viewModel::onConcurrencyChange
            )
        }

        // ── Add Channel Dialog ────────────────────
        if (uiState.isAddingChannel) {
            ChannelFormDialog(
                title = "添加渠道",
                form = uiState.newChannelForm,
                onNameChange = viewModel::updateFormName,
                onTypeChange = viewModel::updateFormType,
                onBaseUrlChange = viewModel::updateFormBaseUrl,
                onApiKeyChange = viewModel::updateFormApiKey,
                onToggleKeyVisibility = viewModel::toggleFormApiKeyVisibility,
                onDismiss = viewModel::toggleAddChannel,
                onConfirm = viewModel::addChannel
            )
        }

        // ── Edit Channel Dialog ───────────────────
        val editingId = uiState.editingChannelId
        if (editingId != null) {
            ChannelFormDialog(
                title = "编辑渠道",
                form = uiState.newChannelForm,
                onNameChange = viewModel::updateFormName,
                onTypeChange = viewModel::updateFormType,
                onBaseUrlChange = viewModel::updateFormBaseUrl,
                onApiKeyChange = viewModel::updateFormApiKey,
                onToggleKeyVisibility = viewModel::toggleFormApiKeyVisibility,
                onDismiss = {
                    viewModel.cancelEditChannel()
                },
                onConfirm = {
                    viewModel.updateChannel(editingId)
                },
                confirmLabel = "更新"
            )
        }
    }
}

// ── Active Config Card ────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActiveConfigCard(
    channels: List<ChannelConfig>,
    activeChannelId: String?,
    activeModelId: String,
    onChannelSelected: (String) -> Unit,
    onModelSelected: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "活跃配置",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Channel selector
            ChannelDropdown(
                channels = channels,
                activeChannelId = activeChannelId,
                onChannelSelected = onChannelSelected
            )

            // Model selector — show models from active channel
            val activeChannel = channels.find { it.id == activeChannelId }
            EditableModelDropdown(
                selectedModelId = activeModelId,
                fetchedModels = activeChannel?.fetchedModels ?: emptyList(),
                onModelSelected = onModelSelected,
                enabled = activeChannel != null
            )

            Text(
                text = "提示: 可在下方添加和管理渠道",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ── Channel Dropdown ──────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChannelDropdown(
    channels: List<ChannelConfig>,
    activeChannelId: String?,
    onChannelSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val activeChannel = channels.find { it.id == activeChannelId }
    val displayText = activeChannel?.name ?: "无"

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (channels.isNotEmpty()) expanded = it }
    ) {
        OutlinedTextField(
            value = displayText,
            onValueChange = {},
            readOnly = true,
            label = { Text("渠道") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth(),
            singleLine = true,
            enabled = channels.isNotEmpty()
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            channels.forEach { channel ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = channel.name,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    },
                    onClick = {
                        onChannelSelected(channel.id)
                        expanded = false
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                )
            }
        }
    }
}

// ── Editable Model Dropdown ───────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditableModelDropdown(
    selectedModelId: String,
    fetchedModels: List<FetchedModel>,
    onModelSelected: (String) -> Unit,
    label: String = "模型",
    enabled: Boolean = true
) {
    var expanded by remember { mutableStateOf(false) }
    var text by remember(selectedModelId) { mutableStateOf(selectedModelId) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = it }
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                onModelSelected(it)
                expanded = true
            },
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth(),
            enabled = enabled,
            singleLine = true,
            placeholder = { Text("输入或选择模型名") }
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            fetchedModels.forEach { model ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(
                                text = model.displayName,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = "${model.modelId} · ${model.contextWindow / 1000}K",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    onClick = {
                        text = model.modelId
                        onModelSelected(model.modelId)
                        expanded = false
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                )
            }
        }
    }
}

// ── Channel Card ──────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChannelCard(
    channel: ChannelConfig,
    fetchState: FetchState,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onFetchModels: () -> Unit
) {
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
            // Header row: name + type chip + actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = channel.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )

                AssistChip(
                    onClick = {},
                    label = {
                        Text(
                            text = when (channel.type) {
                                ChannelType.OPENAI -> "OpenAI"
                                ChannelType.ANTHROPIC -> "Anthropic"
                            }
                        )
                    }
                )

                IconButton(onClick = onEdit) {
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = "编辑渠道",
                        modifier = Modifier.size(20.dp)
                    )
                }

                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "删除渠道",
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }

            // URL
            Text(
                text = "URL: ${channel.baseUrl}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // API Key (masked)
            val keyDisplay = if (channel.apiKey.isBlank()) {
                "Key: 未设置"
            } else if (channel.apiKey.length <= 4) {
                "Key: ····"
            } else {
                "Key: ····${channel.apiKey.takeLast(4)}"
            }
            Text(
                text = keyDisplay,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Model count
            val modelCount = channel.fetchedModels.size
            Text(
                text = if (modelCount > 0) "模型: $modelCount 个已获取" else "模型: 尚未获取",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Fetch models button with state
            FetchModelsRow(
                fetchState = fetchState,
                onFetch = onFetchModels
            )

            // Show first few model names on success
            if (fetchState is FetchState.Success && fetchState.models.isNotEmpty()) {
                ModelChips(models = fetchState.models)
            }
        }
    }
}

// ── Fetch Models Row ──────────────────────────────

@Composable
private fun FetchModelsRow(
    fetchState: FetchState,
    onFetch: () -> Unit
) {
    when (fetchState) {
        is FetchState.Idle -> {
            OutlinedButton(
                onClick = onFetch,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("获取模型列表")
            }
        }
        is FetchState.Fetching -> {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp
                )
                Text(
                    text = "正在获取模型列表...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        is FetchState.Success -> {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "已获取 ${fetchState.models.size} 个模型",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onFetch) {
                    Text("重新获取")
                }
            }
        }
        is FetchState.Error -> {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                    Text(
                        text = fetchState.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2
                    )
                }
                TextButton(onClick = onFetch) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("重试")
                }
            }
        }
    }
}

// ── Model Chips ───────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModelChips(models: List<FetchedModel>) {
    val displayModels = models.take(3)
    val remaining = models.size - 3

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        displayModels.forEach { model ->
            FilterChip(
                selected = false,
                onClick = {},
                label = {
                    Text(
                        text = model.displayName,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            )
        }
        if (remaining > 0) {
            FilterChip(
                selected = false,
                onClick = {},
                label = {
                    Text(
                        text = "+$remaining",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            )
        }
    }
}

// ── Channel Form Dialog ───────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChannelFormDialog(
    title: String,
    form: ChannelFormState,
    onNameChange: (String) -> Unit,
    onTypeChange: (ChannelType) -> Unit,
    onBaseUrlChange: (String) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onToggleKeyVisibility: () -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    confirmLabel: String = "保存"
) {
    var typeExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Channel name
                OutlinedTextField(
                    value = form.name,
                    onValueChange = onNameChange,
                    label = { Text("名称 *") },
                    placeholder = { Text("例如: 生产环境 OpenAI") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // Channel type selector
                ExposedDropdownMenuBox(
                    expanded = typeExpanded,
                    onExpandedChange = { typeExpanded = it }
                ) {
                    OutlinedTextField(
                        value = when (form.type) {
                            ChannelType.OPENAI -> "OpenAI"
                            ChannelType.ANTHROPIC -> "Anthropic"
                        },
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("类型") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = typeExpanded) },
                        modifier = Modifier.menuAnchor(),
                        singleLine = true
                    )

                    ExposedDropdownMenu(
                        expanded = typeExpanded,
                        onDismissRequest = { typeExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("OpenAI") },
                            onClick = {
                                onTypeChange(ChannelType.OPENAI)
                                typeExpanded = false
                            },
                            contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                        )
                        DropdownMenuItem(
                            text = { Text("Anthropic") },
                            onClick = {
                                onTypeChange(ChannelType.ANTHROPIC)
                                typeExpanded = false
                            },
                            contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                        )
                    }
                }

                // Base URL
                OutlinedTextField(
                    value = form.baseUrl,
                    onValueChange = onBaseUrlChange,
                    label = { Text("Base URL *") },
                    placeholder = {
                        Text(
                            when (form.type) {
                                ChannelType.OPENAI -> "https://api.openai.com/v1"
                                ChannelType.ANTHROPIC -> "https://api.anthropic.com"
                            }
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // API Key with visibility toggle
                OutlinedTextField(
                    value = form.apiKey,
                    onValueChange = onApiKeyChange,
                    label = { Text("API Key *") },
                    placeholder = { Text("输入 API Key") },
                    visualTransformation = if (form.apiKeyVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        IconButton(onClick = onToggleKeyVisibility) {
                            Icon(
                                imageVector = if (form.apiKeyVisible) {
                                    Icons.Default.Visibility
                                } else {
                                    Icons.Default.VisibilityOff
                                },
                                contentDescription = if (form.apiKeyVisible) {
                                    "隐藏 API Key"
                                } else {
                                    "显示 API Key"
                                }
                            )
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

// ── Pipeline Config Card ──────────────────────────

@Composable
private fun PipelineConfigCard(
    batchSize: Int,
    concurrency: Int,
    onBatchSizeChange: (Int) -> Unit,
    onConcurrencyChange: (Int) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "高级设置",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Batch size slider
            Text(
                text = "每批文本数: $batchSize",
                style = MaterialTheme.typography.bodyMedium
            )
            Slider(
                value = batchSize.toFloat(),
                onValueChange = { onBatchSizeChange(it.toInt()) },
                valueRange = 1f..200f,
                steps = 198,
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("1", style = MaterialTheme.typography.bodySmall)
                Text("200", style = MaterialTheme.typography.bodySmall)
            }

            HorizontalDivider()

            // Concurrency slider
            Text(
                text = "并行批次数: $concurrency",
                style = MaterialTheme.typography.bodyMedium
            )
            Slider(
                value = concurrency.toFloat(),
                onValueChange = { onConcurrencyChange(it.toInt()) },
                valueRange = 1f..10f,
                steps = 8,
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("1", style = MaterialTheme.typography.bodySmall)
                Text("10", style = MaterialTheme.typography.bodySmall)
            }

            Text(
                text = "每批文本数控制单次 API 调用发送的文本量。并行批次数控制同时进行的 API 调用数（需注意 API 速率限制）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
