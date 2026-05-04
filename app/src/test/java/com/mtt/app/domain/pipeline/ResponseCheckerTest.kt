package com.mtt.app.domain.pipeline

import org.junit.Test
import org.junit.Assert.assertEquals

class ResponseCheckerTest {

    // ── Helper ────────────────────────────────────

    /** Shorthand to keep tests concise. */
    private fun check(
        response: String,
        expectedCount: Int
    ): ValidationResult = ResponseChecker.validateResponse(response, expectedCount)

    // ═══════════════════════════════════════════════
    //  Valid
    // ═══════════════════════════════════════════════

    @Test
    fun `correct count with dot separator and Latin content`() {
        val response = "<textarea>\n1. Hello\n2. World\n3. Good morning\n</textarea>"
        assertEquals(ValidationResult.Valid, check(response, expectedCount = 3))
    }

    @Test
    fun `correct count with dot separator and CJK content`() {
        val response = "<textarea>\n1. こんにちは\n2. 世界\n3. おはよう\n</textarea>"
        assertEquals(ValidationResult.Valid, check(response, expectedCount = 3))
    }

    @Test
    fun `correct count with Chinese comma separator`() {
        val response = "<textarea>\n1、译文一\n2、译文二\n3、译文三\n</textarea>"
        assertEquals(ValidationResult.Valid, check(response, expectedCount = 3))
    }

    @Test
    fun `single translation`() {
        val response = "<textarea>1. Only one line</textarea>"
        assertEquals(ValidationResult.Valid, check(response, expectedCount = 1))
    }

    @Test
    fun `bare numbered lines without textarea tags`() {
        val response = "1. First\n2. Second\n3. Third"
        assertEquals(ValidationResult.Valid, check(response, expectedCount = 3))
    }

    @Test
    fun `zero expected count with blank response`() {
        assertEquals(ValidationResult.Valid, check("", expectedCount = 0))
    }

    @Test
    fun `zero expected count with empty textarea`() {
        assertEquals(ValidationResult.Valid, check("<textarea></textarea>", expectedCount = 0))
    }

    @Test
    fun `two digit numbering`() {
        val response = "<textarea>\n10. line ten\n11. line eleven\n12. line twelve\n</textarea>"
        assertEquals(ValidationResult.Valid, check(response, expectedCount = 3))
    }

    @Test
    fun `lines with leading whitespace`() {
        val response = "<textarea>\n  1. Hello\n  2. World\n</textarea>"
        assertEquals(ValidationResult.Valid, check(response, expectedCount = 2))
    }

    @Test
    fun `ignores content outside textarea tags`() {
        val response = "Preamble text\n<textarea>\n1. A\n2. B\n</textarea>\nPostamble text\n3. C"
        assertEquals(ValidationResult.Valid, check(response, expectedCount = 2))
    }

    @Test
    fun `ignores unnumbered lines inside textarea`() {
        val response = "<textarea>\nHeader line\n1. First\n2. Second\nFooter\n</textarea>"
        assertEquals(ValidationResult.Valid, check(response, expectedCount = 2))
    }

    // ═══════════════════════════════════════════════
    //  TooFew
    // ═══════════════════════════════════════════════

    @Test
    fun `fewer numbered lines than expected`() {
        val response = "<textarea>\n1. Only one\n</textarea>"
        assertEquals(ValidationResult.TooFew, check(response, expectedCount = 3))
    }

    @Test
    fun `empty textarea but expected count greater than zero`() {
        val response = "<textarea></textarea>"
        assertEquals(ValidationResult.TooFew, check(response, expectedCount = 3))
    }

    @Test
    fun `empty textarea with whitespace but expected count greater than zero`() {
        val response = "<textarea> \n \n </textarea>"
        assertEquals(ValidationResult.TooFew, check(response, expectedCount = 1))
    }

    @Test
    fun `blank lines between numbered lines reduce effective count`() {
        val response = "<textarea>\n1. A\n\n\n2. B\n</textarea>"
        // Only 2 numbered lines, expected 5
        assertEquals(ValidationResult.TooFew, check(response, expectedCount = 5))
    }

    @Test
    fun `zero numbered lines but expected count greater than zero`() {
        val response = "<textarea>\nJust some text\nNo numbers here\n</textarea>"
        assertEquals(ValidationResult.TooFew, check(response, expectedCount = 3))
    }

    @Test
    fun `numbered line missing content after separator not counted`() {
        val response = "<textarea>\n1.\n2. text\n</textarea>"
        // Only "2. text" is valid; "1." has no content after separator
        assertEquals(ValidationResult.TooFew, check(response, expectedCount = 2))
    }

    // ═══════════════════════════════════════════════
    //  TooMany
    // ═══════════════════════════════════════════════

    @Test
    fun `more numbered lines than expected`() {
        val response = "<textarea>\n1. A\n2. B\n3. C\n4. D\n5. E\n</textarea>"
        assertEquals(ValidationResult.TooMany, check(response, expectedCount = 2))
    }

    @Test
    fun `expected zero but response has numbered content`() {
        val response = "<textarea>\n1. Unexpected translation\n</textarea>"
        assertEquals(ValidationResult.TooMany, check(response, expectedCount = 0))
    }

    @Test
    fun `bare numbered lines exceeding expected count`() {
        val response = "1. One\n2. Two\n3. Three"
        assertEquals(ValidationResult.TooMany, check(response, expectedCount = 1))
    }

    // ═══════════════════════════════════════════════
    //  Refused
    // ═══════════════════════════════════════════════

    @Test
    fun `empty string is refused`() {
        assertEquals(ValidationResult.Refused, check("", expectedCount = 3))
    }

    @Test
    fun `whitespace only is refused`() {
        assertEquals(ValidationResult.Refused, check("   \n  \t  \n   ", expectedCount = 3))
    }

    @Test
    fun `I cannot translate refusal`() {
        assertEquals(
            ValidationResult.Refused,
            check("I cannot translate this content.", expectedCount = 3)
        )
    }

    @Test
    fun `I can't translate refusal`() {
        assertEquals(
            ValidationResult.Refused,
            check("I can't translate that, sorry.", expectedCount = 3)
        )
    }

    @Test
    fun `sorry refusal`() {
        assertEquals(
            ValidationResult.Refused,
            check("Sorry, I'm unable to help with this request.", expectedCount = 3)
        )
    }

    @Test
    fun `I apologize refusal`() {
        assertEquals(
            ValidationResult.Refused,
            check("I apologize, but I cannot provide that translation.", expectedCount = 3)
        )
    }

    @Test
    fun `as an AI language model refusal`() {
        assertEquals(
            ValidationResult.Refused,
            check("As an AI language model, I am not able to translate this content.", expectedCount = 3)
        )
    }

    @Test
    fun `cannot comply refusal`() {
        assertEquals(
            ValidationResult.Refused,
            check("I cannot comply with this request.", expectedCount = 3)
        )
    }

    @Test
    fun `against my guidelines refusal`() {
        assertEquals(
            ValidationResult.Refused,
            check("This goes against my guidelines.", expectedCount = 3)
        )
    }

    @Test
    fun `case insensitive refusal detection`() {
        assertEquals(
            ValidationResult.Refused,
            check("I CANNOT TRANSLATE THIS TEXT.", expectedCount = 3)
        )
    }

    @Test
    fun `refusal phrase embedded in surrounding text`() {
        assertEquals(
            ValidationResult.Refused,
            check(
                "Thank you for your request. Unfortunately, I'm sorry, but I cannot translate " +
                "that particular content. Please try something else.",
                expectedCount = 3
            )
        )
    }

    @Test
    fun `refusal takes priority over count mismatch`() {
        // Contains refusal phrase AND no numbered lines — refusal wins
        val response = "I'm sorry, I cannot translate this."
        assertEquals(ValidationResult.Refused, check(response, expectedCount = 3))
    }

    // ═══════════════════════════════════════════════
    //  WrongLanguage
    // ═══════════════════════════════════════════════

    @Test
    fun `equal mix of CJK and Latin characters`() {
        // Roughly 50% English, 50% Chinese
        val response = "<textarea>\n1. Hello 你好 World 世界\n2. Good 早上 Morning 好\n</textarea>"
        assertEquals(ValidationResult.WrongLanguage, check(response, expectedCount = 2))
    }

    @Test
    fun `first character CJK last character Latin signals mismatch`() {
        val response = "<textarea>\n1. こんにちは Hello World\n</textarea>"
        assertEquals(ValidationResult.WrongLanguage, check(response, expectedCount = 1))
    }

    @Test
    fun `first character Latin last character CJK signals mismatch`() {
        val response = "<textarea>\n1. Hello World こんにちは\n</textarea>"
        assertEquals(ValidationResult.WrongLanguage, check(response, expectedCount = 1))
    }

    @Test
    fun `CJK response with embedded English commentary`() {
        val response = "<textarea>\n" +
            "1. これは日本語の翻訳です\n" +
            "2. Here is some English note そして日本語\n" +
            "3. 三番目の行です\n" +
            "</textarea>"
        assertEquals(ValidationResult.WrongLanguage, check(response, expectedCount = 3))
    }

    @Test
    fun `predominantly CJK response is NOT wrong language`() {
        val response = "<textarea>\n1. こんにちは世界\n2. お元気ですか\n3. さようなら\n</textarea>"
        assertEquals(ValidationResult.Valid, check(response, expectedCount = 3))
    }

    @Test
    fun `predominantly Latin response is NOT wrong language`() {
        val response = "<textarea>\n1. Hello world\n2. How are you\n3. Goodbye\n</textarea>"
        assertEquals(ValidationResult.Valid, check(response, expectedCount = 3))
    }

    // ═══════════════════════════════════════════════
    //  Edge cases
    // ═══════════════════════════════════════════════

    @Test
    fun `response with only digits and punctuation does not trigger language check`() {
        val response = "<textarea>\n1. 12345\n2. 67890\n</textarea>"
        assertEquals(ValidationResult.Valid, check(response, expectedCount = 2))
    }

    @Test
    fun `response with too few letter characters skips language check`() {
        // Less than 10 letter characters total → language check returns false
        val response = "<textarea>\n1. a\n2. b\n3. c\n</textarea>"
        assertEquals(ValidationResult.Valid, check(response, expectedCount = 3))
    }

    @Test
    fun `multiple dots in a line does not break numbering detection`() {
        // Line "1. Hello. World." should still count as one numbered line
        val response = "<textarea>\n1. Hello. World.\n2. Foo. Bar.\n</textarea>"
        assertEquals(ValidationResult.Valid, check(response, expectedCount = 2))
    }

    @Test
    fun `numbered line with only a dot and no content is not counted`() {
        val response = "<textarea>\n1.\n2.\n</textarea>"
        assertEquals(ValidationResult.TooFew, check(response, expectedCount = 3))
    }

    @Test
    fun `number without separator is not counted`() {
        val response = "<textarea>\n1 Hello\n2 World\n</textarea>"
        // No dot or 、 after the number → not counted as numbered lines
        assertEquals(ValidationResult.TooFew, check(response, expectedCount = 3))
    }

    @Test
    fun `content outside textarea does not affect count`() {
        val response = "1. Ignored\n<textarea>\n1. Real\n2. Real\n</textarea>\n3. Ignored\n4. Ignored"
        assertEquals(ValidationResult.Valid, check(response, expectedCount = 2))
    }

    @Test
    fun `mixed separator formats counted correctly`() {
        val response = "<textarea>\n1. dot sep\n2、comma sep\n3. dot again\n</textarea>"
        assertEquals(ValidationResult.Valid, check(response, expectedCount = 3))
    }

    @Test
    fun `long response does not hang`() {
        // Build a response with 1000 numbered lines
        val sb = StringBuilder("<textarea>\n")
        for (i in 1..1000) {
            sb.append("$i. Translation line number $i\n")
        }
        sb.append("</textarea>")
        val response = sb.toString()

        // Should complete quickly (no regex catastrophic backtracking)
        val result = check(response, expectedCount = 1000)
        assertEquals(ValidationResult.Valid, result)
    }

    @Test
    fun `very long single line does not hang`() {
        val longText = "A".repeat(100_000)
        val response = "<textarea>\n1. $longText\n</textarea>"

        // Should complete without hanging on long input
        val result = check(response, expectedCount = 1)
        assertEquals(ValidationResult.Valid, result)
    }

    @Test
    fun `long refusal text does not hang`() {
        val longText = "A".repeat(50_000)
        val response = "I'm sorry, I cannot translate this: $longText"

        // Should complete quickly (simple substring check, no regex)
        val result = check(response, expectedCount = 3)
        assertEquals(ValidationResult.Refused, result)
    }
}
