package com.mtt.app.ui.result

import com.mtt.app.data.cache.CacheManager
import com.mtt.app.data.io.MtoolFileWriter
import com.mtt.app.data.model.TranslationStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

@OptIn(ExperimentalCoroutinesApi::class)
class ResultViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var cacheManager: CacheManager
    private lateinit var fileWriter: MtoolFileWriter
    private lateinit var viewModel: ResultViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        cacheManager = mockk()
        fileWriter = mockk()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `loadResults should load items from cache`() = runTest {
        // Given
        val mockData = mapOf(
            "こんにちは" to "你好",
            "おはようございます" to "早上好",
            "さようなら" to "再见"
        )
        coEvery { cacheManager.exportToJson() } returns mockData

        // When
        viewModel = ResultViewModel(cacheManager, fileWriter)
        advanceUntilIdle()

        // Then
        val state = viewModel.uiState.value
        assertTrue(state is ResultUiState.Success)
        val items = (state as ResultUiState.Success).items
        assertEquals(3, items.size)
        assertEquals("こんにちは", items[0].sourceText)
        assertEquals("你好", items[0].translatedText)
        assertEquals(TranslationStatus.TRANSLATED, items[0].status)
    }

    @Test
    fun `loadResults should handle empty cache`() = runTest {
        // Given
        coEvery { cacheManager.exportToJson() } returns emptyMap()

        // When
        viewModel = ResultViewModel(cacheManager, fileWriter)
        advanceUntilIdle()

        // Then
        val state = viewModel.uiState.value
        assertTrue(state is ResultUiState.Success)
        val items = (state as ResultUiState.Success).items
        assertEquals(0, items.size)
    }

    @Test
    fun `loadResults should handle error`() = runTest {
        // Given
        coEvery { cacheManager.exportToJson() } throws RuntimeException("Database error")

        // When
        viewModel = ResultViewModel(cacheManager, fileWriter)
        advanceUntilIdle()

        // Then
        val state = viewModel.uiState.value
        assertTrue(state is ResultUiState.Error)
        assertTrue((state as ResultUiState.Error).message.contains("加载结果失败"))
    }

    @Test
    fun `setStatusFilter should filter by status`() = runTest {
        // Given
        val mockData = mapOf(
            "こんにちは" to "你好",
            "おはようございます" to "早上好",
            "さようなら" to ""
        )
        coEvery { cacheManager.exportToJson() } returns mockData

        viewModel = ResultViewModel(cacheManager, fileWriter)
        advanceUntilIdle()

        // When
        viewModel.setStatusFilter(setOf(TranslationStatus.TRANSLATED))
        advanceUntilIdle()

        // Then
        val filteredItems = viewModel.filteredItems.value
        assertEquals(2, filteredItems.size)
        assertTrue(filteredItems.all { it.status == TranslationStatus.TRANSLATED })
    }

    @Test
    fun `setSearchText should filter by search text`() = runTest {
        // Given
        val mockData = mapOf(
            "こんにちは" to "你好",
            "おはようございます" to "早上好",
            "さようなら" to "再见"
        )
        coEvery { cacheManager.exportToJson() } returns mockData

        viewModel = ResultViewModel(cacheManager, fileWriter)
        advanceUntilIdle()

        // When
        viewModel.setSearchText("你好")
        advanceUntilIdle()

        // Then
        val filteredItems = viewModel.filteredItems.value
        assertEquals(1, filteredItems.size)
        assertEquals("こんにちは", filteredItems[0].sourceText)
    }

    @Test
    fun `setSearchText should filter by translated text`() = runTest {
        // Given
        val mockData = mapOf(
            "こんにちは" to "你好",
            "おはようございます" to "早上好",
            "さようなら" to "再见"
        )
        coEvery { cacheManager.exportToJson() } returns mockData

        viewModel = ResultViewModel(cacheManager, fileWriter)
        advanceUntilIdle()

        // When
        viewModel.setSearchText("早上好")
        advanceUntilIdle()

        // Then
        val filteredItems = viewModel.filteredItems.value
        assertEquals(1, filteredItems.size)
        assertEquals("おはようございます", filteredItems[0].sourceText)
    }

    @Test
    fun `setSearchText should be case insensitive`() = runTest {
        // Given
        val mockData = mapOf(
            "Hello" to "你好",
            "World" to "世界"
        )
        coEvery { cacheManager.exportToJson() } returns mockData

        viewModel = ResultViewModel(cacheManager, fileWriter)
        advanceUntilIdle()

        // When
        viewModel.setSearchText("hello")
        advanceUntilIdle()

        // Then
        val filteredItems = viewModel.filteredItems.value
        assertEquals(1, filteredItems.size)
        assertEquals("Hello", filteredItems[0].sourceText)
    }

    @Test
    fun `clearFilters should reset all filters`() = runTest {
        // Given
        val mockData = mapOf(
            "こんにちは" to "你好",
            "おはようございます" to "早上好"
        )
        coEvery { cacheManager.exportToJson() } returns mockData

        viewModel = ResultViewModel(cacheManager, fileWriter)
        advanceUntilIdle()

        // Apply filters
        viewModel.setStatusFilter(setOf(TranslationStatus.TRANSLATED))
        viewModel.setSearchText("你好")
        advanceUntilIdle()

        // When
        viewModel.clearFilters()
        advanceUntilIdle()

        // Then
        val filter = viewModel.filter.value
        assertEquals(emptySet<TranslationStatus>(), filter.statusFilter)
        assertEquals("", filter.searchText)
        
        val filteredItems = viewModel.filteredItems.value
        assertEquals(2, filteredItems.size)
    }

    @Test
    fun `combined filters should work together`() = runTest {
        // Given
        val mockData = mapOf(
            "こんにちは" to "你好",
            "おはようございます" to "早上好",
            "さようなら" to "再见",
            "test" to ""
        )
        coEvery { cacheManager.exportToJson() } returns mockData

        viewModel = ResultViewModel(cacheManager, fileWriter)
        advanceUntilIdle()

        // When
        viewModel.setStatusFilter(setOf(TranslationStatus.TRANSLATED))
        viewModel.setSearchText("好")
        advanceUntilIdle()

        // Then
        val filteredItems = viewModel.filteredItems.value
        assertEquals(2, filteredItems.size)
        assertTrue(filteredItems.all { 
            it.status == TranslationStatus.TRANSLATED && 
            (it.sourceText.contains("好") || it.translatedText.contains("好"))
        })
    }

    @Test
    fun `getStatusIcon should return correct icons`() {
        // Given
        viewModel = ResultViewModel(cacheManager, fileWriter)

        // Then
        assertEquals("✓", viewModel.getStatusIcon(TranslationStatus.TRANSLATED))
        assertEquals("✓", viewModel.getStatusIcon(TranslationStatus.POLISHED))
        assertEquals("⏳", viewModel.getStatusIcon(TranslationStatus.UNTRANSLATED))
        assertEquals("⊗", viewModel.getStatusIcon(TranslationStatus.EXCLUDED))
    }

    @Test
    fun `getStatusColor should return correct colors`() {
        // Given
        viewModel = ResultViewModel(cacheManager, fileWriter)

        // Then
        val green = androidx.compose.ui.graphics.Color(0xFF4CAF50)
        val blue = androidx.compose.ui.graphics.Color(0xFF2196F3)
        val orange = androidx.compose.ui.graphics.Color(0xFFFF9800)
        val red = androidx.compose.ui.graphics.Color(0xFFF44336)

        assertEquals(green, viewModel.getStatusColor(TranslationStatus.TRANSLATED))
        assertEquals(blue, viewModel.getStatusColor(TranslationStatus.POLISHED))
        assertEquals(orange, viewModel.getStatusColor(TranslationStatus.UNTRANSLATED))
        assertEquals(red, viewModel.getStatusColor(TranslationStatus.EXCLUDED))
    }

    @Test
    fun `loadResults should filter out blank translated text`() = runTest {
        // Given
        val mockData = mapOf(
            "こんにちは" to "你好",
            "おはようございます" to "",
            "さようなら" to "   ",  // Blank
            "test" to "测试"
        )
        coEvery { cacheManager.exportToJson() } returns mockData

        // When
        viewModel = ResultViewModel(cacheManager, fileWriter)
        advanceUntilIdle()

        // Then
        val state = viewModel.uiState.value
        assertTrue(state is ResultUiState.Success)
        val items = (state as ResultUiState.Success).items
        assertEquals(2, items.size)
        assertTrue(items.all { it.translatedText.isNotBlank() })
    }
}
