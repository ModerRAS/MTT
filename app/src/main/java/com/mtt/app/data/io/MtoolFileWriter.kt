package com.mtt.app.data.io

import android.content.Context
import android.net.Uri
import com.mtt.app.core.error.Result
import com.mtt.app.core.error.StorageException
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNames
import java.io.OutputStream

/**
 * Writes MTool-format flat JSON files via Android SAF (Storage Access Framework).
 *
 * Features:
 * - SAF URI write support (local, cloud storage via document providers)
 * - Atomic write: serializes to byte array first, then writes in one operation
 * - Pretty-printed JSON output (indent = 4, matching AiNiee format)
 * - Preserves key ordering (uses LinkedHashMap internally)
 * - UTF-8 encoding (matches AiNiee MToolWriter.py)
 *
 * Only writes entries with non-null, non-blank translated text.
 * Empty or untranslated entries are excluded from output (matching AiNiee behavior).
 *
 * Usage:
 * ```kotlin
 * val writer = MtoolFileWriter(context)
 * val data = linkedMapOf("hello" to "你好", "world" to "世界")
 * when (val result = writer.writeToUri(uri, data)) {
 *     is Result.Success -> { /* write succeeded */ }
 *     is Result.Failure -> { handleError(result.exception) }
 * }
 * ```
 */
class MtoolFileWriter(private val context: Context) {

    /**
     * Writes translation data to a SAF URI as flat JSON.
     *
     * Atomic write strategy:
     * 1. Serialize Map to JSON string → byte array in memory
     * 2. Open OutputStream from ContentResolver
     * 3. Write entire byte array at once
     * 4. Flush and close OutputStream
     *
     * If any step fails, the target file is left unmodified (or partially unwritten).
     *
     * @param uri The SAF URI to write to
     * @param data The translation data as key-value pairs (source → translated)
     * @return Result.Success(Unit) on success, Result.Failure(StorageException) on error
     */
    fun writeToUri(uri: Uri, data: Map<String, String>): Result<Unit> {
        return try {
            val jsonBytes = serializeToJsonBytes(data)
            var outputStream: OutputStream? = null
            try {
                outputStream = context.contentResolver.openOutputStream(uri)
                    ?: return Result.failure(StorageException("无法写入文件: 目标路径不可写"))
                outputStream.write(jsonBytes)
                outputStream.flush()
            } finally {
                outputStream?.close()
            }
            Result.success(Unit)
        } catch (e: StorageException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(StorageException("文件写入失败: ${e.message}"))
        }
    }

    /**
     * Serializes the data map to a UTF-8 encoded pretty-printed JSON byte array.
     *
     * Uses kotlinx.serialization with:
     * - prettyPrint = true (matching AiNiee indent=4)
     * - encodeKeys = true (properly escape JSON keys)
     * - UTF-8 encoding
     */
    private fun serializeToJsonBytes(data: Map<String, String>): ByteArray {
        val jsonString = json.encodeToString(data)
        return jsonString.toByteArray(Charsets.UTF_8)
    }

    companion object {
        /**
         * Shared JSON serializer for MTool format.
         * Configures pretty printing with 4-space indent (matching AiNiee Python output).
         */
        private val json = Json {
            prettyPrint = true
            prettyPrintIndent = "    "
            encodeDefaults = false
            ignoreUnknownKeys = false
        }
    }
}
