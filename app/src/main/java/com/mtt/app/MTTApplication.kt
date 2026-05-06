package com.mtt.app

import android.app.Application
import com.mtt.app.core.logger.AppLogger
import com.mtt.app.data.io.StreamingJsonReader
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
     * Uses a dual-layer consumption marker so the config is processed at most
     * once, even across process deaths:
     *   Layer 1 — Internal marker file in [filesDir] (always reliable).
     *   Layer 2 — Consumed-marker written to the config file itself (catches
     *             stale config files left by emulators like MuMu where delete
     *             silently no-ops).
     */
    private fun tryAutoConfigure() {
        val configFile = File("/data/local/tmp/mtt_debug_config.json")

        // ── Layer 1: internal marker (always reliable) ──
        val consumedMarker = File(filesDir, CONSUMED_MARKER_NAME)
        if (consumedMarker.exists()) {
            // Idempotent housekeeping on /data/local/tmp/ leftovers
            if (configFile.exists()) configFile.delete()
            return
        }

        // ── Bail early if config is missing ──
        if (!configFile.exists()) return
        if (configFile.length() < 30) {
            configFile.delete()
            return
        }

        try {
            val jsonString = configFile.readText()

            // ── Layer 2: consumed-marker inside the config file ──
            if (jsonString.startsWith("{\"consumed\"") || jsonString.startsWith("{\"already\"")) {
                configFile.delete()
                return
            }

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
            // Uses StreamingJsonReader to avoid OOM on large files (5MB+).
            if (json.has("test_json_path")) {
                val testPath = json.getString("test_json_path")
                val testFile = File(testPath)
                if (testFile.exists()) {
                    val map = LinkedHashMap<String, String>()
                    try {
                        val inputStream = java.io.FileInputStream(testFile)
                        try {
                            val reader = com.mtt.app.data.io.StreamingJsonReader(inputStream)
                            try {
                                reader.readEntries { key: String, value: String ->
                                    map.put(key, value)
                                }
                            } finally {
                                reader.close()
                            }
                        } finally {
                            inputStream.close()
                        }
                    } catch (e: Exception) {
                        AppLogger.e(TAG, "Failed to read test JSON with streaming reader: ${e.message}")
                        throw e
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

            // ── Mark config as consumed (both layers) ──
            try {
                // Internal marker file — survives anything, always reliable
                consumedMarker.parentFile?.mkdirs()
                consumedMarker.writeText("1")
                AppLogger.i(TAG, "Internal consumed marker written")
            } catch (e: Exception) {
                AppLogger.w(TAG, "Failed to write internal consumed marker: ${e.message}")
            }
            try {
                // Overwrite the external config file with a consumed marker
                // (handles MuMu where delete silently no-ops)
                configFile.writeText("{\"consumed\":true,\"ts\":${System.currentTimeMillis()}}")
                AppLogger.i(TAG, "External config marked as consumed")
            } catch (_: Exception) {
                configFile.delete()
                AppLogger.i(TAG, "External config deleted (write fallback)")
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Auto-configure failed: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "MTTApplication"
        private const val CONSUMED_MARKER_NAME = "mtt_debug_config_consumed"
    }
}
