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
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
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
        onOpenAiKeyVisibilityToggle = viewModel::toggleOpenAiKeyVisibility,
        onOpenAiTestConnection = viewModel::testOpenAiConnection,
        onAnthropicApiKeyChange = viewModel::updateAnthropicApiKey,
        onAnthropicBaseUrlChange = viewModel::updateAnthropicBaseUrl,
        onAnthropicModelChange = viewModel::updateAnthropicModel,
        onAnthropicKeyVisibilityToggle = viewModel::toggleAnthropicKeyVisibility,
        onAnthropicTestConnection = viewModel::testAnthropicConnection
    )
}

@Composable
private fun SettingsScreenContent(
    uiState: SettingsUiState,
    onOpenAiApiKeyChange: (String) -> Unit,
    onOpenAiBaseUrlChange: (String) -> Unit,
    onOpenAiModelChange: (ModelInfo) -> Unit,
    onOpenAiKeyVisibilityToggle: () -> Unit,
    onOpenAiTestConnection: () -> Unit,
    onAnthropicApiKeyChange: (String) -> Unit,
    onAnthropicBaseUrlChange: (String) -> Unit,
    onAnthropicModelChange: (ModelInfo) -> Unit,
    onAnthropicKeyVisibilityToggle: () -> Unit,
    onAnthropicTestConnection: () -> Unit
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
                text = "Settings",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            
            // OpenAI Settings Section
            ProviderSettingsSection(
                title = "OpenAI",
                settings = uiState.openAiSettings,
                onApiKeyChange = onOpenAiApiKeyChange,
                onBaseUrlChange = onOpenAiBaseUrlChange,
                onModelChange = onOpenAiModelChange,
                onKeyVisibilityToggle = onOpenAiKeyVisibilityToggle,
                onTestConnection = onOpenAiTestConnection
            )
            
            // Anthropic Settings Section
            ProviderSettingsSection(
                title = "Anthropic",
                settings = uiState.anthropicSettings,
                onApiKeyChange = onAnthropicApiKeyChange,
                onBaseUrlChange = onAnthropicBaseUrlChange,
                onModelChange = onAnthropicModelChange,
                onKeyVisibilityToggle = onAnthropicKeyVisibilityToggle,
                onTestConnection = onAnthropicTestConnection
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderSettingsSection(
    title: String,
    settings: ProviderSettings,
    onApiKeyChange: (String) -> Unit,
    onBaseUrlChange: (String) -> Unit,
    onModelChange: (ModelInfo) -> Unit,
    onKeyVisibilityToggle: () -> Unit,
    onTestConnection: () -> Unit
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
            
            // Model dropdown
            ModelDropdown(
                label = "Model",
                selectedModel = settings.selectedModel,
                models = settings.availableModels,
                onModelSelected = onModelChange,
                modifier = Modifier.fillMaxWidth()
            )
            
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
                            text = "Testing...",
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
        Text("Test Connection")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelDropdown(
    label: String,
    selectedModel: ModelInfo,
    models: List<ModelInfo>,
    onModelSelected: (ModelInfo) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = selectedModel.displayName,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor()
        )
        
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            models.forEach { model ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(
                                text = model.displayName,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = "Context: ${model.contextWindow / 1000}K tokens",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    onClick = {
                        onModelSelected(model)
                        expanded = false
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                )
            }
        }
    }
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
            onOpenAiKeyVisibilityToggle = {},
            onOpenAiTestConnection = {},
            onAnthropicApiKeyChange = {},
            onAnthropicBaseUrlChange = {},
            onAnthropicModelChange = {},
            onAnthropicKeyVisibilityToggle = {},
            onAnthropicTestConnection = {}
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
            onOpenAiKeyVisibilityToggle = {},
            onOpenAiTestConnection = {},
            onAnthropicApiKeyChange = {},
            onAnthropicBaseUrlChange = {},
            onAnthropicModelChange = {},
            onAnthropicKeyVisibilityToggle = {},
            onAnthropicTestConnection = {}
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
            onOpenAiKeyVisibilityToggle = {},
            onOpenAiTestConnection = {},
            onAnthropicApiKeyChange = {},
            onAnthropicBaseUrlChange = {},
            onAnthropicModelChange = {},
            onAnthropicKeyVisibilityToggle = {},
            onAnthropicTestConnection = {}
        )
    }
}