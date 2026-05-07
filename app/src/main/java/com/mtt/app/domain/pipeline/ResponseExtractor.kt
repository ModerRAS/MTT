package com.mtt.app.domain.pipeline

/**
 * Result of extracting translations from an LLM response.
 */
sealed class ExtractionResult {

    /**
     * Translations successfully extracted, one per line.
     * May be empty if the textarea contained no content.
     */
    data class Success(val translations: List<String>) : ExtractionResult()

    /**
     * Extraction failed — the response is malformed or unparseable.
     *
     * @param message Human-readable description of the failure.
     * @param raw     The original raw response text.
     */
    data class Error(val message: String, val raw: String) : ExtractionResult()
}

/**
 * Parses LLM response text in AiNiee format and extracts numbered translation lines.
 *
 * ## Format
 *
 * The LLM is expected to respond with:
 * ```
 * <textarea>
 * 1. 译文1
 * 2. 译文2
 * 3. 译文3
 * </textarea>
 * ```
 *
 * ## Extraction rules
 *
 * - Locates the **first** `<textarea>...</textarea>` pair.
 * - Splits the inner content by newline.
 * - Strips leading numbering (`1.`, `1、`, etc.) from each non‑blank line.
 * - Unnumbered lines are kept as‑is.
 * - Content outside the textarea tags is ignored.
 *
 * ## Error conditions
 *
 * | Condition                        | Result         |
 * |----------------------------------|----------------|
 * | No `<textarea>` tag              | `Error`        |
 * | No matching `</textarea>` tag    | `Error`        |
 * | Blank / whitespace‑only input    | `Error`        |
 *
 * ## Thread safety
 *
 * This object is stateless and all methods are thread‑safe.
 */
object ResponseExtractor {

    private const val TAG_OPEN = "<textarea>"
    private const val TAG_CLOSE = "</textarea>"
    private const val THINK_OPEN = "<think>"
    private const val THINK_CLOSE = "</think>"

    // Matches leading numbering: "1.", "12.", "1、", "12、", etc.
    // Group 1 = the number (unused), Group 2 = the text after
    private val NUMBERED_LINE = Regex("""^(\d+)[.\、]\s*(.+)""")

    /**
     * Strips `<think>...</think>` blocks from the raw LLM response.
     *
     * Some reasoning models (DeepSeek-R1, QwQ, MiniMax) wrap chain-of-thought
     * in `<think>...</think>` tags. This content must be removed before
     * searching for `<textarea>` to avoid false matches or parsing confusion.
     */
    private fun stripThinkTags(raw: String): String {
        val sb = StringBuilder(raw)
        var start = sb.indexOf(THINK_OPEN, ignoreCase = true)
        while (start != -1) {
            val end = sb.indexOf(THINK_CLOSE, start + THINK_OPEN.length, ignoreCase = true)
            if (end == -1) {
                // Unclosed <think> — remove from start to end
                sb.delete(start, sb.length)
                break
            }
            sb.delete(start, end + THINK_CLOSE.length)
            start = sb.indexOf(THINK_OPEN, ignoreCase = true)
        }
        return sb.toString()
    }

    /**
     * Parses [raw] LLM response text and attempts to extract translations.
     *
     * Before parsing, strips any `<think>...</think>` blocks (used by
     * reasoning models) to avoid interference with `<textarea>` extraction.
     */
    fun parse(raw: String): ExtractionResult {
        // ── Strip reasoning blocks first ───────
        val cleaned = stripThinkTags(raw)

        // ── Guard: empty ──────────────────────
        if (cleaned.isBlank()) {
            return ExtractionResult.Error("Empty response", raw)
        }

        // ── Find opening tag ──────────────────
        val openIndex = cleaned.indexOf(TAG_OPEN, ignoreCase = true)
        if (openIndex == -1) {
            return ExtractionResult.Error("Missing <textarea> tag", raw)
        }

        // ── Find matching closing tag ─────────
        val contentStart = openIndex + TAG_OPEN.length
        val closeIndex = cleaned.indexOf(TAG_CLOSE, contentStart, ignoreCase = true)
        if (closeIndex == -1) {
            return ExtractionResult.Error("Missing </textarea> tag", raw)
        }

        // ── Extract content ───────────────────
        val content = cleaned.substring(contentStart, closeIndex).trim()

        if (content.isEmpty()) {
            return ExtractionResult.Success(emptyList())
        }

        // ── Split lines, prune blanks, strip numbering ──
        val translations = content.split("\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { line ->
                NUMBERED_LINE.matchEntire(line)?.groupValues?.get(2) ?: line
            }

        return ExtractionResult.Success(translations)
    }
}
