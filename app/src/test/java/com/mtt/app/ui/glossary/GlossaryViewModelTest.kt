package com.mtt.app.ui.glossary

import com.mtt.app.data.io.SourceTextRepository
import com.mtt.app.data.local.dao.GlossaryDao
import com.mtt.app.data.model.GlossaryEntryEntity
import com.mtt.app.data.security.SecureStorage
import com.mtt.app.domain.glossary.GlossaryEntry
import com.mtt.app.domain.usecase.ExtractTermsUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for GlossaryViewModel.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GlossaryViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var mockGlossaryDao: GlossaryDao
    private lateinit var mockSourceTextRepository: SourceTextRepository
    private lateinit var mockExtractTermsUseCase: ExtractTermsUseCase
    private lateinit var mockSecureStorage: SecureStorage
    private lateinit var viewModel: GlossaryViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        mockGlossaryDao = mockk(relaxed = true)
        mockSourceTextRepository = mockk(relaxed = true) {
            coEvery { sourceTexts } returns MutableStateFlow(emptyMap())
        }
        mockExtractTermsUseCase = mockk(relaxed = true)
        mockSecureStorage = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state loads glossary data`() = runTest {
        // Given
        val mockEntries = listOf(
            GlossaryEntryEntity(
                id = 1,
                projectId = "default_project",
                sourceTerm = "HP",
                targetTerm = "生命值",
                matchType = GlossaryEntryEntity.MATCH_TYPE_EXACT
            ),
            GlossaryEntryEntity(
                id = 2,
                projectId = "default_project",
                sourceTerm = "MP",
                targetTerm = "魔法值",
                matchType = GlossaryEntryEntity.MATCH_TYPE_EXACT
            ),
            GlossaryEntryEntity(
                id = 3,
                projectId = "default_project",
                sourceTerm = "ProhibitedTerm",
                targetTerm = "",
                matchType = GlossaryEntryEntity.MATCH_TYPE_EXACT
            )
        )
        coEvery { mockGlossaryDao.getByProjectId("default_project") } returns mockEntries

        // When
        viewModel = GlossaryViewModel(mockGlossaryDao, mockSourceTextRepository, mockExtractTermsUseCase, mockSecureStorage)
        advanceUntilIdle()

        // Then
        val state = viewModel.uiState.value
        assertEquals(2, state.glossaryCount)
        assertEquals(1, state.prohibitionCount)
        assertEquals(3, state.previewEntries.size)
    }

    @Test
    fun `importGlossaryFromCsv parses CSV correctly`() = runTest {
        // Given
        val csvContent = """
            HP,生命值
            MP,魔法值
            ATK,攻击力
        """.trimIndent()

        coEvery { mockGlossaryDao.getByProjectId("default_project") } returns emptyList()
        coEvery { mockGlossaryDao.insertAll(any()) } returns Unit

        viewModel = GlossaryViewModel(mockGlossaryDao, mockSourceTextRepository, mockExtractTermsUseCase, mockSecureStorage)
        advanceUntilIdle()

        // When
        viewModel.importGlossaryFromCsv(mockk(), csvContent)
        advanceUntilIdle()

        // Then
        coVerify { mockGlossaryDao.insertAll(match { entries ->
            entries.size == 3 &&
            entries[0].sourceTerm == "HP" &&
            entries[0].targetTerm == "生命值" &&
            entries[1].sourceTerm == "MP" &&
            entries[1].targetTerm == "魔法值" &&
            entries[2].sourceTerm == "ATK" &&
            entries[2].targetTerm == "攻击力"
        }) }

        val state = viewModel.uiState.value
        assertEquals("成功导入 3 条术语", state.successMessage)
    }

    @Test
    fun `importGlossaryFromCsv handles empty lines`() = runTest {
        // Given
        val csvContent = """
            HP,生命值

            MP,魔法值

            ATK,攻击力

        """.trimIndent()

        coEvery { mockGlossaryDao.getByProjectId("default_project") } returns emptyList()
        coEvery { mockGlossaryDao.insertAll(any()) } returns Unit

        viewModel = GlossaryViewModel(mockGlossaryDao, mockSourceTextRepository, mockExtractTermsUseCase, mockSecureStorage)
        advanceUntilIdle()

        // When
        viewModel.importGlossaryFromCsv(mockk(), csvContent)
        advanceUntilIdle()

        // Then
        coVerify { mockGlossaryDao.insertAll(match { entries ->
            entries.size == 3
        }) }
    }

    @Test
    fun `importGlossaryFromCsv skips invalid lines`() = runTest {
        // Given
        val csvContent = """
            HP,生命值
            InvalidLine
            MP,魔法值
            ,EmptySource
            ATK,
        """.trimIndent()

        coEvery { mockGlossaryDao.getByProjectId("default_project") } returns emptyList()
        coEvery { mockGlossaryDao.insertAll(any()) } returns Unit

        viewModel = GlossaryViewModel(mockGlossaryDao, mockSourceTextRepository, mockExtractTermsUseCase, mockSecureStorage)
        advanceUntilIdle()

        // When
        viewModel.importGlossaryFromCsv(mockk(), csvContent)
        advanceUntilIdle()

        // Then
        coVerify { mockGlossaryDao.insertAll(match { entries ->
            entries.size == 2 &&
            entries[0].sourceTerm == "HP" &&
            entries[1].sourceTerm == "MP"
        }) }
    }

    @Test
    fun `importProhibitionList parses text correctly`() = runTest {
        // Given
        val textContent = """
            ProhibitedTerm1
            ProhibitedTerm2
            ProhibitedTerm3
        """.trimIndent()

        coEvery { mockGlossaryDao.getByProjectId("default_project") } returns emptyList()
        coEvery { mockGlossaryDao.insertAll(any()) } returns Unit

        viewModel = GlossaryViewModel(mockGlossaryDao, mockSourceTextRepository, mockExtractTermsUseCase, mockSecureStorage)
        advanceUntilIdle()

        // When
        viewModel.importProhibitionList(mockk(), textContent)
        advanceUntilIdle()

        // Then
        coVerify { mockGlossaryDao.insertAll(match { entries ->
            entries.size == 3 &&
            entries[0].sourceTerm == "ProhibitedTerm1" &&
            entries[0].targetTerm == "" &&
            entries[1].sourceTerm == "ProhibitedTerm2" &&
            entries[1].targetTerm == "" &&
            entries[2].sourceTerm == "ProhibitedTerm3" &&
            entries[2].targetTerm == ""
        }) }

        val state = viewModel.uiState.value
        assertEquals("成功导入 3 条禁翻术语", state.successMessage)
    }

    @Test
    fun `importProhibitionList handles empty lines`() = runTest {
        // Given
        val textContent = """
            ProhibitedTerm1

            ProhibitedTerm2

            ProhibitedTerm3

        """.trimIndent()

        coEvery { mockGlossaryDao.getByProjectId("default_project") } returns emptyList()
        coEvery { mockGlossaryDao.insertAll(any()) } returns Unit

        viewModel = GlossaryViewModel(mockGlossaryDao, mockSourceTextRepository, mockExtractTermsUseCase, mockSecureStorage)
        advanceUntilIdle()

        // When
        viewModel.importProhibitionList(mockk(), textContent)
        advanceUntilIdle()

        // Then
        coVerify { mockGlossaryDao.insertAll(match { entries ->
            entries.size == 3
        }) }
    }

    @Test
    fun `clearGlossary deletes all entries`() = runTest {
        // Given
        coEvery { mockGlossaryDao.getByProjectId("default_project") } returns emptyList()
        coEvery { mockGlossaryDao.deleteByProjectId("default_project") } returns Unit

        viewModel = GlossaryViewModel(mockGlossaryDao, mockSourceTextRepository, mockExtractTermsUseCase, mockSecureStorage)
        advanceUntilIdle()

        // When
        viewModel.clearGlossary()
        advanceUntilIdle()

        // Then
        coVerify { mockGlossaryDao.deleteByProjectId("default_project") }

        val state = viewModel.uiState.value
        assertEquals("已清空术语表", state.successMessage)
    }

    @Test
    fun `clearMessages clears success and error messages`() = runTest {
        // Given
        coEvery { mockGlossaryDao.getByProjectId("default_project") } returns emptyList()
        viewModel = GlossaryViewModel(mockGlossaryDao, mockSourceTextRepository, mockExtractTermsUseCase, mockSecureStorage)
        advanceUntilIdle()

        // Simulate a success message
        viewModel.importGlossaryFromCsv(mockk(), "HP,生命值")
        advanceUntilIdle()

        // Verify message exists
        assertTrue(viewModel.uiState.value.successMessage != null)

        // When
        viewModel.clearMessages()

        // Then
        val state = viewModel.uiState.value
        assertEquals(null, state.successMessage)
        assertEquals(null, state.errorMessage)
    }
}

/**
 * Unit tests for CSV parsing logic.
 */
class CsvParsingTest {

    @Test
    fun `parseCsvContent handles standard CSV`() {
        val content = """
            HP,生命值
            MP,魔法值
            ATK,攻击力
        """.trimIndent()

        val entries = parseCsvContent(content)

        assertEquals(3, entries.size)
        assertEquals("HP", entries[0].source)
        assertEquals("生命值", entries[0].target)
        assertEquals("MP", entries[1].source)
        assertEquals("魔法值", entries[1].target)
        assertEquals("ATK", entries[2].source)
        assertEquals("攻击力", entries[2].target)
    }

    @Test
    fun `parseCsvContent handles CSV with spaces`() {
        val content = """
            HP , 生命值
            MP , 魔法值
        """.trimIndent()

        val entries = parseCsvContent(content)

        assertEquals(2, entries.size)
        assertEquals("HP", entries[0].source)
        assertEquals("生命值", entries[0].target)
    }

    @Test
    fun `parseCsvContent skips empty lines`() {
        val content = """
            HP,生命值

            MP,魔法值

        """.trimIndent()

        val entries = parseCsvContent(content)

        assertEquals(2, entries.size)
    }

    @Test
    fun `parseCsvContent skips invalid lines`() {
        val content = """
            HP,生命值
            InvalidLine
            MP,魔法值
            ,EmptySource
            ATK,
        """.trimIndent()

        val entries = parseCsvContent(content)

        assertEquals(2, entries.size)
        assertEquals("HP", entries[0].source)
        assertEquals("MP", entries[1].source)
    }

    @Test
    fun `parseCsvContent handles empty content`() {
        val content = ""

        val entries = parseCsvContent(content)

        assertEquals(0, entries.size)
    }

    @Test
    fun `parseCsvContent handles only whitespace`() {
        val content = """
            
                
            
        """.trimIndent()

        val entries = parseCsvContent(content)

        assertEquals(0, entries.size)
    }

    /**
     * Helper function to parse CSV content (mirrors ViewModel logic).
     */
    private fun parseCsvContent(content: String): List<GlossaryEntry> {
        return content.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { line ->
                val parts = line.split(",", limit = 2)
                if (parts.size == 2) {
                    val source = parts[0].trim()
                    val target = parts[1].trim()
                    if (source.isNotEmpty() && target.isNotEmpty()) {
                        GlossaryEntry(source = source, target = target)
                    } else {
                        null
                    }
                } else {
                    null
                }
            }
    }
}