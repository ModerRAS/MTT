package com.mtt.app.domain.glossary

import com.mtt.app.data.model.TranslationMode
import com.mtt.app.domain.pipeline.SkipPatterns
import kotlin.jvm.JvmName

/**
 * A glossary entry defining a source-to-target term mapping.
 *
 * Matching behavior is controlled by [isRegex] and [isCaseSensitive]:
 * - Default: case-sensitive literal matching
 * - [isRegex] = true: source is treated as a regex pattern
 * - [isCaseSensitive] = false: case-insensitive literal matching
 *
 * @param source          The source term to match in text
 * @param target          The replacement target term
 * @param isRegex         Whether [source] is a regex pattern (default: false)
 * @param isCaseSensitive Whether matching is case-sensitive (default: true)
 */
data class GlossaryEntry(
    val source: String,
    val target: String,
    val isRegex: Boolean = false,
    val isCaseSensitive: Boolean = false,
    val remark: String = ""
)

/**
 * Result of a glossary match operation.
 *
 * @param entry       The matching [GlossaryEntry]
 * @param matchedText The actual text that was matched (may differ from [entry.source]
 *                    for case-insensitive matches)
 * @param startIndex  Start index (inclusive) in the source text
 * @param endIndex    End index (exclusive) in the source text
 */
data class GlossaryMatch(
    val entry: GlossaryEntry,
    val matchedText: String,
    val startIndex: Int,
    val endIndex: Int
)

/**
 * Result of the [GlossaryEngine.protect] operation.
 *
 * @param protectedText Text with glossary source terms replaced by placeholders
 * @param placeholders  Map from placeholder (e.g. "{GLO_0}") to target term (e.g. "生命值")
 */
data class ProtectResult(
    val protectedText: String,
    val placeholders: Map<String, String>
)

/**
 * Core glossary engine for terminology matching and protection.
 *
 * Handles:
 * - Matching source text against glossary entries (literal, regex, case-insensitive)
 * - Prohibition list checking (禁翻表) — text that should not be translated
 * - Pre-processing: protecting glossary terms before LLM translation
 * - Post-processing: restoring glossary terms after LLM translation
 *
 * Placeholder format: `{GLO_0}`, `{GLO_1}`, ...
 *
 * Matches AiNiee's glossary protection strategy:
 * glossary terms are replaced with placeholders before sending to LLM,
 * then restored from the LLM response.
 */
object GlossaryEngine {

    private const val PLACEHOLDER_PREFIX = "{GLO_"
    private const val PLACEHOLDER_SUFFIX = "}"

    // ──────────────────────────────────────────────
    //  Matching
    // ──────────────────────────────────────────────

    /**
     * Finds all glossary term matches in [text].
     *
     * Overlapping matches are resolved by giving priority to longer matches.
     * Example: with entries "HP" and "HP Regen", the text "HP Regen is good"
     * matches only "HP Regen" (the shorter "HP" at position 0 is discarded).
     *
     * @param text     The source text to search
     * @param glossary List of glossary entries to match against
     * @return Non-overlapping matches sorted by position (ascending [startIndex])
     */
    fun match(text: String, glossary: List<GlossaryEntry>): List<GlossaryMatch> {
        if (text.isEmpty() || glossary.isEmpty()) return emptyList()

        val allMatches = mutableListOf<GlossaryMatch>()

        for (entry in glossary) {
            try {
                val pattern = buildPattern(entry)
                val matchResults = pattern.findAll(text)
                for (mr in matchResults) {
                    val range = mr.range
                    if (range.isEmpty()) continue // skip zero-length matches (e.g. empty source)

                    allMatches.add(
                        GlossaryMatch(
                            entry = entry,
                            matchedText = mr.value,
                            startIndex = range.first,
                            endIndex = range.last + 1
                        )
                    )
                }
            } catch (_: Exception) {
                // Skip entries with invalid regex patterns
                continue
            }
        }

        if (allMatches.isEmpty()) return emptyList()

        // Sort by start index ascending, then by length descending (longer first)
        allMatches.sortWith(compareBy({ it.startIndex }, { -(it.endIndex - it.startIndex) }))

        // Remove overlapping matches — keep first (longest) match at each position
        val result = mutableListOf<GlossaryMatch>()
        var lastEnd = 0
        for (m in allMatches) {
            if (m.startIndex >= lastEnd) {
                result.add(m)
                lastEnd = m.endIndex
            }
        }

        return result
    }

    // ──────────────────────────────────────────────
    //  Context-aware filtering
    // ──────────────────────────────────────────────

    /**
     * Filters glossary entries to only those whose source term actually appears
     * in the given [texts]. This prevents bloating the LLM prompt with irrelevant
     * glossary entries, matching AiNiee's context-aware glossary approach.
     *
     * @param entries Full list of glossary entries
     * @param texts   Batch of source texts to check against
     * @return Only entries whose source term matches any of [texts]
     */
    fun filterByTexts(entries: List<GlossaryEntry>, texts: List<String>): List<GlossaryEntry> {
        if (entries.isEmpty() || texts.isEmpty()) return emptyList()
        val combined = texts.joinToString("\n")
        return entries.filter { entry ->
            try {
                val pattern = buildPattern(entry)
                pattern.containsMatchIn(combined)
            } catch (_: Exception) {
                false
            }
        }
    }

    // ──────────────────────────────────────────────
    //  Prohibition
    // ──────────────────────────────────────────────

    /**
     * Checks whether [text] contains any term from the prohibition list (禁翻表).
     *
     * Terms in the prohibition list should not be translated and must remain
     * in their original form. This performs literal substring matching.
     *
     * @param text       The text to check
     * @param prohibited List of prohibited terms (literal substrings)
     * @return true if any prohibited term is found as a substring in [text]
     */
    fun isProhibited(text: String, prohibited: List<String>): Boolean {
        return prohibited.any { text.contains(it) }
    }

    /**
     * Checks whether [text] contains any term from the prohibition list (禁翻表)
     * with regex support. Each entry's [GlossaryEntry.source] is matched using
     * [buildPattern] — supporting exact, regex, and case-insensitive prohibition rules.
     *
     * @param text      The text to check
     * @param entries   Prohibition entries (target is empty) with match type
     * @return true if any entry's pattern matches [text]
     */
    @JvmName("isProhibitedByEntries")
    fun isProhibited(text: String, entries: List<GlossaryEntry>): Boolean {
        for (entry in entries) {
            try {
                val pattern = buildPattern(entry)
                if (pattern.containsMatchIn(text)) return true
            } catch (_: Exception) {
                continue
            }
        }
        return false
    }

    // ──────────────────────────────────────────────
    //  Protect / Restore
    // ──────────────────────────────────────────────

    /**
     * Protects glossary source terms in [text] by replacing them with numbered
     * placeholders before sending to an LLM. This prevents the LLM from
     * mistranslating known glossary terms.
     *
     * Example:
     * ```
     * text = "HP is a key stat"
     * glossary = [GlossaryEntry("HP", "生命值")]
     * → ProtectResult("{GLO_0} is a key stat", {"{GLO_0}": "生命值"})
     * ```
     *
     * **Important**: Prohibition entries (empty target) are NOT protected here.
     * They are handled via [buildProhibitionSection] and DoNotTranslate instructions
     * in the system prompt, which tell the LLM to preserve them as-is.
     *
     * @param text     Source text to protect
     * @param glossary Glossary entries whose source terms should be protected
     * @return Protected text with placeholders and the placeholder-to-target mapping
     */
    fun protect(text: String, glossary: List<GlossaryEntry>): ProtectResult {
        val matches = match(text, glossary.filter { it.target.isNotEmpty() })
        if (matches.isEmpty()) {
            return ProtectResult(text, emptyMap())
        }

        val placeholders = linkedMapOf<String, String>()
        val sb = StringBuilder(text.length + matches.size * 8)
        var lastIndex = 0

        for ((i, match) in matches.withIndex()) {
            val placeholder = "$PLACEHOLDER_PREFIX$i$PLACEHOLDER_SUFFIX"
            placeholders[placeholder] = match.entry.target

            sb.append(text, lastIndex, match.startIndex)
            sb.append(placeholder)
            lastIndex = match.endIndex
        }

        sb.append(text, lastIndex, text.length)

        return ProtectResult(sb.toString(), placeholders)
    }

    /**
     * Restores glossary placeholders in LLM-translated text back to their target terms.
     * This is the post-processing step after receiving translation from the LLM.
     *
     * Example:
     * ```
     * text = "{GLO_0} 是一个关键属性"
     * placeholders = {"{GLO_0}": "生命值"}
     * → "生命值 是一个关键属性"
     * ```
     *
     * @param text         Text containing placeholders (typically LLM output)
     * @param placeholders Map from placeholder to target term (obtained from [protect])
     * @return Text with all placeholders replaced by their target terms
     */
    fun restore(text: String, placeholders: Map<String, String>): String {
        if (placeholders.isEmpty()) return text

        var result = text
        for ((placeholder, target) in placeholders) {
            result = result.replace(placeholder, target)
        }
        return result
    }

    /**
     * Checks whether every glossary term present in [source] has its target term
     * present in [translated]. Prohibition entries (empty target) are ignored
     * because they must remain in their original form rather than become a target.
     */
    fun verifyApplied(source: String, translated: String, glossary: List<GlossaryEntry>): Boolean {
        val matches = match(source, glossary.filter { it.target.isNotEmpty() })
        if (matches.isEmpty()) return true
        return matches.all { match ->
            translated.contains(match.entry.target)
        }
    }

    // ──────────────────────────────────────────────
    //  Prompt formatting (AiNiee-compatible)
    // ──────────────────────────────────────────────

    /**
     * Builds the glossary prompt section (###术语表) for inclusion
     * in the LLM system prompt.
     *
     * Format:
     * ```
     * ###术语表
     * 原文|译文|备注
     * source|target|
     * ```
     *
     * @param entries Glossary entries to include
     * @param mode    Active translation mode (PROOFREAD returns "")
     * @return Formatted glossary section, or "" if entries is empty
     */
    fun buildGlossarySection(entries: List<GlossaryEntry>, mode: TranslationMode): String {
        // Skip entries that don't need translation (pure numbers, EV codes, etc.)
        val filtered = entries.filter { !SkipPatterns.shouldSkip(it.source) }
        if (filtered.isEmpty()) return ""

        val sb = StringBuilder()
        sb.appendLine()
        sb.appendLine("###术语表")
        sb.appendLine("原文|译文|备注")
        for (entry in filtered) {
            val remark = entry.remark.takeIf { it.isNotBlank() } ?: " "
            sb.appendLine("${entry.source}|${entry.target}|$remark")
        }
        return sb.toString()
    }

    /**
     * Builds the DoNotTranslate prohibition section for inclusion
     * in the LLM system prompt.
     *
     * Format:
     * ```
     * ###DoNotTranslate
     * term|description
     * ```
     *
     * Used only in TRANSLATE mode to indicate terms that must NOT
     * be translated.
     *
     * @param entries Glossary entries treated as prohibition terms
     * @return Formatted prohibition section, or "" if entries is empty
     */
    fun buildProhibitionSection(entries: List<GlossaryEntry>): String {
        // Skip entries that don't need translation (pure numbers, EV codes, etc.)
        val filtered = entries.filter { !SkipPatterns.shouldSkip(it.source) }
        if (filtered.isEmpty()) return ""

        val sb = StringBuilder()
        sb.appendLine()
        sb.appendLine("###DoNotTranslate")
        for (entry in filtered) {
            sb.appendLine("${entry.source}|${entry.target}")
        }
        return sb.toString()
    }

    // ──────────────────────────────────────────────
    //  Private helpers
    // ──────────────────────────────────────────────

    /**
     * Builds a [Regex] from a [GlossaryEntry] based on its match settings.
     */
    private fun buildPattern(entry: GlossaryEntry): Regex {
        return when {
            entry.isRegex -> Regex(entry.source)
            entry.isCaseSensitive -> Regex(Regex.escape(entry.source))
            else -> Regex(Regex.escape(entry.source), RegexOption.IGNORE_CASE)
        }
    }
}
