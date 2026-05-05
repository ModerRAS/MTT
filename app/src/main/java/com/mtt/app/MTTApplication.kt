package com.mtt.app

import android.app.Application
import com.mtt.app.core.logger.AppLogger
import com.mtt.app.data.security.SecureStorage
import com.mtt.app.ui.translation.TranslationViewModel
import dagger.hilt.android.HiltAndroidApp
import org.json.JSONObject
import java.io.File

@HiltAndroidApp
class MTTApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        AppLogger.init(this)
        tryAutoConfigure()
    }

    /**
     * Check for a debug config file at /data/local/tmp/mtt_debug_config.json.
     * If found, pre-populate SecureStorage with API keys/base URL/model and
     * optionally auto-load a test JSON file for translation.
     *
     * This file is deleted after successful read to avoid re-configuration.
     */
    private fun tryAutoConfigure() {
        val configFile = File("/data/local/tmp/mtt_debug_config.json")
        if (!configFile.exists()) return

        try {
            val jsonString = configFile.readText()
            val json = JSONObject(jsonString)
            val secureStorage = SecureStorage(this)

            // Configure OpenAI API key
            if (json.has("openai_api_key")) {
                secureStorage.saveApiKey(SecureStorage.PROVIDER_OPENAI, json.getString("openai_api_key"))
                AppLogger.i(TAG, "Auto-configured OpenAI API key")
            }

            // Configure OpenAI base URL
            if (json.has("openai_base_url")) {
                secureStorage.saveValue(SecureStorage.KEY_OPENAI_BASE_URL, json.getString("openai_base_url"))
                AppLogger.i(TAG, "Auto-configured OpenAI base URL")
            }

            // Configure OpenAI model
            if (json.has("openai_model")) {
                secureStorage.saveApiKey(SecureStorage.KEY_OPENAI_MODEL, json.getString("openai_model"))
                AppLogger.i(TAG, "Auto-configured OpenAI model")
            }

            // Read test JSON file if specified and auto-load for translation
            if (json.has("test_json_path")) {
                val testPath = json.getString("test_json_path")
                val testFile = File(testPath)
                if (testFile.exists()) {
                    val testContent = testFile.readText()
                    val testJson = JSONObject(testContent)
                    val map = LinkedHashMap<String, String>()
                    val names = testJson.names()
                    if (names != null) {
                        for (i in 0 until names.length()) {
                            val key = names.getString(i)
                            map[key] = testJson.optString(key, key)
                        }
                    }
                    TranslationViewModel.pendingAutoLoad = TranslationViewModel.AutoLoadData(
                        sourceTexts = map.values.toList(),
                        sourceTextMap = map,
                        fileName = testFile.name
                    )
                    AppLogger.i(TAG, "Auto-loaded test JSON: ${testFile.name} (${map.size} entries)")
                } else {
                    AppLogger.w(TAG, "Test JSON file not found: $testPath")
                }
            }

            // Delete config file to avoid re-configuration on next launch
            configFile.delete()
            AppLogger.i(TAG, "Debug config applied and config file deleted")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Auto-configure failed: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "MTTApplication"
    }
}
