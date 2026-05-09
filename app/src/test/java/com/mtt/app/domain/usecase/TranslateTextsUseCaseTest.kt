package com.mtt.app.domain.usecase

import com.mtt.app.data.cache.CacheManager
import com.mtt.app.data.model.FailedItem
import com.mtt.app.data.model.LlmProvider
import com.mtt.app.data.model.ModelInfo
import com.mtt.app.data.model.TranslationConfig
import com.mtt.app.data.model.TranslationMode
import com.mtt.app.domain.pipeline.BatchResult
import com.mtt.app.domain.pipeline.TranslationExecutor
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TranslateTextsUseCaseTest {

    private val executor: TranslationExecutor = mockk()
    private val cacheManager: CacheManager = mockk(relaxed = true)
    private lateinit var useCase: TranslateTextsUseCase

    private val config = TranslationConfig(
        mode = TranslationMode.TRANSLATE,
        model = ModelInfo(
            modelId = "test-model",
            displayName = "Test Model",
            contextWindow = 128000,
            provider = LlmProvider.OpenAI("", "")
        ),
        sourceLang = "英语",
        targetLang = "中文"
    )

    @Before
    fun setUp() {
        useCase = TranslateTextsUseCase(executor, cacheManager)
        coEvery { cacheManager.getCached(any(), any(), any()) } returns null
        coEvery { cacheManager.saveToCache(any(), any(), any(), any(), any()) } returns Unit
    }

    @Test
    fun `invoke forwards verification and retry events with original indices`() = runBlocking {
        val texts = listOf("cached", "failed", "ok")
        coEvery { cacheManager.getCached("cached", config.mode, config.model.modelId) } returns mockk {
            every { content } returns "已缓存"
        }
        every { executor.executeBatch(listOf("failed", "ok"), config) } returns flowOf(
            BatchResult.VerificationComplete(
                totalItems = 2,
                failedCount = 1,
                failedItems = listOf(FailedItem(globalIndex = 0, sourceText = "failed"))
            ),
            BatchResult.RetryProgress(round = 1, completed = 0, total = 1),
            BatchResult.RetryComplete(
                finalFailedItems = listOf(
                    FailedItem(
                        globalIndex = 0,
                        sourceText = "failed",
                        retryCount = 3,
                        permanentlyFailed = true
                    )
                )
            ),
            BatchResult.Success(
                batchIndex = 0,
                items = listOf("failed", "译文"),
                tokensUsed = 10,
                inputTokens = 5,
                outputTokens = 5,
                cacheTokens = 0
            )
        )

        val results = useCase(texts, config).toList()

        val verification = results.filterIsInstance<BatchResult.VerificationComplete>().single()
        assertEquals(1, verification.failedItems.single().globalIndex)

        val retryComplete = results.filterIsInstance<BatchResult.RetryComplete>().single()
        assertEquals(1, retryComplete.finalFailedItems.single().globalIndex)
        assertTrue(retryComplete.finalFailedItems.single().permanentlyFailed)

        val success = results.last() as BatchResult.Success
        assertEquals(listOf("已缓存", "failed", "译文"), success.items)
    }

    @Test
    fun `invoke does not cache permanently failed passthrough items`() = runBlocking {
        val texts = listOf("failed", "ok")
        every { executor.executeBatch(texts, config) } returns flowOf(
            BatchResult.RetryComplete(
                finalFailedItems = listOf(
                    FailedItem(
                        globalIndex = 0,
                        sourceText = "failed",
                        retryCount = 3,
                        permanentlyFailed = true
                    )
                )
            ),
            BatchResult.Success(
                batchIndex = 0,
                items = listOf("failed", "译文"),
                tokensUsed = 10,
                inputTokens = 5,
                outputTokens = 5,
                cacheTokens = 0
            )
        )

        useCase(texts, config).toList()

        coVerify(exactly = 0) {
            cacheManager.saveToCache("failed", any(), any(), any(), any())
        }
        coVerify(exactly = 1) {
            cacheManager.saveToCache("ok", "译文", config.mode, config.model.modelId, any())
        }
    }
}
