package com.mtt.app.data.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.mtt.app.data.model.ChannelConfig
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
/**
 * Secure storage for API keys using EncryptedSharedPreferences.
 *
 * API keys are encrypted at rest using AES256_GCM encryption scheme.
 * Keys are NEVER logged to logcat for security.
 *
 * Provided via [SecurityModule]; do NOT annotate with @Inject to avoid
 * duplicate binding conflicts.
 */
class SecureStorage(
    private val context: Context
) {

    private val masterKey: MasterKey by lazy {
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    private val encryptedPrefs: SharedPreferences by lazy {
        EncryptedSharedPreferences.create(
            context,
            PREFS_FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    /**
     * Save API key for the specified provider.
     * @param provider The provider identifier (e.g., "openai", "anthropic")
     * @param apiKey The API key to save (will be encrypted)
     */
    fun saveApiKey(provider: String, apiKey: String) {
        encryptedPrefs.edit()
            .putString(getKeyForProvider(provider), apiKey)
            .apply()
    }

    /**
     * Get API key for the specified provider.
     * @param provider The provider identifier
     * @return The API key, or null if not set
     */
    fun getApiKey(provider: String): String? {
        return encryptedPrefs.getString(getKeyForProvider(provider), null)
    }

    /**
     * Clear API key for the specified provider.
     * @param provider The provider identifier
     */
    fun clearApiKey(provider: String) {
        encryptedPrefs.edit()
            .remove(getKeyForProvider(provider))
            .apply()
    }

    /**
     * Check if API key exists for the specified provider.
     * @param provider The provider identifier
     * @return true if key exists, false otherwise
     */
    fun hasApiKey(provider: String): Boolean {
        return encryptedPrefs.contains(getKeyForProvider(provider))
    }

    /**
     * Save a custom model configuration as JSON.
     */
    fun saveCustomModels(json: String) {
        encryptedPrefs.edit()
            .putString(KEY_CUSTOM_MODELS, json)
            .apply()
    }

    /**
     * Load saved custom model configurations as JSON.
     * Returns null if no custom models have been saved.
     */
    fun getCustomModels(): String? {
        return encryptedPrefs.getString(KEY_CUSTOM_MODELS, null)
    }

    /**
     * Save a simple string value (used for model IDs, language preferences, etc.).
     */
    fun saveValue(key: String, value: String) {
        encryptedPrefs.edit()
            .putString(key, value)
            .apply()
    }

    /**
     * Get a simple string value.
     */
    fun getValue(key: String): String? {
        return encryptedPrefs.getString(key, null)
    }

    /**
     * Remove a simple value by key.
     */
    fun removeValue(key: String) {
        encryptedPrefs.edit()
            .remove(key)
            .apply()
    }

    /**
     * Save all channels as JSON array to encrypted storage.
     */
    fun saveChannels(channels: List<ChannelConfig>) {
        val json = Json { ignoreUnknownKeys = true }
        val jsonString = json.encodeToString(channels)
        encryptedPrefs.edit()
            .putString(KEY_CHANNELS, jsonString)
            .apply()
    }

    /**
     * Load all channels from encrypted storage.
     * Returns empty list if no channels saved.
     */
    fun loadChannels(): List<ChannelConfig> {
        val jsonString = encryptedPrefs.getString(KEY_CHANNELS, null) ?: return emptyList()
        val json = Json { ignoreUnknownKeys = true }
        return try {
            json.decodeFromString<List<ChannelConfig>>(jsonString)
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Save the currently active channel ID.
     */
    fun saveActiveChannelId(id: String) {
        saveValue(KEY_ACTIVE_CHANNEL_ID, id)
    }

    /**
     * Load the currently active channel ID.
     */
    fun loadActiveChannelId(): String? {
        return getValue(KEY_ACTIVE_CHANNEL_ID)
    }

    /**
     * Save the currently active model ID.
     */
    fun saveActiveModelId(id: String) {
        saveValue(KEY_ACTIVE_MODEL_ID, id)
    }

    /**
     * Load the currently active model ID.
     */
    fun loadActiveModelId(): String? {
        return getValue(KEY_ACTIVE_MODEL_ID)
    }

    private fun getKeyForProvider(provider: String): String {
        return when (provider) {
            PROVIDER_OPENAI -> KEY_OPENAI
            PROVIDER_ANTHROPIC -> KEY_ANTHROPIC
            else -> "key_$provider"
        }
    }

    companion object {
        private const val PREFS_FILE_NAME = "secure_api_keys"

        const val PROVIDER_OPENAI = "openai"
        const val PROVIDER_ANTHROPIC = "anthropic"

        private const val KEY_OPENAI = "key_openai"
        private const val KEY_ANTHROPIC = "key_anthropic"
        private const val KEY_CUSTOM_MODELS = "custom_models"

        // Channel-based provider configuration keys
        private const val KEY_CHANNELS = "channel_channels"
        private const val KEY_ACTIVE_CHANNEL_ID = "channel_active_id"
        private const val KEY_ACTIVE_MODEL_ID = "channel_active_model"

        // Model and language preference keys (used by SettingsViewModel and TranslationViewModel)
        const val KEY_OPENAI_MODEL = "openai_model"
        const val KEY_ANTHROPIC_MODEL = "anthropic_model"
        const val KEY_SOURCE_LANG = "source_lang"
        const val KEY_TARGET_LANG = "target_lang"

        // Base URL keys
        const val KEY_OPENAI_BASE_URL = "openai_base_url"
        const val KEY_ANTHROPIC_BASE_URL = "anthropic_base_url"

        // Pipeline config keys
        const val KEY_BATCH_SIZE = "batch_size"       // texts per API call
        const val KEY_CONCURRENCY = "concurrency"     // parallel batches
    }
}