package com.mtt.app.domain.pipeline

/**
 * Validates LLM translation responses for quality and format integrity.
 *
 * Performs checks in order (short-circuiting on first failure):
 * 1. **Refusal** — model declined the request (refusal phrases or empty response)
 * 2. **Count** — number of extracted translations matches [expectedCount]
 * 3. **Language** — translation script mismatch (CJK vs Latin)
 *
 * This is a pure validation utility — no LLM calls, no I/O.
 *
 * ## Response format
 *
 * Responses are expected to follow the AiNiee `<textarea>` convention:
 * ```
 * <textarea>
 * 1. 译文1
 * 2. 译文2
 * 3. 译文3
 * </textarea>
 * ```
 *
 * Numbering supports both dot (`.`) and Chinese enumeration comma (`、`)
 * separators. Content outside `<textarea>` tags is ignored for count and
 * language checks but is still examined for refusal phrases.
 *
 * ## Thread safety
 *
 * Stateless and thread‑safe.
 */
object ResponseChecker {

    // ──────────────────────────────────────────────
    //  Public API
    // ──────────────────────────────────────────────

    /**
     * Validates a raw LLM response against the expected number of translations.
     *
     * Checks are applied in priority order and short‑circuit on the first
     * failure:
     *
     * 1. Refusal detection (empty response or refusal phrases)
     * 2. Translation count vs [expectedCount]
     * 3. Language script mismatch (CJK/Latin mix)
     *
     * @param response      Raw LLM response string (may contain `<textarea>` tags).
     * @param expectedCount Number of translations expected in the response.
     *                      Use 0 when no translations are expected (e.g. empty batch).
     * @return [ValidationResult] indicating pass/fail and the failure reason.
     */
    fun validateResponse(
        response: String,
        expectedCount: Int
    ): ValidationResult {
        // 1. Check for refusal
        if (isRefusal(response)) {
            return ValidationResult.Refused
        }

        // 2. Count translations
        val actualCount = countTranslations(response)

        if (actualCount < expectedCount) {
            return ValidationResult.TooFew
        }
        if (actualCount > expectedCount) {
            return ValidationResult.TooMany
        }

        // 3. Check language mismatch
        if (isLanguageMismatch(response)) {
            return ValidationResult.WrongLanguage
        }

        return ValidationResult.Valid
    }

    // ──────────────────────────────────────────────
    //  Refusal detection
    // ──────────────────────────────────────────────

    /**
     * Phrases that indicate the LLM refused to perform the translation.
     * All checks are case-insensitive.
     */
    private val REFUSAL_PHRASES = listOf(
        "i cannot translate",
        "i can't translate",
        "i'm unable to translate",
        "i am unable to translate",
        "i cannot provide",
        "i can't provide",
        "i'm not able to translate",
        "i am not able to translate",
        "unable to translate",
        "cannot comply with",
        "can't comply with",
        "i'm sorry",
        "i am sorry",
        "i apologize",
        "as an ai",
        "as a language model",
        "against my guidelines",
        "against my policy",
        "violates the policy"
    )

    /**
     * Detects whether the LLM refused the translation request.
     *
     * An empty or whitespace‑only response is always treated as a refusal.
     * Otherwise the response text is scanned (case‑insensitively) for known
     * refusal phrases.
     */
    private fun isRefusal(response: String): Boolean {
        if (response.isBlank()) return true

        val lower = response.lowercase()
        return REFUSAL_PHRASES.any { phrase -> phrase in lower }
    }

    // ──────────────────────────────────────────────
    //  Count checking
    // ──────────────────────────────────────────────

    /**
     * Counts the number of translation items in the response.
     *
     * If the response contains `<textarea>` tags, only content between
     * the first matching tag pair is counted. Otherwise the entire
     * response body is examined.
     *
     * Only non‑empty lines starting with a number followed by a dot or
     * Chinese enumeration comma are counted (e.g. `1.`, `12.`, `1、`, `12、`).
     */
    private fun countTranslations(response: String): Int {
        val content = extractTextareaContent(response)
        if (content.isEmpty()) return 0

        return content.split("\n")
            .count { line -> isNumberedLine(line.trim()) }
    }

    /**
     * Extracts content between the first `<textarea>` and `</textarea>` tags
     * (case‑insensitive). Returns the full [response] if no opening tag is found.
     */
    private fun extractTextareaContent(response: String): String {
        val openTag = "<textarea>"
        val closeTag = "</textarea>"

        val openIndex = response.indexOf(openTag, ignoreCase = true)
        if (openIndex == -1) {
            return response // no tags — use the whole response
        }

        val contentStart = openIndex + openTag.length
        val closeIndex = response.indexOf(closeTag, contentStart, ignoreCase = true)

        return if (closeIndex == -1) {
            // Missing closing tag — everything after the opening tag
            response.substring(contentStart).trim()
        } else {
            response.substring(contentStart, closeIndex).trim()
        }
    }

    /**
     * Returns `true` when [line] starts with a number followed by
     * a dot (`.`) or Chinese enumeration comma (`、`), and has content
     * after the separator.
     *
     * Examples:
     * - `1. 译文`  → true
     * - `12、abc`  → true
     * - `1.`       → false  (no content after separator)
     * - `译文`     → false  (not numbered)
     * - `1`        → false  (missing separator)
     */
    private fun isNumberedLine(line: String): Boolean {
        if (line.isEmpty()) return false

        var i = 0
        // Consume leading digits
        while (i < line.length && line[i].isDigit()) {
            i++
        }

        // Need at least one digit
        if (i == 0) return false

        // Check for separator
        if (i >= line.length) return false
        val separator = line[i]
        if (separator != '.' && separator != '\u3001') return false

        // Must have content after the separator (not just the separator itself)
        return i + 1 < line.length
    }

    // ──────────────────────────────────────────────
    //  Language mismatch detection
    // ──────────────────────────────────────────────

    /**
     * CJK Unicode blocks. Characters in these blocks are considered
     * part of a Chinese / Japanese / Korean script.
     */
    private val CJK_BLOCKS = setOf(
        Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS,
        Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A,
        Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B,
        Character.UnicodeBlock.HIRAGANA,
        Character.UnicodeBlock.KATAKANA,
        Character.UnicodeBlock.KATAKANA_PHONETIC_EXTENSIONS,
        Character.UnicodeBlock.HANGUL_SYLLABLES,
        Character.UnicodeBlock.HANGUL_JAMO,
        Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO
    )

    /**
     * Detects when the response content has an unexpected mix of CJK and
     * Latin scripts — typically indicating the model responded in the
     * wrong language or left source text partially untranslated.
     *
     * Strategy:
     * 1. **First/last character heuristic** — if the first and last
     *    meaningful (letter) characters belong to different script
     *    families, the response is likely mixed.
     * 2. **Overall script ratio** — if 20–80 % of letter characters are
     *    CJK and the rest are Latin, the response has a suspicious mix.
     */
    private fun isLanguageMismatch(response: String): Boolean {
        val content = extractTranslatableContent(response)

        // Collect only letter characters for analysis
        val letters = content.filter { it.isLetter() }
        if (letters.length < 10) {
            // Not enough text to reliably determine language
            return false
        }

        // ── First/last character heuristic ──
        val firstIsCjk = isCjk(letters.first())
        val lastIsCjk = isCjk(letters.last())
        if (firstIsCjk != lastIsCjk) {
            return true
        }

        // ── Overall script ratio ──
        var cjkCount = 0
        var latinCount = 0

        for (ch in letters) {
            if (isCjk(ch)) {
                cjkCount++
            } else if (ch in 'a'..'z' || ch in 'A'..'Z') {
                latinCount++
            }
        }

        val total = cjkCount + latinCount
        if (total == 0) return false

        val cjkRatio = cjkCount.toDouble() / total

        // A mix where CJK ratio falls between 20 % and 80 % is suspicious:
        // neither predominantly CJK nor predominantly Latin.
        return cjkRatio > 0.20 && cjkRatio < 0.80
    }

    /**
     * Extracts only the translatable text from the response by:
     * 1. Extracting content from inside `<textarea>` tags (if present).
     * 2. Stripping the numbering prefix from each numbered line.
     * 3. Joining the remaining text for script analysis.
     */
    private fun extractTranslatableContent(response: String): String {
        val body = extractTextareaContent(response)
        if (body.isEmpty()) return ""

        return body.split("\n")
            .map { line ->
                val trimmed = line.trim()
                if (isNumberedLine(trimmed)) {
                    // Strip numbering prefix: "1. text" → "text"
                    var i = 0
                    while (i < trimmed.length && trimmed[i].isDigit()) i++
                    if (i < trimmed.length) i++ // skip separator (`. ` or `、`)
                    if (i < trimmed.length && trimmed[i] == ' ') i++ // skip optional space
                    trimmed.substring(i).trimStart()
                } else {
                    trimmed
                }
            }
            .filter { it.isNotBlank() }
            .joinToString("")
    }

    /**
     * Returns `true` when [ch] belongs to a CJK Unicode block.
     */
    private fun isCjk(ch: Char): Boolean {
        return Character.UnicodeBlock.of(ch) in CJK_BLOCKS
    }
}

// ─────────────────────────────────────────────────────────
//  ValidationResult
// ─────────────────────────────────────────────────────────

/**
 * Outcome of [ResponseChecker.validateResponse].
 */
sealed interface ValidationResult {

    /** Response passed all checks — count, refusal, and language are all valid. */
    data object Valid : ValidationResult

    /** Fewer translations in the response than [ResponseChecker.validateResponse] expected. */
    data object TooFew : ValidationResult

    /** More translations in the response than [ResponseChecker.validateResponse] expected. */
    data object TooMany : ValidationResult

    /**
     * Model refused to translate.
     *
     * This is returned for empty/whitespace‑only responses or when the
     * response text contains known refusal phrases.
     */
    data object Refused : ValidationResult

    /**
     * Response contains an unexpected mix of CJK and Latin scripts,
     * indicating the model may have responded in the wrong language
     * or left portions of the source text untranslated.
     */
    data object WrongLanguage : ValidationResult
}
