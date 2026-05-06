package com.mtt.app.data.llm

import kotlin.math.min

/**
 * Result of frequency analysis: a candidate term and how many times it appeared.
 */
data class FrequencyCandidate(
    val term: String,
    val frequency: Int
)

/**
 * Analyzes source texts to find candidate glossary terms based on frequency.
 *
 * Strategy (two-level extraction):
 * 1. Extract contiguous character sequences (split on non-letter/digit chars).
 *    For CJK text this already splits into likely word-like chunks.
 * 2. For long sequences (>= 4 chars), also extract overlapping bigrams and
 *    trigrams to catch terms embedded within longer compounds.
 *    Example: "最大生命値" → "最大", "大生", "生命", "命値", "最大生", "大生命", "生命値"
 * 3. Count frequency of ALL extracted substrings across all texts.
 * 4. Filter by minimum frequency and length.
 *
 * This replaces the need for a full CJK tokenizer (like Kuromoji) while still
 * catching compound terms embedded within longer words. The frequency filter
 * naturally eliminates noise from random overlapping n-grams.
 *
 * The output is a list of candidate terms sent to the LLM for validation/translation.
 * This is MUCH faster than scanning 42K entries chunk-by-chunk via API calls.
 *
 * Examples of what gets extracted:
 *   "Hello World"          → ["Hello", "World"]
 *   "HPを回復する"         → ["HP", "回復"]
 *   "EV001_script"        → ["EV001", "script"]
 *   "最大生命値を回復する" → ["最大", "大生", "生命", "命値", "最大生", "大生命",
 *                               "生命値", "最大生命", "大生命値", "最大生命値"]
 *   "OpenAI: DeepSeek V4 Flash" → ["OpenAI", "DeepSeek", "Flash"]
 */
object FrequencyAnalyzer {

    /**
     * Extract candidate terms from source texts based on frequency analysis.
     *
     * @param texts         All source text values to scan
     * @param minFrequency  Minimum number of occurrences to qualify as candidate
     * @param minLength     Minimum character length for a candidate
     * @param maxLength     Maximum character length for a candidate
     * @return List of candidates sorted by frequency (most frequent first)
     */
    fun extractCandidates(
        texts: Collection<String>,
        minFrequency: Int = DEFAULT_MIN_FREQUENCY,
        minLength: Int = DEFAULT_MIN_LENGTH,
        maxLength: Int = DEFAULT_MAX_LENGTH
    ): List<FrequencyCandidate> {
        if (texts.isEmpty()) return emptyList()

        val freqMap = mutableMapOf<String, Int>()

        for (text in texts) {
            if (text.isBlank()) continue
            val sequences = extractWordSequences(text)
            for (seq in sequences) {
                // Level 1: add the full sequence
                if (seq.length in minLength..maxLength) {
                    freqMap[seq] = freqMap.getOrDefault(seq, 0) + 1
                }
                // Level 2: for long sequences, also add overlapping n-grams
                // (catches terms embedded within longer compounds)
                if (seq.length >= NGRAM_THRESHOLD) {
                    addOverlappingNgrams(seq, freqMap, minLength, maxLength)
                }
            }
        }

        return freqMap
            .filter { it.value >= minFrequency }
            .map { FrequencyCandidate(it.key, it.value) }
            .sortedByDescending { it.frequency }
    }

    /**
     * Add overlapping bigrams and trigrams from [seq] to [freqMap].
     *
     * Example with "最大生命値" (length 5):
     *   bigrams  (2-gram): "最大", "大生", "生命", "命値"
     *   trigrams (3-gram): "最大生", "大生命", "生命値"
     */
    private fun addOverlappingNgrams(
        seq: String,
        freqMap: MutableMap<String, Int>,
        minLength: Int,
        maxLength: Int
    ) {
        for (gramSize in 2..min(3, maxLength)) {
            if (gramSize > seq.length) break
            val maxStart = seq.length - gramSize
            for (start in 0..maxStart) {
                val sub = seq.substring(start, start + gramSize)
                if (sub.length in minLength..maxLength) {
                    freqMap[sub] = freqMap.getOrDefault(sub, 0) + 1
                }
            }
        }
    }

    /**
     * Extract contiguous word-like sequences from text.
     *
     * Splits on non-letter, non-digit characters (whitespace, punctuation, symbols).
     * Single-character tokens (common CJK particles like を, が, は, の) are discarded.
     */
    private fun extractWordSequences(text: String): List<String> {
        val sequences = mutableListOf<String>()
        val current = StringBuilder()

        for (char in text) {
            if (char.isLetterOrDigit() || char == '_') {
                current.append(char)
            } else {
                if (current.isNotEmpty() && current.length >= 2) {
                    sequences.add(current.toString())
                }
                current.clear()
            }
        }
        if (current.length >= 2) {
            sequences.add(current.toString())
        }

        return sequences
    }

    /**
     * Limit candidates to a reasonable number for LLM processing.
     * Keeps top-N most frequent candidates.
     */
    fun limitCandidates(
        candidates: List<FrequencyCandidate>,
        maxCount: Int = DEFAULT_MAX_CANDIDATES
    ): List<FrequencyCandidate> {
        return candidates.take(maxCount)
    }

    private const val DEFAULT_MIN_FREQUENCY = 3
    private const val DEFAULT_MIN_LENGTH = 2
    private const val DEFAULT_MAX_LENGTH = 20
    private const val DEFAULT_MAX_CANDIDATES = 500
    private const val NGRAM_THRESHOLD = 4
}
