package com.mtt.app.data.llm

import com.mtt.app.data.model.TranslationMode
import com.mtt.app.domain.prompt.PromptBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for PromptBuilder.
 */
class PromptBuilderTest {

    // region buildTranslatePrompt tests

    @Test
    fun buildTranslatePrompt_withAllParams_returnsPromptResult() {
        val items = listOf(1 to "Hello", 2 to "World")
        val result = PromptBuilder.buildTranslatePrompt(
            items = items,
            sourceLang = "English",
            targetLang = "Chinese",
            glossary = "Hello = 你好",
            prohibition = "World",
            previous = "previous text"
        )

        assertTrue(result.systemPrompt.contains("English"))
        assertTrue(result.systemPrompt.contains("Chinese"))
        assertTrue(result.systemPrompt.contains("Glossary"))
        assertTrue(result.systemPrompt.contains("DoNotTranslate"))
        assertTrue(result.userMessage.contains("textarea"))
        assertTrue(result.userMessage.contains("previous"))
    }

    @Test
    fun buildTranslatePrompt_withBlankOptionalParams_stillGeneratesValidPrompt() {
        val items = listOf(1 to "Test")
        val result = PromptBuilder.buildTranslatePrompt(
            items = items,
            sourceLang = "EN",
            targetLang = "ZH",
            glossary = "",
            prohibition = "",
            previous = ""
        )

        assertTrue(result.systemPrompt.contains("严格遵循以下规则"))
        assertTrue(result.systemPrompt.contains("EN"))
        assertTrue(result.systemPrompt.contains("ZH"))
        assertFalse(result.systemPrompt.contains("Glossary:"))
        assertFalse(result.systemPrompt.contains("DoNotTranslate:"))
        assertFalse(result.userMessage.contains("Previous"))
        assertTrue(result.userMessage.contains("这是你接下来的翻译任务"))
        assertTrue(result.userMessage.contains("1. Test"))
    }

    // endregion

    // region buildPolishPrompt tests

    @Test
    fun buildPolishPrompt_returnsPromptResult() {
        val items = listOf(1 to "原始翻译", 2 to "另一个翻译")
        val result = PromptBuilder.buildPolishPrompt(
            items = items,
            sourceLang = "Chinese",
            targetLang = "English",
            glossary = "术语表内容",
            previous = "前文上下文"
        )

        assertTrue(result.systemPrompt.contains("润色"))
        assertTrue(result.systemPrompt.contains("Chinese"))
        assertTrue(result.systemPrompt.contains("English"))
        assertTrue(result.userMessage.contains("这是你接下来的润色任务"))
        assertTrue(result.userMessage.contains("1. 原始翻译"))
        assertTrue(result.userMessage.contains("2. 另一个翻译"))
    }

    @Test
    fun buildPolishPrompt_withEmptyGlossaryAndPrevious_succeeds() {
        val items = listOf(3 to "内容")
        val result = PromptBuilder.buildPolishPrompt(
            items = items,
            sourceLang = "A",
            targetLang = "B",
            glossary = "",
            previous = ""
        )

        assertFalse(result.systemPrompt.contains("Glossary:"))
        assertFalse(result.userMessage.contains("Previous"))
        assertTrue(result.userMessage.contains("这是你接下来的润色任务"))
        assertTrue(result.userMessage.contains("3. 内容"))
    }

    // endregion

    // region buildProofreadPrompt tests

    @Test
    fun buildProofreadPrompt_returnsPromptResult() {
        val items = listOf(1 to "原文", 2 to "译文")
        val result = PromptBuilder.buildProofreadPrompt(
            items = items,
            sourceLang = "Source",
            targetLang = "Target",
            glossary = "术语"
        )

        assertTrue(result.systemPrompt.contains("校对"))
        assertTrue(result.systemPrompt.contains("Source"))
        assertTrue(result.systemPrompt.contains("Target"))
        assertTrue(result.userMessage.contains("这是你接下来的校对任务"))
    }

    @Test
    fun buildProofreadPrompt_withEmptyGlossary_succeeds() {
        val items = listOf(1 to "item")
        val result = PromptBuilder.buildProofreadPrompt(
            items = items,
            sourceLang = "EN",
            targetLang = "ZH",
            glossary = ""
        )

        assertFalse(result.systemPrompt.contains("Glossary"))
        assertTrue(result.userMessage.contains("### 这是你接下来的校对任务"))
        assertTrue(result.userMessage.contains("<textarea>"))
        assertTrue(result.userMessage.contains("</textarea>"))
        assertTrue(result.userMessage.contains("1. item"))
    }

    // endregion

    // region buildSystemPrompt tests

    @Test
    fun buildSystemPrompt_translateMode_containsKeyRules() {
        val result = PromptBuilder.buildSystemPrompt(
            mode = TranslationMode.TRANSLATE,
            sourceLang = "源语言",
            targetLang = "目标语言",
            glossary = "",
            prohibition = ""
        )

        assertTrue(result.contains("翻译"))
        assertTrue(result.contains("textarea"))
        assertTrue(result.contains("序号"))
        assertTrue(result.contains("Glossary"))
        assertTrue(result.contains("DoNotTranslate"))
    }

    @Test
    fun buildSystemPrompt_polishMode_containsPolishRules() {
        val result = PromptBuilder.buildSystemPrompt(
            mode = TranslationMode.POLISH,
            sourceLang = "EN",
            targetLang = "ZH",
            glossary = "",
            prohibition = ""
        )

        assertTrue(result.contains("润色"))
        assertTrue(result.contains("自然"))
        assertTrue(result.contains("textarea"))
    }

    @Test
    fun buildSystemPrompt_proofreadMode_containsProofreadRules() {
        val result = PromptBuilder.buildSystemPrompt(
            mode = TranslationMode.PROOFREAD,
            sourceLang = "A",
            targetLang = "B",
            glossary = "",
            prohibition = ""
        )

        assertTrue(result.contains("校对"))
        assertTrue(result.contains("原文"))
        assertTrue(result.contains("译文"))
        assertTrue(result.contains("textarea"))
    }

    @Test
    fun buildSystemPrompt_withGlossary_appendsGlossarySection() {
        val glossaryText = "Glossary:\nterm = definition"
        val result = PromptBuilder.buildSystemPrompt(
            mode = TranslationMode.TRANSLATE,
            sourceLang = "A",
            targetLang = "B",
            glossary = glossaryText,
            prohibition = ""
        )

        assertTrue(result.contains(glossaryText))
    }

    @Test
    fun buildSystemPrompt_translateMode_withProhibition_appendsProhibitionSection() {
        val prohibitionText = "DoNotTranslate:\n特定词 = 原样"
        val result = PromptBuilder.buildSystemPrompt(
            mode = TranslationMode.TRANSLATE,
            sourceLang = "A",
            targetLang = "B",
            glossary = "",
            prohibition = prohibitionText
        )

        assertTrue(result.contains(prohibitionText))
        assertTrue(result.contains("DoNotTranslate"))
    }

    // endregion

    // region buildUserMessage tests

    @Test
    fun buildUserMessage_withItems_wrapsInTextareaWithNumberedLines() {
        val items = listOf(1 to "第一句", 2 to "第二句", 3 to "第三句")
        val result = PromptBuilder.buildUserMessage(
            items = items,
            taskDescription = "测试任务",
            previousContext = ""
        )

        assertTrue(result.contains("<textarea>"))
        assertTrue(result.contains("</textarea>"))
        assertTrue(result.contains("### 测试任务"))
        assertTrue(result.contains("1. 第一句"))
        assertTrue(result.contains("2. 第二句"))
        assertTrue(result.contains("3. 第三句"))
    }

    @Test
    fun buildUserMessage_withPreviousContext_appendsPreviousSection() {
        val items = listOf(1 to "当前内容")
        val result = PromptBuilder.buildUserMessage(
            items = items,
            taskDescription = "任务",
            previousContext = "前文内容\nmore previous"
        )

        assertTrue(result.contains("### Previous text"))
        assertTrue(result.contains("<previous>"))
        assertTrue(result.contains("前文内容"))
        assertTrue(result.contains("more previous"))
        assertTrue(result.contains("</previous>"))
    }

    @Test
    fun buildUserMessage_emptyItems_stillWrapsInTextareaTags() {
        val result = PromptBuilder.buildUserMessage(
            items = emptyList(),
            taskDescription = "空任务",
            previousContext = ""
        )

        assertTrue(result.contains("<textarea>"))
        assertTrue(result.contains("</textarea>"))
        assertFalse(result.contains("### Previous"))
    }

    // endregion
}
