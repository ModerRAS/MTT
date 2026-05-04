package com.mtt.app.data.io

import android.content.Context
import android.net.Uri
import com.mtt.app.core.error.MttException
import com.mtt.app.core.error.ParseException
import com.mtt.app.core.error.Result
import com.mtt.app.core.error.StorageException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Reads MTool-format flat JSON files via Android SAF (Storage Access Framework).
 *
 * Supports:
 * - SAF URIs (local, cloud, etc.)
 * - UTF-8, UTF-16 (LE/BE) with BOM auto-detection
 * - Unicode escape sequences (\uXXXX) via kotlinx.serialization
 * - Strict validation: flat {key: value} only, all string values
 *
 * Usage:
 * ```kotlin
 * val reader = MtoolFileReader(context)
 * when (val result = reader.readFromUri(uri)) {
 *     is Result.Success -> { val data = result.data; ... }
 *     is Result.Failure -> { handleError(result.exception) }
 * }
 * ```
 */
class MtoolFileReader(private val context: Context) {

    /**
     * Reads and parses an MTool JSON file from a SAF URI.
     *
     * @param uri The SAF URI pointing to the JSON file
     * @return Result.Success with LinkedHashMap (preserving key order), or Result.Failure
     */
    fun readFromUri(uri: Uri): Result<Map<String, String>> {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: return Result.failure(StorageException("无法打开文件: 文件不存在或无法访问"))

            val bytes = inputStream.use { it.readBytes() }
            val text = bytes.decodeWithBomDetection()
            val jsonText = text.trim()

            // Validate format before parsing into map
            val validationResult = JsonValidator.validate(jsonText)
            if (validationResult != ValidationResult.VALID) {
                return Result.failure(mapValidationError(validationResult))
            }

            parseMtoolJson(jsonText)
        } catch (e: MttException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(ParseException("文件读取失败: ${e.message}"))
        }
    }

    /**
     * Parses a validated MTool JSON string into a LinkedHashMap.
     * Preserves key order from the JSON file.
     */
    private fun parseMtoolJson(jsonText: String): Result<Map<String, String>> {
        return try {
            val jsonElement = json.parseToJsonElement(jsonText)
            val obj = jsonElement.jsonObject

            val result = LinkedHashMap<String, String>(obj.size)
            for ((key, value) in obj) {
                result[key] = value.jsonPrimitive.content
            }

            Result.success(result)
        } catch (e: Exception) {
            Result.failure(ParseException("JSON 解析失败: ${e.message}"))
        }
    }

    private fun mapValidationError(result: ValidationResult): MttException {
        return when (result) {
            ValidationResult.INVALID_JSON -> ParseException("JSON 格式无效")
            ValidationResult.NESTED_JSON -> ParseException("不支持嵌套 JSON，请使用扁平键值对格式")
            ValidationResult.NON_STRING_VALUES -> ParseException("JSON 值必须为字符串类型")
            ValidationResult.EMPTY -> ParseException("JSON 文件为空")
            else -> ParseException("数据验证失败")
        }
    }

    companion object {
        /**
         * Shared JSON instance for parsing.
         * isLenient = true allows BOM handling and non-standard JSON.
         */
        private val json = Json {
            ignoreUnknownKeys = false
            isLenient = true
            coerceInputValues = false
        }

        // Byte Order Mark constants
        private val BOM_UTF8 = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        private val BOM_UTF16_LE = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        private val BOM_UTF16_BE = byteArrayOf(0xFE.toByte(), 0xFF.toByte())

        /**
         * Decodes a byte array with BOM detection and stripping.
         *
         * Detection priority:
         * 1. UTF-8 BOM (EF BB BF) → strip 3 bytes, decode as UTF-8
         * 2. UTF-16 LE BOM (FF FE) → strip 2 bytes, decode as UTF-16LE
         * 3. UTF-16 BE BOM (FE FF) → strip 2 bytes, decode as UTF-16BE
         * 4. No BOM → decode as UTF-8 (default)
         */
        fun ByteArray.decodeWithBomDetection(): String {
            return when {
                startsWith(BOM_UTF8) -> {
                    this.copyOfRange(3, this.size).toString(Charsets.UTF_8)
                }
                startsWith(BOM_UTF16_LE) -> {
                    this.copyOfRange(2, this.size).toString(Charsets.UTF_16LE)
                }
                startsWith(BOM_UTF16_BE) -> {
                    this.copyOfRange(2, this.size).toString(Charsets.UTF_16BE)
                }
                else -> {
                    this.toString(Charsets.UTF_8)
                }
            }
        }

        /**
         * Checks if this byte array starts with the given prefix bytes.
         */
        private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
            if (this.size < prefix.size) return false
            for (i in prefix.indices) {
                if (this[i] != prefix[i]) return false
            }
            return true
        }
    }
}
