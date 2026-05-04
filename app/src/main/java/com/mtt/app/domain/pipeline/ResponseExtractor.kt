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

    // Matches leading numbering: "1.", "12.", "1、", "12、", etc.
    // Group 1 = the number (unused), Group 2 = the text after
    private val NUMBERED_LINE = Regex("""^(\d+)[.\、]\s*(.+)""")

    /**
     * Parses [raw] LLM response text and attempts to extract translations.
     */
    fun parse(raw: String): ExtractionResult {
        // ── Guard: empty ──────────────────────
        if (raw.isBlank()) {
            return ExtractionResult.Error("Empty response", raw)
        }

        // ── Find opening tag ──────────────────
        val openIndex = raw.indexOf(TAG_OPEN, ignoreCase = true)
        if (openIndex == -1) {
            return ExtractionResult.Error("Missing <textarea> tag", raw)
        }

        // ── Find matching closing tag ─────────
        val contentStart = openIndex + TAG_OPEN.length
        val closeIndex = raw.indexOf(TAG_CLOSE, contentStart, ignoreCase = true)
        if (closeIndex == -1) {
            return ExtractionResult.Error("Missing </textarea> tag", raw)
        }

        // ── Extract content ───────────────────
        val content = raw.substring(contentStart, closeIndex).trim()

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
