package com.mtt.app.domain.usecase

import com.mtt.app.data.model.ExtractedTerm
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [ExtractTermsUseCase] internal (pure) methods.
 *
 * Tests dedup, filtering, JSON extraction, and heuristic fallback logic
 * without LLM dependencies. The internal methods are tested on a real
 * instance constructed with mock dependencies.
 */
class ExtractTermsUseCaseTest {

    private val useCase = ExtractTermsUseCase(mockk(), mockk(), mockk())

    // ═══════════════════════════════════════════════════════════════
    //  deduplicateAndFilter tests
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `deduplicateAndFilter returns empty for empty input`() {
        val result = useCase.deduplicateAndFilter(emptyList())
        assertTrue(result.isEmpty())
    }

    @Test
    fun `deduplicateAndFilter deduplicates by lowercase source term`() {
        val items = listOf(
            ExtractedTerm("HP", "生命值", type = ExtractedTerm.TYPE_TERM),
            ExtractedTerm("hp", "Health", type = ExtractedTerm.TYPE_TERM)
        )
        val result = useCase.deduplicateAndFilter(items)
        assertEquals(1, result.size)
    }

    @Test
    fun `deduplicateAndFilter prefers term over non_translate`() {
        val items = listOf(
            ExtractedTerm("HP", "", type = ExtractedTerm.TYPE_NON_TRANSLATE, category = "标签"),
            ExtractedTerm("HP", "生命值", type = ExtractedTerm.TYPE_TERM)
        )
        val result = useCase.deduplicateAndFilter(items)
        assertEquals(1, result.size)
        assertEquals(ExtractedTerm.TYPE_TERM, result[0].type)
        assertEquals("生命值", result[0].suggestedTarget)
    }

    @Test
    fun `deduplicateAndFilter filters blank sources`() {
        val items = listOf(
            ExtractedTerm("", "test", type = ExtractedTerm.TYPE_TERM),
            ExtractedTerm("  ", "test2", type = ExtractedTerm.TYPE_TERM)
        )
        val result = useCase.deduplicateAndFilter(items)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `deduplicateAndFilter prefers entry with non-empty category`() {
        val items = listOf(
            ExtractedTerm("MP", "魔法", type = ExtractedTerm.TYPE_TERM, category = "", explanation = "mana"),
            ExtractedTerm("MP", "魔力", type = ExtractedTerm.TYPE_TERM, category = "技能", explanation = "magic power")
        )
        val result = useCase.deduplicateAndFilter(items)
        assertEquals(1, result.size)
        assertEquals("魔力", result[0].suggestedTarget)
        assertEquals("技能", result[0].category)
    }

    @Test
    fun `deduplicateAndFilter sorts by lowercase source`() {
        val items = listOf(
            ExtractedTerm("リンク", "林克", type = ExtractedTerm.TYPE_TERM),
            ExtractedTerm("ゼルダ", "塞尔达", type = ExtractedTerm.TYPE_TERM)
        )
        val result = useCase.deduplicateAndFilter(items)
        assertEquals(2, result.size)
        assertTrue(result[0].sourceTerm <= result[1].sourceTerm)
    }

    // ═══════════════════════════════════════════════════════════════
    //  isOnlyPunctuation tests
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `isOnlyPunctuation returns true for empty string`() {
        assertTrue(useCase.isOnlyPunctuation(""))
    }

    @Test
    fun `isOnlyPunctuation returns true for pure punctuation`() {
        assertTrue(useCase.isOnlyPunctuation("..."))
        assertTrue(useCase.isOnlyPunctuation("---"))
        assertTrue(useCase.isOnlyPunctuation("、。！？"))
    }

    @Test
    fun `isOnlyPunctuation returns false for alphanumeric`() {
        assertFalse(useCase.isOnlyPunctuation("HP"))
        assertFalse(useCase.isOnlyPunctuation("<br>"))
        assertFalse(useCase.isOnlyPunctuation("{{name}}"))
        assertFalse(useCase.isOnlyPunctuation("hello"))
        assertFalse(useCase.isOnlyPunctuation("{0}"))
        assertFalse(useCase.isOnlyPunctuation("%s"))
        assertFalse(useCase.isOnlyPunctuation("\$var"))
    }

    @Test
    fun `isOnlyPunctuation returns false for mixed content`() {
        assertFalse(useCase.isOnlyPunctuation("...HP"))
        assertFalse(useCase.isOnlyPunctuation("标签..."))
    }

    // ═══════════════════════════════════════════════════════════════
    //  mergeNonTranslateItems tests
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `mergeNonTranslateItems returns empty for no non_translate items`() {
        val items = listOf(
            ExtractedTerm("HP", "生命值", type = ExtractedTerm.TYPE_TERM),
            ExtractedTerm("リンク", "林克", type = ExtractedTerm.TYPE_CHARACTER)
        )
        val result = useCase.mergeNonTranslateItems(items)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `mergeNonTranslateItems deduplicates by sourceTerm`() {
        val items = listOf(
            ExtractedTerm("<br>", "", type = ExtractedTerm.TYPE_NON_TRANSLATE, category = "标签"),
            ExtractedTerm("<br>", "", type = ExtractedTerm.TYPE_NON_TRANSLATE, category = "标签", explanation = "换行符")
        )
        val result = useCase.mergeNonTranslateItems(items)
        assertEquals(1, result.size)
        assertEquals("<br>", result[0].sourceTerm)
    }

    @Test
    fun `mergeNonTranslateItems prefers item with category`() {
        val items = listOf(
            ExtractedTerm("{{name}}", "", type = ExtractedTerm.TYPE_NON_TRANSLATE, category = "", explanation = "名字变量"),
            ExtractedTerm("{{name}}", "", type = ExtractedTerm.TYPE_NON_TRANSLATE, category = "变量", explanation = "玩家名")
        )
        val result = useCase.mergeNonTranslateItems(items)
        assertEquals(1, result.size)
        assertEquals("变量", result[0].category)
    }

    @Test
    fun `mergeNonTranslateItems filters punctuation-only markers`() {
        val items = listOf(
            ExtractedTerm("...", "", type = ExtractedTerm.TYPE_NON_TRANSLATE, category = "其他"),
            ExtractedTerm("<br>", "", type = ExtractedTerm.TYPE_NON_TRANSLATE, category = "标签")
        )
        val result = useCase.mergeNonTranslateItems(items)
        assertEquals(1, result.size)
        assertEquals("<br>", result[0].sourceTerm)
    }

    @Test
    fun `mergeNonTranslateItems filters blank markers`() {
        val items = listOf(
            ExtractedTerm("", "", type = ExtractedTerm.TYPE_NON_TRANSLATE),
            ExtractedTerm("  ", "", type = ExtractedTerm.TYPE_NON_TRANSLATE),
            ExtractedTerm("{{var}}", "", type = ExtractedTerm.TYPE_NON_TRANSLATE)
        )
        val result = useCase.mergeNonTranslateItems(items)
        assertEquals(1, result.size)
        assertEquals("{{var}}", result[0].sourceTerm)
    }

    // ═══════════════════════════════════════════════════════════════
    //  extractJsonObject tests
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `extractJsonObject extracts from code-fenced JSON`() {
        val content = """
            ```json
            {"characters":[],"terms":[{"source":"HP","recommended_translation":"生命值","category_path":"其他","note":"体力"}],"non_translate":[]}
            ```
        """.trimIndent()
        val result = useCase.extractJsonObject(content)
        assertTrue(result.contains("HP"))
        assertTrue(result.contains("生命值"))
    }

    @Test
    fun `extractJsonObject extracts bare JSON`() {
        val content = """{"characters":[{"source":"露娜","recommended_translation":"露娜","gender":"女性","note":"NPC"}],"terms":[],"non_translate":[]}"""
        val result = useCase.extractJsonObject(content)
        assertTrue(result.contains("露娜"))
        assertTrue(result.contains("NPC"))
    }

    @Test
    fun `extractJsonObject returns empty for no JSON`() {
        val result = useCase.extractJsonObject("这是普通文本，没有JSON")
        assertEquals("", result)
    }

    // ═══════════════════════════════════════════════════════════════
    //  heuristicDedupBatch tests
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `heuristicDedupBatch returns empty for empty input`() {
        val result = useCase.heuristicDedupBatch(emptyList())
        assertTrue(result.isEmpty())
    }

    @Test
    fun `heuristicDedupBatch skips pure non_translate groups`() {
        val group = Stage2Group(
            source = "<br>",
            allVariants = mutableListOf("<br>"),
            candidates = mutableListOf(
                ExtractedTerm("<br>", "", type = ExtractedTerm.TYPE_NON_TRANSLATE, category = "标签")
            )
        )
        val result = useCase.heuristicDedupBatch(listOf(group))
        assertTrue("Pure non_translate groups should be skipped", result.isEmpty())
    }

    @Test
    fun `heuristicDedupBatch character wins when more character candidates`() {
        val group = Stage2Group(
            source = "亚瑟王",
            allVariants = mutableListOf("亚瑟王", "亚瑟"),
            candidates = mutableListOf(
                ExtractedTerm("亚瑟王", "亚瑟王", type = ExtractedTerm.TYPE_CHARACTER, category = "男性", explanation = "历史人物"),
                ExtractedTerm("亚瑟", "Arthur", type = ExtractedTerm.TYPE_CHARACTER, category = "男性", explanation = "别称"),
                ExtractedTerm("亚瑟", "Arthur", type = ExtractedTerm.TYPE_TERM, category = "称号", explanation = "错误分类")
            )
        )
        val result = useCase.heuristicDedupBatch(listOf(group))
        assertEquals(1, result.size)
        assertEquals(ExtractedTerm.TYPE_CHARACTER, result[0].type)
    }

    @Test
    fun `heuristicDedupBatch term wins when more term candidates`() {
        val group = Stage2Group(
            source = "月光斩",
            allVariants = mutableListOf("月光斩"),
            candidates = mutableListOf(
                ExtractedTerm("月光斩", "Moon Slash", type = ExtractedTerm.TYPE_TERM, category = "技能", explanation = "剑技名称")
            )
        )
        val result = useCase.heuristicDedupBatch(listOf(group))
        assertEquals(1, result.size)
        assertEquals(ExtractedTerm.TYPE_TERM, result[0].type)
    }

    @Test
    fun `heuristicDedupBatch default term when character and term tie`() {
        val group = Stage2Group(
            source = "亚瑟王",
            allVariants = mutableListOf("亚瑟王", "亚瑟"),
            candidates = mutableListOf(
                ExtractedTerm("亚瑟王", "亚瑟王", type = ExtractedTerm.TYPE_CHARACTER, category = "男性", explanation = "历史人物"),
                ExtractedTerm("亚瑟", "Arthur", type = ExtractedTerm.TYPE_TERM, category = "称号", explanation = "错误分类")
            )
        )
        val result = useCase.heuristicDedupBatch(listOf(group))
        assertEquals(1, result.size)
        // When tied, defaults to term
        assertEquals(ExtractedTerm.TYPE_TERM, result[0].type)
    }

    @Test
    fun `heuristicDedupBatch empty category when none available`() {
        val group = Stage2Group(
            source = "unknown",
            allVariants = mutableListOf("unknown"),
            candidates = mutableListOf(
                ExtractedTerm("unknown", "", type = ExtractedTerm.TYPE_TERM)
            )
        )
        val result = useCase.heuristicDedupBatch(listOf(group))
        assertEquals(1, result.size)
        assertEquals("", result[0].category)
    }

    // ═══════════════════════════════════════════════════════════════
    //  estimateStage2GroupTokens tests
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `estimateStage2GroupTokens returns positive for valid group`() {
        val group = Stage2Group(
            source = "HP",
            allVariants = mutableListOf("HP", "Hp"),
            candidates = mutableListOf(
                ExtractedTerm("HP", "生命值", type = ExtractedTerm.TYPE_TERM)
            )
        )
        val tokens = useCase.estimateStage2GroupTokens(group)
        assertTrue(tokens > 0)
    }

    // ═══════════════════════════════════════════════════════════════
    //  finalizeResults tests
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `finalizeResults returns Stage 2 results when all groups claimed`() {
        val stage2 = listOf(
            ExtractedTerm("月光斩", "月光斩", type = ExtractedTerm.TYPE_TERM, category = "技能", explanation = "剑技名称")
        )
        val group = Stage2Group(
            source = "月光斩",
            allVariants = mutableListOf("月光斩"),
            candidates = mutableListOf(
                ExtractedTerm("月光斩", "Moon Slash", type = ExtractedTerm.TYPE_TERM, category = "技能", explanation = "剑技名称")
            )
        )
        // Need to manipulate the alias map since it's populated by buildStage2Batches
        // but we can test the core logic: all groups claimed → no rescued items added
        val result = useCase.finalizeResults(stage2, listOf(group), emptyList())
        assertEquals(1, result.size)
        assertEquals("月光斩", result[0].sourceTerm)
    }

    @Test
    fun `finalizeResults rescues unclaimed groups`() {
        // Stage 2 only returns "月光斩", missing "星门" which was also extracted
        val stage2 = listOf(
            ExtractedTerm("月光斩", "月光斩", type = ExtractedTerm.TYPE_TERM, category = "技能", explanation = "剑技名称")
        )
        val groups = listOf(
            Stage2Group(
                source = "月光斩",
                allVariants = mutableListOf("月光斩"),
                candidates = mutableListOf(
                    ExtractedTerm("月光斩", "Moon Slash", type = ExtractedTerm.TYPE_TERM, category = "技能", explanation = "剑技名称")
                )
            ),
            Stage2Group(
                source = "星门",
                allVariants = mutableListOf("星门"),
                candidates = mutableListOf(
                    ExtractedTerm("星门", "星门", type = ExtractedTerm.TYPE_TERM, category = "地名", explanation = "传送地点")
                )
            )
        )
        val result = useCase.finalizeResults(stage2, groups, emptyList())
        assertEquals(2, result.size)
        // Stage 2 result preserved
        assertTrue(result.any { it.sourceTerm == "月光斩" })
        // Rescued group included
        assertTrue(result.any { it.sourceTerm == "星门" })
    }

    @Test
    fun `finalizeResults weighted scoring prefers character over term`() {
        // A group with mostly character candidates and a category
        val stage2 = emptyList<ExtractedTerm>()
        val groups = listOf(
            Stage2Group(
                source = "露娜小姐",
                allVariants = mutableListOf("露娜小姐"),
                candidates = mutableListOf(
                    ExtractedTerm("露娜小姐", "露娜小姐", type = ExtractedTerm.TYPE_CHARACTER, category = "女性", explanation = "NPC"),
                    ExtractedTerm("露娜", "Luna", type = ExtractedTerm.TYPE_TERM, category = "其他", explanation = "误分类")
                )
            )
        )
        val result = useCase.finalizeResults(stage2, groups, emptyList())
        assertEquals(1, result.size)
        // Character has higher weight (non-empty gender) → should be character
        assertEquals(ExtractedTerm.TYPE_CHARACTER, result[0].type)
    }

    @Test
    fun `finalizeResults weighted scoring prefers term with category`() {
        val stage2 = emptyList<ExtractedTerm>()
        val groups = listOf(
            Stage2Group(
                source = "圣剑",
                allVariants = mutableListOf("圣剑"),
                candidates = mutableListOf(
                    ExtractedTerm("圣剑", "Holy Sword", type = ExtractedTerm.TYPE_CHARACTER, category = "", explanation = ""),
                    ExtractedTerm("圣剑", "圣剑", type = ExtractedTerm.TYPE_TERM, category = "物品", explanation = "武器名称")
                )
            )
        )
        val result = useCase.finalizeResults(stage2, groups, emptyList())
        assertEquals(1, result.size)
        // Term has higher weight (non-empty category_path) → should be term
        assertEquals(ExtractedTerm.TYPE_TERM, result[0].type)
        assertEquals("物品", result[0].category)
    }

    @Test
    fun `finalizeResults skips pure non_translate groups`() {
        val stage2 = emptyList<ExtractedTerm>()
        val groups = listOf(
            Stage2Group(
                source = "<br>",
                allVariants = mutableListOf("<br>"),
                candidates = mutableListOf(
                    ExtractedTerm("<br>", "", type = ExtractedTerm.TYPE_NON_TRANSLATE, category = "标签")
                )
            )
        )
        val result = useCase.finalizeResults(stage2, groups, emptyList())
        assertEquals(0, result.size)
    }

    @Test
    fun `finalizeResults merges notes from multiple candidates`() {
        val stage2 = emptyList<ExtractedTerm>()
        val groups = listOf(
            Stage2Group(
                source = "精灵族",
                allVariants = mutableListOf("精灵族"),
                candidates = mutableListOf(
                    ExtractedTerm("精灵族", "Elves", type = ExtractedTerm.TYPE_TERM, category = "种族", explanation = "种族名称"),
                    ExtractedTerm("精灵", "精灵", type = ExtractedTerm.TYPE_TERM, category = "种族", explanation = "简称")
                )
            )
        )
        val result = useCase.finalizeResults(stage2, groups, emptyList())
        assertEquals(1, result.size)
        assertTrue(result[0].explanation.contains("种族名称"))
        assertTrue(result[0].explanation.contains("简称"))
    }
}
