package com.mtt.app.data.io

import android.util.JsonReader
import java.io.InputStream
import java.io.InputStreamReader

/**
 * Streaming JSON reader for MTool flat JSON format.
 *
 * Reads key-value pairs one by one using [JsonReader] instead of loading
 * the entire JSON tree into memory. This avoids OOM on large files (e.g. 5MB+).
 *
 * Usage:
 * ```kotlin
 * val reader = StreamingJsonReader(inputStream)
 * reader.use { r ->
 *     r.readEntries { key, value ->
 *         map[key] = value
 *     }
 * }
 * ```
 */
class StreamingJsonReader(private val inputStream: InputStream) : AutoCloseable {

    private val reader: JsonReader = JsonReader(InputStreamReader(inputStream, Charsets.UTF_8))

    /**
     * Reads all key-value entries from the flat JSON object.
     * Calls [onEntry] for each entry found.
     *
     * @return Number of entries read
     * @throws IllegalStateException if JSON is not a flat object
     */
    fun readEntries(onEntry: (key: String, value: String) -> Unit): Int {
        var count = 0

        reader.beginObject()
        while (reader.hasNext()) {
            val key = reader.nextName()
            val value = when (reader.peek()) {
                android.util.JsonToken.STRING -> reader.nextString()
                android.util.JsonToken.NUMBER -> reader.nextDouble().toBigDecimal().stripTrailingZeros().toPlainString()
                android.util.JsonToken.BOOLEAN -> reader.nextBoolean().toString()
                android.util.JsonToken.NULL -> { reader.nextNull(); "" }
                else -> throw IllegalStateException(
                    "MTool JSON must have string values only, but found ${reader.peek()} at key '$key'"
                )
            }
            onEntry(key, value)
            count++
        }
        reader.endObject()

        return count
    }

    override fun close() {
        reader.close()
    }
}
