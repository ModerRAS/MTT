package com.mtt.app.ui.home

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.mtt.app.data.cache.CacheManager
import com.mtt.app.data.io.SourceTextRepository
import com.mtt.app.data.local.dao.ExtractionJobDao
import com.mtt.app.data.local.dao.GlossaryDao
import com.mtt.app.data.local.dao.TranslationJobDao
import com.mtt.app.data.model.FailedItem
import com.mtt.app.data.security.SecureStorage
import com.mtt.app.domain.pipeline.BatchResult
import com.mtt.app.domain.usecase.ExtractTermsUseCase
import com.mtt.app.domain.usecase.TranslateTextsUseCase
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HomeViewModelRetryTest {

    private val useCase: TranslateTextsUseCase = mockk()
    private val contentResolver: ContentResolver = mockk(relaxed = true)
    private val context: Context = mockk {
        every { contentResolver } returns this@HomeViewModelRetryTest.contentResolver
    }
    private val secureStorage: SecureStorage = mockk {
        every { getCustomModels() } returns null
        every { loadActiveChannelId() } returns null
        every { loadChannels() } returns emptyList()
        every { loadActiveModelId() } returns null
        every { getApiKey(any()) } returns null
        every { getValue(any()) } returns null
    }
    private val glossaryDao: GlossaryDao = mockk(relaxed = true)
    private val sourceTextRepository: SourceTextRepository = mockk(relaxed = true)
    private val extractTermsUseCase: ExtractTermsUseCase = mockk(relaxed = true)
    private val translationJobDao: TranslationJobDao = mockk(relaxed = true)
    private val extractionJobDao: ExtractionJobDao = mockk(relaxed = true)
    private val cacheManager: CacheManager = mockk(relaxed = true)
    private val testDispatcher = StandardTestDispatcher()
    private val uri: Uri = mockk(relaxed = true)

    private lateinit var viewModel: HomeViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        HomeViewModel.pendingAutoLoad = null
        coEvery { glossaryDao.getByProjectId(any()) } returns emptyList()

        viewModel = HomeViewModel(
            useCase,
            secureStorage,
            glossaryDao,
            sourceTextRepository,
            extractTermsUseCase,
            translationJobDao,
            extractionJobDao,
            cacheManager,
            context
        )
        viewModel.ioDispatcher = testDispatcher
        viewModel.useService = false
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `retry all preserves still failed global index after local retry result is remapped`() = runTest {
        loadSourceFile()
        advanceUntilIdle()
        coEvery { useCase.invoke(any(), any()) } returnsMany listOf(
            flowOf(
                BatchResult.RetryComplete(
                    finalFailedItems = listOf(
                        FailedItem(globalIndex = 1, sourceText = "one", permanentlyFailed = true),
                        FailedItem(globalIndex = 2, sourceText = "two", permanentlyFailed = true)
                    )
                ),
                BatchResult.Success(
                    batchIndex = 0,
                    items = listOf("zero translated", "", ""),
                    tokensUsed = 0,
                    inputTokens = 0,
                    outputTokens = 0,
                    cacheTokens = 0
                )
            ),
            flowOf(
                BatchResult.VerificationComplete(
                    totalItems = 2,
                    failedCount = 1,
                    failedItems = listOf(FailedItem(globalIndex = 1, sourceText = "two"))
                ),
                BatchResult.Success(
                    batchIndex = 0,
                    items = listOf("one translated", ""),
                    tokensUsed = 0,
                    inputTokens = 0,
                    outputTokens = 0,
                    cacheTokens = 0
                )
            )
        )

        viewModel.onStartTask()
        advanceUntilIdle()
        assertEquals(listOf(1, 2), viewModel.uiState.value.failedItems.map { it.globalIndex })

        viewModel.retryAllFailed()
        advanceUntilIdle()

        assertEquals(listOf(2), viewModel.uiState.value.failedItems.map { it.globalIndex })
        assertTrue(viewModel.uiState.value.screenState is ScreenState.Completed)
    }

    @Test
    fun `single retry preserves unrelated failed items`() = runTest {
        loadSourceFile()
        advanceUntilIdle()
        coEvery { useCase.invoke(any(), any()) } returnsMany listOf(
            flowOf(
                BatchResult.RetryComplete(
                    finalFailedItems = listOf(
                        FailedItem(globalIndex = 1, sourceText = "one", permanentlyFailed = true),
                        FailedItem(globalIndex = 2, sourceText = "two", permanentlyFailed = true)
                    )
                ),
                BatchResult.Success(
                    batchIndex = 0,
                    items = listOf("zero translated", "", ""),
                    tokensUsed = 0,
                    inputTokens = 0,
                    outputTokens = 0,
                    cacheTokens = 0
                )
            ),
            flowOf(
                BatchResult.VerificationComplete(
                    totalItems = 1,
                    failedCount = 1,
                    failedItems = listOf(FailedItem(globalIndex = 0, sourceText = "one"))
                ),
                BatchResult.Success(
                    batchIndex = 0,
                    items = listOf(""),
                    tokensUsed = 0,
                    inputTokens = 0,
                    outputTokens = 0,
                    cacheTokens = 0
                )
            )
        )

        viewModel.onStartTask()
        advanceUntilIdle()

        viewModel.retrySingleFailed(1)
        advanceUntilIdle()

        assertEquals(setOf(1, 2), viewModel.uiState.value.failedItems.map { it.globalIndex }.toSet())
        assertTrue(viewModel.uiState.value.screenState is ScreenState.Completed)
    }

    @Test
    fun `retry complete with no remaining failures removes retried item`() = runTest {
        loadSourceFile()
        advanceUntilIdle()
        coEvery { useCase.invoke(any(), any()) } returnsMany listOf(
            flowOf(
                BatchResult.RetryComplete(
                    finalFailedItems = listOf(
                        FailedItem(globalIndex = 1, sourceText = "one", permanentlyFailed = true),
                        FailedItem(globalIndex = 2, sourceText = "two", permanentlyFailed = true)
                    )
                ),
                BatchResult.Success(
                    batchIndex = 0,
                    items = listOf("zero translated", "", ""),
                    tokensUsed = 0,
                    inputTokens = 0,
                    outputTokens = 0,
                    cacheTokens = 0
                )
            ),
            flowOf(
                BatchResult.RetryComplete(finalFailedItems = emptyList()),
                BatchResult.Success(
                    batchIndex = 0,
                    items = listOf("one translated"),
                    tokensUsed = 0,
                    inputTokens = 0,
                    outputTokens = 0,
                    cacheTokens = 0
                )
            )
        )

        viewModel.onStartTask()
        advanceUntilIdle()

        viewModel.retrySingleFailed(1)
        advanceUntilIdle()

        assertEquals(listOf(2), viewModel.uiState.value.failedItems.map { it.globalIndex })
        assertTrue(viewModel.uiState.value.screenState is ScreenState.Completed)
    }

    @Test
    fun `successful retry clears failed item and completes screen`() = runTest {
        loadSourceFile()
        advanceUntilIdle()
        coEvery { useCase.invoke(any(), any()) } returnsMany listOf(
            flowOf(
                BatchResult.RetryComplete(
                    finalFailedItems = listOf(
                        FailedItem(globalIndex = 1, sourceText = "one", permanentlyFailed = true)
                    )
                ),
                BatchResult.Success(
                    batchIndex = 0,
                    items = listOf("zero translated", ""),
                    tokensUsed = 0,
                    inputTokens = 0,
                    outputTokens = 0,
                    cacheTokens = 0
                )
            ),
            flowOf(
                BatchResult.Success(
                    batchIndex = 0,
                    items = listOf("one translated"),
                    tokensUsed = 0,
                    inputTokens = 0,
                    outputTokens = 0,
                    cacheTokens = 0
                )
            )
        )

        viewModel.onStartTask()
        advanceUntilIdle()
        assertEquals(listOf(1), viewModel.uiState.value.failedItems.map { it.globalIndex })

        viewModel.retryAllFailed()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.failedItems.isEmpty())
        assertTrue(viewModel.uiState.value.screenState is ScreenState.Completed)
    }

    private fun loadSourceFile() {
        every { contentResolver.openInputStream(uri) } returns ByteArrayInputStream(
            """{"k0":"zero","k1":"one","k2":"two"}""".toByteArray()
        )
        viewModel.onFileSelected(uri, "retry.json")
    }
}
