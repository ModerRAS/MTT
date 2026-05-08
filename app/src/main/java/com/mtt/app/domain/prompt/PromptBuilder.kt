package com.mtt.app.domain.prompt

import com.mtt.app.data.model.TranslationMode

/**
 * Contains the system prompt and user message for a translation request.
 */
data class PromptResult(
    val systemPrompt: String,
    val userMessage: String
)

/**
 * Builds LLM prompts matching AiNiee's format.
 *
 * All modes use `<textarea>` tags for both input and output,
 * with numbered items prefixed by "N. " (1-based index).
 */
object PromptBuilder {

    // ──────────────────────────────────────────────
    //  Convenience methods per TranslationMode
    // ──────────────────────────────────────────────

    fun buildTranslatePrompt(
        items: List<Pair<Int, String>>,
        sourceLang: String,
        targetLang: String,
        glossary: String,
        prohibition: String,
        previous: String
    ): PromptResult {
        val systemPrompt = buildSystemPrompt(TranslationMode.TRANSLATE, sourceLang, targetLang, glossary, prohibition)
        val userMessage = buildUserMessage(items, "这是你接下来的翻译任务", previous)
        return PromptResult(systemPrompt, userMessage)
    }

    fun buildPolishPrompt(
        items: List<Pair<Int, String>>,
        sourceLang: String,
        targetLang: String,
        glossary: String,
        previous: String
    ): PromptResult {
        val systemPrompt = buildSystemPrompt(TranslationMode.POLISH, sourceLang, targetLang, glossary, "")
        val userMessage = buildUserMessage(items, "这是你接下来的润色任务", previous)
        return PromptResult(systemPrompt, userMessage)
    }

    fun buildProofreadPrompt(
        items: List<Pair<Int, String>>,
        sourceLang: String,
        targetLang: String,
        glossary: String
    ): PromptResult {
        val systemPrompt = buildSystemPrompt(TranslationMode.PROOFREAD, sourceLang, targetLang, glossary, "")
        val userMessage = buildUserMessage(items, "这是你接下来的校对任务", "")
        return PromptResult(systemPrompt, userMessage)
    }

    // ──────────────────────────────────────────────
    //  Core builders
    // ──────────────────────────────────────────────

    /**
     * Builds the system prompt for the given [mode].
     *
     * @param glossary   Pre-formatted glossary text (appended as-is when non-blank).
     * @param prohibition Pre-formatted DoNotTranslate table (appended as-is when non-blank).
     */
    fun buildSystemPrompt(
        mode: TranslationMode,
        sourceLang: String,
        targetLang: String,
        glossary: String,
        prohibition: String
    ): String {
        return when (mode) {
            TranslationMode.TRANSLATE -> buildTranslateSystem(sourceLang, targetLang, glossary, prohibition)
            TranslationMode.POLISH -> buildPolishSystem(sourceLang, targetLang, glossary)
            TranslationMode.PROOFREAD -> buildProofreadSystem(sourceLang, targetLang, glossary)
        }
    }

    /**
     * Builds the user message containing the numbered items
     * wrapped in `<textarea>` tags, with optional [previousContext].
     *
     * @param taskDescription Describes the task (e.g. "这是你接下来的翻译任务").
     */
    fun buildUserMessage(
        items: List<Pair<Int, String>>,
        taskDescription: String,
        previousContext: String
    ): String {
        val sb = StringBuilder()

        // Previous context (for consistency across batches)
        if (previousContext.isNotBlank()) {
            sb.appendLine("### Previous text")
            sb.appendLine("<previous>")
            sb.append(previousContext.trim())
            if (!previousContext.endsWith("\n")) sb.append("\n")
            sb.appendLine("</previous>")
            sb.appendLine()
        }

        // Task header
        sb.appendLine("### $taskDescription")
        sb.appendLine("<textarea>")

        // Numbered items
        for ((index, text) in items) {
            sb.append("$index. $text")
            if (!text.endsWith("\n")) sb.append("\n")
        }

        sb.append("</textarea>")
        return sb.toString()
    }

    // ──────────────────────────────────────────────
    //  Mode-specific system prompts
    // ──────────────────────────────────────────────

    private fun buildTranslateSystem(
        sourceLang: String,
        targetLang: String,
        glossary: String,
        prohibition: String
    ): String {
        val sb = StringBuilder()
        sb.appendLine("你是专业翻译，专注将${sourceLang}文本翻译为${targetLang}。")
        sb.appendLine("严格遵循以下规则：")
        sb.appendLine("1. 调用 output_translations 工具输出翻译结果")
        sb.appendLine("2. 每个条目必须包含原文(source)和译文(translated)")
        sb.appendLine("3. 使用Glossary中的术语翻译，保持一致性")
        sb.appendLine("4. 禁止翻译DoNotTranslate列表中的词汇，保持原文")
        sb.appendLine("5. 保持原文的语气、风格和格式")
        sb.appendLine("6. 原文中的换行符(\\n)、回车符(\\r)、制表符(\\t)等控制字符是内容的一部分，必须在译文中保留相同数量")
        sb.appendLine("7. 一次性输出全部条目的翻译，不要遗漏")

        appendOptionalPromptSection(sb, glossary)
        appendOptionalPromptSection(sb, prohibition)

        sb.appendLine()
        sb.appendLine("在 output_translations 工具中输出结果")
        return sb.toString()
    }

    private fun buildPolishSystem(
        sourceLang: String,
        targetLang: String,
        glossary: String
    ): String {
        val sb = StringBuilder()
        sb.appendLine("你是文本润色专家，专注将${sourceLang}到${targetLang}的译文优化得更加自然、流畅、地道。")
        sb.appendLine("严格遵循以下规则：")
        sb.appendLine("1. 只润色<textarea>中的内容，不要添加任何解释")
        sb.appendLine("2. 严格按照序号输出，每行一个润色结果")
        sb.appendLine("3. 保持原文的核心意思和语气不变")
        sb.appendLine("4. 使用Glossary中的术语，保持一致性")
        sb.appendLine("5. 优化表达方式，使其更符合${targetLang}的语言习惯")

        appendOptionalPromptSection(sb, glossary)

        sb.appendLine()
        sb.appendLine("###以textarea标签输出润色文本")
        sb.appendLine("<textarea>")
        sb.appendLine("1.润色文本")
        sb.append("</textarea>")
        return sb.toString()
    }

    private fun buildProofreadSystem(
        sourceLang: String,
        targetLang: String,
        glossary: String
    ): String {
        val sb = StringBuilder()
        sb.appendLine("你是校对专家，专注检查${sourceLang}原文与${targetLang}译文的对应关系。")
        sb.appendLine("严格遵循以下规则：")
        sb.appendLine("1. 只校对<textarea>中的内容，不要添加任何解释")
        sb.appendLine("2. 严格按照序号输出，每行一个校对结果")
        sb.appendLine("3. 对比原文和译文，发现错误时用修正后的译文替换")
        sb.appendLine("4. 翻译正确则保持原译文不变")
        sb.appendLine("5. 确保译文准确传达原文含义，无遗漏、无增译")

        appendOptionalPromptSection(sb, glossary)

        sb.appendLine()
        sb.appendLine("###以textarea标签输出校对文本")
        sb.appendLine("<textarea>")
        sb.appendLine("1.校对后译文")
        sb.append("</textarea>")
        return sb.toString()
    }

    // ──────────────────────────────────────────────
    //  Helpers
    // ──────────────────────────────────────────────

    private fun appendOptionalPromptSection(sb: StringBuilder, section: String) {
        if (section.isNotBlank()) {
            sb.appendLine()
            sb.append(section.trim())
        }
    }
}
