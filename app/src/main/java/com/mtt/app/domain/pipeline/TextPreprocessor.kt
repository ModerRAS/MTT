package com.mtt.app.domain.pipeline

/**
 * Result of text preprocessing. Contains split segments
 * and metadata required for faithful post-processing restoration.
 */
sealed class PreprocessedText {
    /**
     * Text successfully split into segments ready for LLM translation.
     */
    data class Segments(
        val segments: List<Segment>,
        val metadata: PreprocessingMetadata
    ) : PreprocessedText()
}

/**
 * A single translatable segment extracted from the original text.
 *
 * @param index   1-based position (matches PrompelBuilder numbering).
 * @param text    Content with {X-…} tags replaced by placeholders and special chars escaped.
 * @param prefix  Whitespace-originated prefix that stays outside the translatable span.
 * @param suffix  Whitespace-originated suffix that stays outside the translatable span.
 * @param isEmpty true if this segment represents a blank line (no text to translate).
 */
data class Segment(
    val index: Int,
    val text: String,
    val prefix: String = "",
    val suffix: String = "",
    val isEmpty: Boolean = false
)

/**
 * Metadata needed by [TextPreprocessor.postprocess] to rebuild the original
 * structure after translation.
 *
 * @param tagPlaceholders  Mapping from safe placeholder key → original {X-…} tag.
 * @param lineEndings      Original line endings recorded per line break position.
 * @param hasTags          true when the text contained at least one {X-…} tag.
 */
data class PreprocessingMetadata(
    val tagPlaceholders: Map<String, String> = emptyMap(),
    val lineEndings: List<LineEnding> = emptyList(),
    val hasTags: Boolean = false
)

/**
 * Records the original line ending at a given position so it can
 * be faithfully restored in post-processing.
 *
 * @param position 0-based index of the line that this ending follows.
 * @param original The raw ending string (e.g. "\r\n", "\n", "\r", "<br>").
 */
data class LineEnding(
    val position: Int,
    val original: String
)

// ─────────────────────────────────────────────────────────
//  TextPreprocessor
// ─────────────────────────────────────────────────────────

/**
 * Splits raw text into LLM-translatable segments while preserving
 * AiNiee-style {X-…} tags, line endings, and structural whitespace.
 *
 * Usage:
 * ```kotlin
 * val result = TextPreprocessor.preprocess(text)
 * // … send result.segments to LLM, receive translated strings …
 * val final = TextPreprocessor.postprocess(translated, result.metadata)
 * ```
 */
object TextPreprocessor {

    // ── Tag handling ───────────────────────────────────

    /** Matches AiNiee MTool tags: `{X-NAME}`, `{X-NAME-VALUE}`, `{X-ROLE}` etc. */
    private val TAG_PATTERN = Regex("""\{X-[A-Z0-9_]+(?:-[A-Z0-9_]+)?\}""")

    /** Prefix for generated placeholders, e.g. `[T0]`, `[T1]`. */
    private const val TAG_PLACEHOLDER_PREFIX = "[T"

    // ── Line ending handling ───────────────────────────

    /** Matches any traditional or HTML-style line break. */
    private val LINE_BREAK_PATTERN = Regex("""\r\n|\r|\n|<br\s*/?>""")

    // ── LLM safety ─────────────────────────────────────

    /** Characters that may interfere with LLM prompt parsing — escaped with backslash. */
    private val LLM_ESCAPE_MAP = mapOf(
        '`' to "\\`",
        '#' to "\\#",
        '[' to "\\[",
        ']' to "\\]",
        '<' to "\\<",
        '>' to "\\>",
        '{' to "\\{",
        '}' to "\\}"
    )

    // ── Public API ─────────────────────────────────────

    /**
     * Splits [text] into segments suitable for LLM batch translation.
     *
     * Steps:
     * 1. Extract {X-…} tags → safe placeholders.
     * 2. Normalize line endings to `\n`, recording originals.
     * 3. Split into lines, recording prefix/suffix whitespace.
     * 4. Escape special characters for LLM safety.
     */
    fun preprocess(text: String): PreprocessedText.Segments {
        if (text.isEmpty()) {
            return PreprocessedText.Segments(
                segments = listOf(
                    Segment(index = 1, text = "", isEmpty = true)
                ),
                metadata = PreprocessingMetadata()
            )
        }

        // 1. Extract {X-…} tags
        val (cleanText, tagMap) = extractTags(text)

        // 2. Normalize line endings
        val (normalizedText, lineEndings) = normalizeLineEndings(cleanText)

        // 3. Split into lines and build segments
        val rawSegments = buildSegments(normalizedText)

        // 4. Escape LLM-sensitive characters in translatable text
        val escapedSegments = rawSegments.map { seg ->
            if (seg.isEmpty) seg else seg.copy(text = escapeForLlm(seg.text))
        }

        return PreprocessedText.Segments(
            segments = escapedSegments,
            metadata = PreprocessingMetadata(
                tagPlaceholders = tagMap,
                lineEndings = lineEndings,
                hasTags = tagMap.isNotEmpty()
            )
        )
    }

    /**
     * Rejoins translated segments back into a single string,
     * restoring original {X-…} tags and line endings.
     *
     * @param translatedSegments Translated text for each non-empty segment,
     *                           in the same order as the output segments from [preprocess].
     * @param metadata           The [PreprocessingMetadata] returned by [preprocess].
     */
    fun postprocess(
        translatedSegments: List<String>,
        metadata: PreprocessingMetadata
    ): String {
        // 1. Unescape LLM safety characters
        val unescaped = translatedSegments.map { unescapeForLlm(it) }

        // 2. Restore {X-…} tags
        val withTags = unescaped.map { restoreTags(it, metadata.tagPlaceholders) }

        // 3. Rejoin with original line endings
        return rejoinLines(withTags, metadata.lineEndings)
    }

    // ── Private: Tag extraction ────────────────────────

    /**
     * Replaces every `{X-…}` tag with a stable placeholder (e.g. `[T0]`)
     * and returns the cleaned text + reverse mapping.
     */
    private fun extractTags(text: String): Pair<String, Map<String, String>> {
        val tagMap = LinkedHashMap<String, String>() // preserves insertion order
        var counter = 0

        val cleanText = TAG_PATTERN.replace(text) { match ->
            val tag = match.value
            val key = "$TAG_PLACEHOLDER_PREFIX$counter]"
            tagMap[key] = tag
            counter++
            key
        }

        return cleanText to tagMap
    }

    /**
     * Reverses [extractTags]: replaces placeholders with their original tags.
     */
    private fun restoreTags(text: String, tagMap: Map<String, String>): String {
        var result = text
        for ((placeholder, tag) in tagMap) {
            result = result.replace(placeholder, tag)
        }
        return result
    }

    // ── Private: Line ending normalization ─────────────

    /**
     * Converts all line breaks to `\n` and records the original break
     * string for each position so it can be restored later.
     *
     * @return Pair of (normalized text, ordered list of original endings).
     */
    private fun normalizeLineEndings(text: String): Pair<String, List<LineEnding>> {
        val endings = mutableListOf<LineEnding>()
        val sb = StringBuilder()
        var normalizedPos = 0

        var i = 0
        while (i < text.length) {
            val remaining = text.substring(i)

            val match = LINE_BREAK_PATTERN.find(remaining)
            if (match != null && match.range.first == 0) {
                endings.add(LineEnding(position = normalizedPos, original = match.value))
                sb.append('\n')
                normalizedPos++
                i += match.value.length
            } else {
                sb.append(text[i])
                i++
            }
        }

        return sb.toString() to endings
    }

    /** Restores original line endings from the recorded list. */
    private fun rejoinLines(lines: List<String>, lineEndings: List<LineEnding>): String {
        if (lines.isEmpty()) return ""

        val sb = StringBuilder()

        for (i in lines.indices) {
            sb.append(lines[i])
            if (i < lines.size - 1) {
                val ending = lineEndings.getOrNull(i)?.original ?: "\n"
                sb.append(ending)
            }
        }

        return sb.toString()
    }

    // ── Private: Segment building ──────────────────────

    /**
     * Splits normalized text into lines, recording prefix/suffix whitespace
     * and marking empty lines.
     */
    private fun buildSegments(text: String): List<Segment> {
        val lines = text.split('\n')
        val segments = mutableListOf<Segment>()
        var index = 1

        for (line in lines) {
            if (line.isEmpty()) {
                segments.add(
                    Segment(index = index, text = "", isEmpty = true)
                )
            } else {
                val trimmed = line.trimStart()
                val prefixLength = line.length - line.trimStart().length
                val suffixLength = line.length - line.trimEnd().length

                val prefix = if (prefixLength > 0) line.substring(0, prefixLength) else ""
                val core = if (suffixLength > 0) {
                    line.substring(prefixLength, line.length - suffixLength)
                } else {
                    line.substring(prefixLength)
                }
                val suffix = if (suffixLength > 0) {
                    line.substring(line.length - suffixLength)
                } else ""

                segments.add(
                    Segment(
                        index = index,
                        text = core,
                        prefix = prefix,
                        suffix = suffix
                    )
                )
            }
            index++
        }

        return segments
    }

    // ── Private: LLM safety escaping ───────────────────

    /**
     * Escapes characters that could interfere with LLM prompt injection
     * or {X-…} tag parsing.
     */
    private fun escapeForLlm(text: String): String {
        val sb = StringBuilder(text.length + 8)
        for (ch in text) {
            val escaped = LLM_ESCAPE_MAP[ch]
            sb.append(escaped ?: ch)
        }
        return sb.toString()
    }

    /** Reverses [escapeForLlm]. */
    private fun unescapeForLlm(text: String): String {
        var result = text
        // Preserve double backslash by substituting a sentinel
        result = result.replace("\\\\", "\u0000")
        for ((target, escaped) in LLM_ESCAPE_MAP) {
            result = result.replace(escaped, target.toString())
        }
        result = result.replace("\u0000", "\\\\")
        return result
    }
}

