package com.mtt.app.ui.translation

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.mtt.app.data.io.SourceTextRepository
import com.mtt.app.data.local.dao.GlossaryDao
import com.mtt.app.data.model.TranslationConfig
import com.mtt.app.data.model.TranslationMode
import com.mtt.app.data.model.TranslationProgress
import com.mtt.app.data.model.TranslationUiState
import com.mtt.app.data.security.SecureStorage
import com.mtt.app.domain.pipeline.BatchResult
import com.mtt.app.domain.usecase.TranslateTextsUseCase
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * Unit tests for [TranslationViewModel].
 *
 * Uses a mocked [TranslateTextsUseCase] to control the [BatchResult] flow
 * and a mocked [Context]/[ContentResolver] for file I/O.  [StandardTestDispatcher]
 * replaces both [Dispatchers.Main] and the ViewModel's [ioDispatcher].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TranslationViewModelTest {

    // ── Test doubles ──────────────────────────────

    private val useCase: TranslateTextsUseCase = mockk()
    private val contentResolver: ContentResolver = mockk(relaxed = true)
    private val context: Context = mockk {
        every { contentResolver } returns this@TranslationViewModelTest.contentResolver
    }
    private val secureStorage: SecureStorage = mockk {
        every { getApiKey(any()) } returns null   // Return null so defaults are used
    }
    private val mockGlossaryDao: GlossaryDao = mockk(relaxed = true)
    private val mockSourceTextRepository: SourceTextRepository = mockk(relaxed = true)
    private val testDispatcher = StandardTestDispatcher()

    init {
        coEvery { mockGlossaryDao.getByProjectId(any()) } returns emptyList()
    }

    private lateinit var viewModel: TranslationViewModel

    // ── Test fixtures ─────────────────────────────

    private val testUri: Uri = mockk(relaxed = true)
    private val testLines = listOf("Hello", "World", "Test")
    private val testFileContent = """{"Hello":"Hello","World":"World","Test":"Test"}"""

    // ── Setup / teardown ──────────────────────────

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        viewModel = TranslationViewModel(useCase, secureStorage, mockGlossaryDao, mockSourceTextRepository, context)
        viewModel.ioDispatcher = testDispatcher
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ═══════════════════════════════════════════════
    //  Initial state
    // ═══════════════════════════════════════════════

    @Test
    fun `initial state is Idle with default mode TRANSLATE`() {
        assertEquals(TranslationUiState.Idle, viewModel.uiState.value)
        assertEquals(TranslationProgress.initial(), viewModel.progress.value)
        assertEquals(TranslationMode.TRANSLATE, viewModel.currentMode.value)
    }

    // ═══════════════════════════════════════════════
    //  onChangeMode
    // ═══════════════════════════════════════════════

    @Test
    fun `onChangeMode updates currentMode state`() {
        viewModel.onChangeMode(TranslationMode.POLISH)
        assertEquals(TranslationMode.POLISH, viewModel.currentMode.value)

        viewModel.onChangeMode(TranslationMode.PROOFREAD)
        assertEquals(TranslationMode.PROOFREAD, viewModel.currentMode.value)

        viewModel.onChangeMode(TranslationMode.TRANSLATE)
        assertEquals(TranslationMode.TRANSLATE, viewModel.currentMode.value)
    }

    // ═══════════════════════════════════════════════
    //  onFileSelected — success
    // ═══════════════════════════════════════════════

    @Test
    fun `onFileSelected reads file and transitions to Idle`() = runTest {
        mockFileInputStream(testFileContent)

        viewModel.onFileSelected(testUri)
        advanceUntilIdle()

        assertEquals(TranslationUiState.Idle, viewModel.uiState.value)
        assertEquals(testLines.size, viewModel.progress.value.totalItems)
        assertEquals(0, viewModel.progress.value.completedItems)
        assertEquals("Ready", viewModel.progress.value.status)
    }

    @Test
    fun `onFileSelected sets fileName when provided`() = runTest {
        mockFileInputStream(testFileContent)

        viewModel.onFileSelected(testUri, "source.txt")
        advanceUntilIdle()

        assertEquals("source.txt", viewModel.selectedFileName.value)
    }

    @Test
    fun `onFileSelected parses JSON key-value pairs`() = runTest {
        val content = """{"key1":"text1","key2":"text2","key3":"text3"}"""
        mockFileInputStream(content)

        viewModel.onFileSelected(testUri)
        advanceUntilIdle()

        assertEquals(3, viewModel.progress.value.totalItems)
    }

    // ═══════════════════════════════════════════════
    //  onFileSelected — error
    // ═══════════════════════════════════════════════

    @Test
    fun `onFileSelected emits Error when file cannot be opened`() = runTest {
        every { contentResolver.openInputStream(testUri) } returns null

        viewModel.onFileSelected(testUri)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is TranslationUiState.Error)
        assertTrue((state as TranslationUiState.Error).message.contains("Cannot open file"))
    }

    @Test
    fun `onFileSelected emits Error on IOException`() = runTest {
        every { contentResolver.openInputStream(testUri) } throws IOException("Disk full")

        viewModel.onFileSelected(testUri)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is TranslationUiState.Error)
        assertTrue((state as TranslationUiState.Error).message.contains("Disk full"))
    }

    // ═══════════════════════════════════════════════
    //  onStartTranslation — empty texts
    // ═══════════════════════════════════════════════

    @Test
    fun `onStartTranslation emits Error when no texts loaded`() = runTest {
        viewModel.onStartTranslation()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is TranslationUiState.Error)
        assertTrue((state as TranslationUiState.Error).message.contains("No texts"))
    }

    // ═══════════════════════════════════════════════
    //  onStartTranslation — happy path
    // ═══════════════════════════════════════════════

    @Test
    fun `onStartTranslation transitions Idle → Translating → Completed`() = runTest {
        loadTestTexts()
        advanceUntilIdle()

        coEvery { useCase.invoke(any(), any()) } returns flowOf(
            BatchResult.Started(batchIndex = 0, size = 3),
            BatchResult.Progress(batchIndex = 0, completed = 1, total = 3, stage = "Processing"),
            BatchResult.Progress(batchIndex = 0, completed = 2, total = 3, stage = "Processing"),
            BatchResult.Success(batchIndex = 0, items = listOf("Hola", "Mundo", "Test"), tokensUsed = 42)
        )

        viewModel.onStartTranslation()
        advanceUntilIdle()

        assertEquals(TranslationUiState.Completed, viewModel.uiState.value)
        assertEquals(3, viewModel.progress.value.totalItems)
        assertEquals(3, viewModel.progress.value.completedItems)
        assertEquals("Complete", viewModel.progress.value.status)
    }

    @Test
    fun `onStartTranslation emits Translating with Progress events`() = runTest {
        loadTestTexts()
        advanceUntilIdle()

        coEvery { useCase.invoke(any(), any()) } returns flowOf(
            BatchResult.Started(batchIndex = 0, size = 2),
            BatchResult.Progress(batchIndex = 0, completed = 1, total = 2, stage = "Chunk 1"),
            BatchResult.Success(batchIndex = 0, items = listOf("A", "B"), tokensUsed = 10)
        )

        viewModel.onStartTranslation()
        advanceUntilIdle()

        assertEquals(TranslationUiState.Completed, viewModel.uiState.value)
        assertEquals(2, viewModel.progress.value.totalItems)
        assertEquals(2, viewModel.progress.value.completedItems)
        assertEquals("Complete", viewModel.progress.value.status)
    }

    // ═══════════════════════════════════════════════
    //  onStartTranslation — failure
    // ═══════════════════════════════════════════════

    @Test
    fun `onStartTranslation transitions to Error on BatchResult Failure`() = runTest {
        loadTestTexts()
        advanceUntilIdle()

        coEvery { useCase.invoke(any(), any()) } returns flowOf(
            BatchResult.Started(batchIndex = 0, size = 3),
            BatchResult.Failure(batchIndex = 0, error = RuntimeException("API quota exceeded"))
        )

        viewModel.onStartTranslation()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is TranslationUiState.Error)
        assertEquals("API quota exceeded", (state as TranslationUiState.Error).message)
    }

    @Test
    fun `onStartTranslation handles Flow exception gracefully`() = runTest {
        loadTestTexts()
        advanceUntilIdle()

        coEvery { useCase.invoke(any(), any()) } returns flowOf(
            BatchResult.Started(batchIndex = 0, size = 3),
            BatchResult.Failure(batchIndex = 0, error = RuntimeException())
        )

        viewModel.onStartTranslation()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is TranslationUiState.Error)
    }

    // ═══════════════════════════════════════════════
    //  onStartTranslation — retrying
    // ═══════════════════════════════════════════════

    @Test
    fun `onStartTranslation handles Retrying events`() = runTest {
        loadTestTexts()
        advanceUntilIdle()

        coEvery { useCase.invoke(any(), any()) } returns flowOf(
            BatchResult.Started(batchIndex = 0, size = 2),
            BatchResult.Retrying(batchIndex = 0, attempt = 1, reason = "Validation TooFew"),
            BatchResult.Progress(batchIndex = 0, completed = 1, total = 2, stage = "Retry chunk"),
            BatchResult.Success(batchIndex = 0, items = listOf("A", "B"), tokensUsed = 10)
        )

        viewModel.onStartTranslation()
        advanceUntilIdle()

        assertEquals(TranslationUiState.Completed, viewModel.uiState.value)
        assertEquals(2, viewModel.progress.value.totalItems)
        assertEquals(2, viewModel.progress.value.completedItems)
        assertEquals("Complete", viewModel.progress.value.status)
    }

    // ═══════════════════════════════════════════════
    //  onPauseTranslation
    // ═══════════════════════════════════════════════

    @Test
    fun `onPauseTranslation cancels translation and returns to Idle`() = runTest {
        loadTestTexts()
        advanceUntilIdle()

        // Emit started but never finish — flow is suspended
        coEvery { useCase.invoke(any(), any()) } returns flowOf(
            BatchResult.Started(batchIndex = 0, size = 3),
            BatchResult.Progress(batchIndex = 0, completed = 1, total = 3, stage = "Working...")
        )

        viewModel.onStartTranslation()
        advanceUntilIdle()

        // Now pause
        viewModel.onPauseTranslation()
        advanceUntilIdle()

        assertEquals(TranslationUiState.Idle, viewModel.uiState.value)
    }

    // ═══════════════════════════════════════════════
    //  onResumeTranslation
    // ═══════════════════════════════════════════════

    @Test
    fun `onResumeTranslation restarts full pipeline`() = runTest {
        loadTestTexts()
        advanceUntilIdle()

        coEvery { useCase.invoke(any(), any()) } returns flowOf(
            BatchResult.Started(batchIndex = 0, size = 3),
            BatchResult.Progress(batchIndex = 0, completed = 1, total = 3, stage = "Processing"),
            BatchResult.Success(batchIndex = 0, items = listOf("X", "Y", "Z"), tokensUsed = 10)
        )

        viewModel.onResumeTranslation()
        advanceUntilIdle()

        assertEquals(TranslationUiState.Completed, viewModel.uiState.value)
    }

    // ═══════════════════════════════════════════════
    //  onExportResult — success
    // ═══════════════════════════════════════════════

    @Test
    fun `onExportResult writes file and emits Completed`() = runTest {
        // First, complete a translation to populate translatedResults
        loadTestTexts()
        advanceUntilIdle()
        coEvery { useCase.invoke(any(), any()) } returns flowOf(
            BatchResult.Started(batchIndex = 0, size = 3),
            BatchResult.Success(batchIndex = 0, items = listOf("Hola", "Mundo", "Test"), tokensUsed = 42)
        )
        viewModel.onStartTranslation()
        advanceUntilIdle()
        assertEquals(TranslationUiState.Completed, viewModel.uiState.value)

        // Now test export
        val exportUri = mockk<Uri>(relaxed = true)
        val outputStream = ByteArrayOutputStream()
        every { contentResolver.openOutputStream(exportUri) } returns outputStream

        viewModel.onExportResult(exportUri)
        advanceUntilIdle()

        val exported = outputStream.toString(Charsets.UTF_8.name())
        assertEquals("""{
  "Hello": "Hola",
  "World": "Mundo",
  "Test": "Test"
}""", exported)
        assertEquals(TranslationUiState.Completed, viewModel.uiState.value)
    }

    // ═══════════════════════════════════════════════
    //  onExportResult — error
    // ═══════════════════════════════════════════════

    @Test
    fun `onExportResult emits Error when output stream fails`() = runTest {
        val exportUri = mockk<Uri>(relaxed = true)
        every { contentResolver.openOutputStream(exportUri) } returns null

        viewModel.onExportResult(exportUri)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is TranslationUiState.Error)
        assertTrue((state as TranslationUiState.Error).message.contains("export"))
    }

    // ═══════════════════════════════════════════════
    //  Integration: full workflow
    // ═══════════════════════════════════════════════

    @Test
    fun `full workflow — file → translate → export`() = runTest {
        // 1. Select file
        mockFileInputStream(testFileContent)
        viewModel.onFileSelected(testUri)
        advanceUntilIdle()
        assertEquals(TranslationUiState.Idle, viewModel.uiState.value)
        assertEquals(3, viewModel.progress.value.totalItems)

        // 2. Change mode
        viewModel.onChangeMode(TranslationMode.POLISH)
        assertEquals(TranslationMode.POLISH, viewModel.currentMode.value)

        // 3. Translate
        coEvery { useCase.invoke(any(), match { it.mode == TranslationMode.POLISH }) } returns flowOf(
            BatchResult.Started(batchIndex = 0, size = 3),
            BatchResult.Progress(batchIndex = 0, completed = 1, total = 3, stage = "Polishing"),
            BatchResult.Success(batchIndex = 0, items = listOf("Hola", "Mundo", "Test"), tokensUsed = 30)
        )

        viewModel.onStartTranslation()
        advanceUntilIdle()
        assertEquals(TranslationUiState.Completed, viewModel.uiState.value)

        // 4. Export
        val exportUri = mockk<Uri>(relaxed = true)
        val outputStream = ByteArrayOutputStream()
        every { contentResolver.openOutputStream(exportUri) } returns outputStream

        viewModel.onExportResult(exportUri)
        advanceUntilIdle()
        val expected = """{
  "Hello": "Hola",
  "World": "Mundo",
  "Test": "Test"
}"""
        assertEquals(expected, outputStream.toString(Charsets.UTF_8.name()))
    }

    @Test
    fun `pause and resume during translation`() = runTest {
        loadTestTexts()
        advanceUntilIdle()

        coEvery { useCase.invoke(any(), any()) } returns flowOf(
            BatchResult.Started(batchIndex = 0, size = 3),
            BatchResult.Progress(batchIndex = 0, completed = 1, total = 3, stage = "Part 1")
        )

        viewModel.onStartTranslation()
        advanceUntilIdle()
        assertEquals(1, viewModel.progress.value.completedItems)

        // Pause
        viewModel.onPauseTranslation()
        advanceUntilIdle()
        assertEquals(TranslationUiState.Idle, viewModel.uiState.value)

        // Resume — sets up new mock for the restart
        coEvery { useCase.invoke(any(), any()) } returns flowOf(
            BatchResult.Started(batchIndex = 0, size = 3),
            BatchResult.Progress(batchIndex = 0, completed = 2, total = 3, stage = "Part 2"),
            BatchResult.Success(batchIndex = 0, items = listOf("A", "B", "C"), tokensUsed = 20)
        )

        viewModel.onResumeTranslation()
        advanceUntilIdle()

        assertEquals(TranslationUiState.Completed, viewModel.uiState.value)
        assertEquals(3, viewModel.progress.value.completedItems)
    }

    @Test
    fun `selecting new file resets progress`() = runTest {
        // First translation
        loadTestTexts()
        advanceUntilIdle()
        coEvery { useCase.invoke(any(), any()) } returns flowOf(
            BatchResult.Started(batchIndex = 0, size = 3),
            BatchResult.Success(batchIndex = 0, items = listOf("A", "B", "C"), tokensUsed = 10)
        )
        viewModel.onStartTranslation()
        advanceUntilIdle()
        assertEquals(3, viewModel.progress.value.totalItems)

        // Select new file
        val newContent = """{"One":"One","Two":"Two","Three":"Three","Four":"Four","Five":"Five"}"""
        mockFileInputStream(newContent)
        viewModel.onFileSelected(mockk<Uri>(relaxed = true))
        advanceUntilIdle()

        assertEquals(5, viewModel.progress.value.totalItems)
        assertEquals(0, viewModel.progress.value.completedItems)
    }

    // ═══════════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════════

    private fun mockFileInputStream(content: String) {
        val inputStream = ByteArrayInputStream(content.toByteArray(Charsets.UTF_8))
        every { contentResolver.openInputStream(any<Uri>()) } returns inputStream
    }

    /** Load test texts by mocking the file read inside [onFileSelected]. */
    private fun loadTestTexts(uri: Uri = testUri) {
        mockFileInputStream(testFileContent)
        viewModel.onFileSelected(uri)
    }
}
