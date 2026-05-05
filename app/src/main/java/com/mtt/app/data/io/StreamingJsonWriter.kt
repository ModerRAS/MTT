package com.mtt.app.data.io

import java.io.OutputStream

/**
 * Streaming JSON writer for MTool flat JSON format.
 *
 * Writes JSON key-value pairs one by one to an [OutputStream] without
 * building the entire JSON tree in memory. This avoids OOM on large exports.
 *
 * Usage:
 * ```kotlin
 * val writer = StreamingJsonWriter(outputStream)
 * writer.use { w ->
 *     for ((key, value) in data) {
 *         w.writeEntry(key, value)
 *     }
 * }
 * ```
 */
class StreamingJsonWriter(private val outputStream: OutputStream) : AutoCloseable {

    private var entryCount = 0
    private var closed = false

    /**
     * Write a single key-value entry.
     * Must be called between [writeBegin] and [writeEnd].
     */
    fun writeEntry(key: String, value: String) {
        if (entryCount > 0) {
            outputStream.write(",\n".toByteArray())
        }
        val indent = "  "
        val escapedKey = escapeJsonString(key)
        val escapedValue = escapeJsonString(value)
        outputStream.write("$indent\"$escapedKey\": \"$escapedValue\"".toByteArray())
        entryCount++
    }

    /**
     * Write the opening brace and optional newline.
     * Call before any [writeEntry] calls.
     */
    fun writeBegin() {
        outputStream.write("{\n".toByteArray())
    }

    /**
     * Write the closing brace and newline, then flush.
     * Call after all [writeEntry] calls.
     */
    fun writeEnd() {
        if (!closed) {
            outputStream.write("\n}".toByteArray())
            outputStream.flush()
            closed = true
        }
    }

    override fun close() {
        if (!closed) {
            writeEnd()
        }
        outputStream.close()
    }

    companion object {
        /**
         * Escape a string for safe inclusion in a JSON value.
         * Escapes backslash, quote, newline, carriage return, tab, and control characters.
         */
        private fun escapeJsonString(s: String): String {
            val sb = StringBuilder(s.length + 16)
            for (ch in s) {
                when (ch) {
                    '\\' -> sb.append("\\\\")
                    '"' -> sb.append("\\\"")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    '\b' -> sb.append("\\b")
                    '\u000C' -> sb.append("\\f")  // form feed
                    else -> {
                        if (ch.code in 0..0x1F) {
                            sb.append("\\u${ch.code.toString(16).padStart(4, '0')}")
                        } else {
                            sb.append(ch)
                        }
                    }
                }
            }
            return sb.toString()
        }
    }
}
