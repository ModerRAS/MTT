@file:OptIn(ExperimentalMaterial3Api::class)

package com.mtt.app.ui.settings

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.mtt.app.data.model.LlmProvider
import com.mtt.app.data.model.ModelInfo
import com.mtt.app.data.remote.llm.ModelRegistry
import com.mtt.app.ui.theme.MttTheme

/**
 * Settings screen for API key, model, and proxy configuration.
 */
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    // Auto-save on exit
    DisposableEffect(Unit) {
        onDispose {
            viewModel.saveSettings()
        }
    }

    SettingsScreenContent(
        uiState = uiState,
        onOpenAiApiKeyChange = viewModel::updateOpenAiApiKey,
        onOpenAiBaseUrlChange = viewModel::updateOpenAiBaseUrl,
        onOpenAiModelChange = viewModel::updateOpenAiModel,
        onOpenAiModelTextChanged = viewModel::updateOpenAiModelById,
        onOpenAiKeyVisibilityToggle = viewModel::toggleOpenAiKeyVisibility,
        onOpenAiTestConnection = viewModel::testOpenAiConnection,
        onAnthropicApiKeyChange = viewModel::updateAnthropicApiKey,
        onAnthropicBaseUrlChange = viewModel::updateAnthropicBaseUrl,
        onAnthropicModelChange = viewModel::updateAnthropicModel,
        onAnthropicModelTextChanged = viewModel::updateAnthropicModelById,
        onAnthropicKeyVisibilityToggle = viewModel::toggleAnthropicKeyVisibility,
        onAnthropicTestConnection = viewModel::testAnthropicConnection,
        onAddCustomModel = viewModel::addCustomModel,
        onRemoveCustomModel = viewModel::removeCustomModel
    )
}

@Composable
private fun SettingsScreenContent(
    uiState: SettingsUiState,
    onOpenAiApiKeyChange: (String) -> Unit,
    onOpenAiBaseUrlChange: (String) -> Unit,
    onOpenAiModelChange: (ModelInfo) -> Unit,
    onOpenAiModelTextChanged: (String) -> Unit,
    onOpenAiKeyVisibilityToggle: () -> Unit,
    onOpenAiTestConnection: () -> Unit,
    onAnthropicApiKeyChange: (String) -> Unit,
    onAnthropicBaseUrlChange: (String) -> Unit,
    onAnthropicModelChange: (ModelInfo) -> Unit,
    onAnthropicModelTextChanged: (String) -> Unit,
    onAnthropicKeyVisibilityToggle: () -> Unit,
    onAnthropicTestConnection: () -> Unit,
    onAddCustomModel: (modelId: String, displayName: String, contextWindow: Int, isAnthropic: Boolean) -> Unit,
    onRemoveCustomModel: (modelId: String) -> Unit
) {
    val scrollState = rememberScrollState()

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
                text = "设置",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground
            )

            // OpenAI Settings Section
            ProviderSettingsSection(
                title = "OpenAI",
                settings = uiState.openAiSettings,
                isAnthropic = false,
                onApiKeyChange = onOpenAiApiKeyChange,
                onBaseUrlChange = onOpenAiBaseUrlChange,
                onModelChange = onOpenAiModelChange,
                onModelTextChanged = onOpenAiModelTextChanged,
                onKeyVisibilityToggle = onOpenAiKeyVisibilityToggle,
                onTestConnection = onOpenAiTestConnection,
                onAddCustomModel = onAddCustomModel,
                onRemoveCustomModel = onRemoveCustomModel
            )

            // Anthropic Settings Section
            ProviderSettingsSection(
                title = "Anthropic",
                settings = uiState.anthropicSettings,
                isAnthropic = true,
                onApiKeyChange = onAnthropicApiKeyChange,
                onBaseUrlChange = onAnthropicBaseUrlChange,
                onModelChange = onAnthropicModelChange,
                onModelTextChanged = onAnthropicModelTextChanged,
                onKeyVisibilityToggle = onAnthropicKeyVisibilityToggle,
                onTestConnection = onAnthropicTestConnection,
                onAddCustomModel = onAddCustomModel,
                onRemoveCustomModel = onRemoveCustomModel
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderSettingsSection(
    title: String,
    settings: ProviderSettings,
    isAnthropic: Boolean,
    onApiKeyChange: (String) -> Unit,
    onBaseUrlChange: (String) -> Unit,
    onModelChange: (ModelInfo) -> Unit,
    onModelTextChanged: (String) -> Unit,
    onKeyVisibilityToggle: () -> Unit,
    onTestConnection: () -> Unit,
    onAddCustomModel: (modelId: String, displayName: String, contextWindow: Int, isAnthropic: Boolean) -> Unit,
    onRemoveCustomModel: (modelId: String) -> Unit
) {
    var showCustomModelDialog by remember { mutableStateOf(false) }

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
            // Section title
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // API Key input with visibility toggle
            OutlinedTextField(
                value = settings.apiKey,
                onValueChange = onApiKeyChange,
                label = { Text("API Key") },
                placeholder = { Text("Enter your API key") },
                isError = settings.apiKeyError != null,
                supportingText = settings.apiKeyError?.let { { Text(it) } },
                visualTransformation = if (settings.isKeyVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    IconButton(onClick = onKeyVisibilityToggle) {
                        Icon(
                            imageVector = if (settings.isKeyVisible) {
                                Icons.Default.Visibility
                            } else {
                                Icons.Default.VisibilityOff
                            },
                            contentDescription = if (settings.isKeyVisible) {
                                "Hide API key"
                            } else {
                                "Show API key"
                            }
                        )
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            // Base URL input
            OutlinedTextField(
                value = settings.baseUrl,
                onValueChange = onBaseUrlChange,
                label = { Text("Base URL") },
                placeholder = { Text("https://api.openai.com/v1") },
                isError = settings.baseUrlError != null,
                supportingText = settings.baseUrlError?.let { { Text(it) } },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            // Editable model input with dropdown suggestions
            EditableModelDropdown(
                label = "模型",
                selectedModel = settings.selectedModel,
                models = settings.availableModels,
                onModelSelected = onModelChange,
                onModelTextChanged = onModelTextChanged,
                onAddCustomModel = { onAddCustomModel(it, it, 128000, isAnthropic) },
                modifier = Modifier.fillMaxWidth()
            )

            // Custom model management row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = { showCustomModelDialog = true },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Add, contentDescription = "添加自定义模型")
                    Spacer(Modifier.width(4.dp))
                    Text("自定义模型")
                }

                // Show delete button if custom model is selected
                if (settings.selectedModel.isCustom) {
                    OutlinedButton(
                        onClick = { onRemoveCustomModel(settings.selectedModel.modelId) },
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = "删除模型")
                        Spacer(Modifier.width(4.dp))
                        Text("删除")
                    }
                }
            }

            // Test Connection button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                when (val testState = settings.testConnectionState) {
                    is TestConnectionState.Testing -> {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .width(24.dp)
                                .height(24.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "测试中...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    is TestConnectionState.Success -> {
                        Text(
                            text = testState.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        TestConnectionButton(
                            onClick = onTestConnection,
                            enabled = settings.apiKey.isNotBlank() && settings.baseUrlError == null
                        )
                    }
                    is TestConnectionState.Error -> {
                        Text(
                            text = testState.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        TestConnectionButton(
                            onClick = onTestConnection,
                            enabled = settings.apiKey.isNotBlank() && settings.baseUrlError == null
                        )
                    }
                    is TestConnectionState.Idle -> {
                        TestConnectionButton(
                            onClick = onTestConnection,
                            enabled = settings.apiKey.isNotBlank() && settings.baseUrlError == null
                        )
                    }
                }
            }
        }
    }

    // Custom model configuration dialog
    if (showCustomModelDialog) {
        CustomModelDialog(
            isAnthropic = isAnthropic,
            onDismiss = { showCustomModelDialog = false },
            onConfirm = { modelId, displayName, contextWindow ->
                onAddCustomModel(modelId, displayName, contextWindow, isAnthropic)
                // Find and select the newly added model
                val newModel = ModelRegistry.getById(modelId)
                if (newModel != null) {
                    onModelChange(newModel)
                }
                showCustomModelDialog = false
            }
        )
    }
}

@Composable
private fun TestConnectionButton(
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
    ) {
        Text("测试连接")
    }
}

/**
 * Editable model dropdown - user can type any model name or select from suggestions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditableModelDropdown(
    label: String,
    selectedModel: ModelInfo,
    models: List<ModelInfo>,
    onModelSelected: (ModelInfo) -> Unit,
    onModelTextChanged: (String) -> Unit,
    onAddCustomModel: (modelId: String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    var text by remember(selectedModel) { mutableStateOf(selectedModel.displayName) }
    val filteredModels = remember(text, models) {
        models.filter {
            it.displayName.contains(text, ignoreCase = true) ||
            it.modelId.contains(text, ignoreCase = true)
        }
    }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { newValue ->
                text = newValue
                expanded = true
                onModelTextChanged(newValue)
                // Try to find exact match
                val match = models.firstOrNull {
                    it.displayName == newValue || it.modelId == newValue
                }
                if (match != null) {
                    onModelSelected(match)
                }
            },
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor(),
            singleLine = true,
            placeholder = { Text("输入或选择模型名") }
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            // Show matching preset + custom models
            filteredModels.forEach { model ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(
                                text = model.displayName,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Row {
                                Text(
                                    text = "Context: ${model.contextWindow / 1000}K tokens",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (model.isCustom) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "自定义",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.tertiary
                                    )
                                }
                            }
                        }
                    },
                    onClick = {
                        text = model.displayName
                        onModelSelected(model)
                        expanded = false
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                )
            }

            // If no exact match, show "Add custom model" option
            if (text.isNotBlank() && models.none { it.modelId == text || it.displayName == text }) {
                DropdownMenuItem(
                    text = {
                        Text(
                            text = "添加自定义模型 \"$text\"",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    },
                    onClick = {
                        onAddCustomModel(text)
                        expanded = false
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                )
            }
        }
    }
}

/**
 * Dialog for adding a custom model with full configuration.
 */
@Composable
private fun CustomModelDialog(
    isAnthropic: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (modelId: String, displayName: String, contextWindow: Int) -> Unit
) {
    var modelId by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var contextWindowText by remember { mutableStateOf("128000") }
    var modelIdError by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("配置自定义模型") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "供应商: ${if (isAnthropic) "Anthropic" else "OpenAI"}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = modelId,
                    onValueChange = {
                        modelId = it
                        modelIdError = if (it.isBlank()) "Model ID 不能为空" else null
                    },
                    label = { Text("Model ID") },
                    placeholder = { Text(if (isAnthropic) "claude-3-opus-20240229" else "gpt-4-turbo") },
                    isError = modelIdError != null,
                    supportingText = modelIdError?.let { { Text(it) } },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text("显示名称 (可选)") },
                    placeholder = { Text("Claude 3 Opus") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = contextWindowText,
                    onValueChange = { contextWindowText = it },
                    label = { Text("Context Window (tokens)") },
                    placeholder = { Text("128000") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    text = "Context window 影响 token 估算和批处理大小。不同模型的 context window 不同，建议查阅模型官方文档。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (modelId.isBlank()) {
                        modelIdError = "Model ID 不能为空"
                        return@Button
                    }
                    val contextWindow = contextWindowText.toIntOrNull() ?: 128000
                    val name = displayName.ifBlank { modelId }
                    onConfirm(modelId, name, contextWindow)
                },
                enabled = modelId.isNotBlank()
            ) {
                Text("添加")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
private fun SettingsScreenPreview() {
    MttTheme(dynamicColor = false) {
        SettingsScreenContent(
            uiState = SettingsUiState(),
            onOpenAiApiKeyChange = {},
            onOpenAiBaseUrlChange = {},
            onOpenAiModelChange = {},
            onOpenAiModelTextChanged = {},
            onOpenAiKeyVisibilityToggle = {},
            onOpenAiTestConnection = {},
            onAnthropicApiKeyChange = {},
            onAnthropicBaseUrlChange = {},
            onAnthropicModelChange = {},
            onAnthropicModelTextChanged = {},
            onAnthropicKeyVisibilityToggle = {},
            onAnthropicTestConnection = {},
            onAddCustomModel = { _, _, _, _ -> },
            onRemoveCustomModel = {}
        )
    }
}

@Preview(showBackground = true, showSystemUi = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun SettingsScreenDarkPreview() {
    MttTheme(darkTheme = true, dynamicColor = false) {
        SettingsScreenContent(
            uiState = SettingsUiState(),
            onOpenAiApiKeyChange = {},
            onOpenAiBaseUrlChange = {},
            onOpenAiModelChange = {},
            onOpenAiModelTextChanged = {},
            onOpenAiKeyVisibilityToggle = {},
            onOpenAiTestConnection = {},
            onAnthropicApiKeyChange = {},
            onAnthropicBaseUrlChange = {},
            onAnthropicModelChange = {},
            onAnthropicModelTextChanged = {},
            onAnthropicKeyVisibilityToggle = {},
            onAnthropicTestConnection = {},
            onAddCustomModel = { _, _, _, _ -> },
            onRemoveCustomModel = {}
        )
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
private fun SettingsScreenWithTestDataPreview() {
    MttTheme(dynamicColor = false) {
        SettingsScreenContent(
            uiState = SettingsUiState(
                openAiSettings = ProviderSettings(
                    apiKey = "sk-test-key-1234567890",
                    apiKeyError = null,
                    baseUrl = "https://api.openai.com/v1",
                    baseUrlError = null,
                    testConnectionState = TestConnectionState.Success("Connection successful"),
                    selectedModel = ModelRegistry.defaultOpenAiModel,
                    defaultModel = ModelRegistry.defaultOpenAiModel,
                    defaultBaseUrl = "https://api.openai.com/v1"
                ),
                anthropicSettings = ProviderSettings(
                    apiKey = "sk-ant-test-key-1234567890",
                    apiKeyError = "API key cannot be empty",
                    baseUrl = "https://api.anthropic.com",
                    baseUrlError = null,
                    testConnectionState = TestConnectionState.Error("Invalid API key"),
                    selectedModel = ModelRegistry.defaultAnthropicModel,
                    defaultModel = ModelRegistry.defaultAnthropicModel,
                    defaultBaseUrl = "https://api.anthropic.com"
                )
            ),
            onOpenAiApiKeyChange = {},
            onOpenAiBaseUrlChange = {},
            onOpenAiModelChange = {},
            onOpenAiModelTextChanged = {},
            onOpenAiKeyVisibilityToggle = {},
            onOpenAiTestConnection = {},
            onAnthropicApiKeyChange = {},
            onAnthropicBaseUrlChange = {},
            onAnthropicModelChange = {},
            onAnthropicModelTextChanged = {},
            onAnthropicKeyVisibilityToggle = {},
            onAnthropicTestConnection = {},
            onAddCustomModel = { _, _, _, _ -> },
            onRemoveCustomModel = {}
        )
    }
}
