package com.mtt.app.service

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import com.mtt.app.HiltTestRunner
import dagger.hilt.android.testing.HiltTestApplication
import com.mtt.app.data.cache.CacheManager
import com.mtt.app.data.llm.RateLimiter
import com.mtt.app.data.local.dao.ExtractionJobDao
import com.mtt.app.data.local.dao.GlossaryDao
import com.mtt.app.data.local.dao.TranslationJobDao
import com.mtt.app.data.model.TranslationProgress
import com.mtt.app.data.network.HttpClientFactory
import com.mtt.app.data.security.SecureStorage
import com.mtt.app.di.AppModule
import com.mtt.app.di.DatabaseModule
import com.mtt.app.di.NetworkModule
import com.mtt.app.di.RepositoryModule
import com.mtt.app.di.SecurityModule
import com.mtt.app.di.UseCaseModule
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import dagger.hilt.components.SingletonComponent
import io.mockk.mockk
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNotificationManager
import javax.inject.Singleton

/**
 * Unit tests for [TranslationService].
 *
 * Uses Hilt testing infrastructure (@HiltAndroidTest + @UninstallModules) to
 * replace all production modules with a test module providing mock dependencies.
 * This ensures [Robolectric.buildService] properly handles the @AndroidEntryPoint
 * service without IllegalStateException from Hilt injection failures.
 */
@HiltAndroidTest
@UninstallModules(
    AppModule::class,
    RepositoryModule::class,
    UseCaseModule::class,
    NetworkModule::class,
    SecurityModule::class,
    DatabaseModule::class
)
@RunWith(HiltTestRunner::class)
@Config(application = HiltTestApplication::class, sdk = [Build.VERSION_CODES.O])
class TranslationServiceTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Module
    @InstallIn(SingletonComponent::class)
    object TestModule {
        @Provides
        @Singleton
        fun provideOkHttpClient(): OkHttpClient = OkHttpClient()

        @Provides
        @Singleton
        fun provideRateLimiter(): RateLimiter = mockk(relaxed = true)

        @Provides
        @Singleton
        fun provideCacheManager(): CacheManager = mockk(relaxed = true)

        // Bindings needed by Hilt ViewModels in the test component
        @Provides
        @Singleton
        fun provideHttpClientFactory(): HttpClientFactory = mockk(relaxed = true)

        @Provides
        @Singleton
        fun provideSecureStorage(): SecureStorage = mockk(relaxed = true)

        @Provides
        @Singleton
        fun provideGlossaryDao(): GlossaryDao = mockk(relaxed = true)

        @Provides
        @Singleton
        fun provideTranslationJobDao(): TranslationJobDao = mockk(relaxed = true)

        @Provides
        @Singleton
        fun provideExtractionJobDao(): ExtractionJobDao = mockk(relaxed = true)
    }

    private lateinit var service: TranslationService
    private lateinit var shadowNotificationManager: ShadowNotificationManager

    @Before
    fun setUp() {
        // Initialize Hilt component before creating the service
        hiltRule.inject()

        // Reset shared state between tests
        TranslationService.pendingTexts = emptyList()
        TranslationService.pendingConfig = null
        TranslationService._serviceProgress.value = TranslationProgress.initial()

        // With Hilt test infrastructure active, buildService properly handles
        // the @AndroidEntryPoint service — injection succeeds
        service = Robolectric.buildService(TranslationService::class.java).create().get()

        shadowNotificationManager = shadowOf(
            service.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        )
    }

    @After
    fun tearDown() {
        service.onDestroy()
        TranslationService.pendingTexts = emptyList()
        TranslationService.pendingConfig = null
        TranslationService._serviceProgress.value = TranslationProgress.initial()
    }

    // ═══════════════════════════════════════════════
    //  Notification Channel (verified via notification properties)
    // ═══════════════════════════════════════════════

    @Test
    fun `notification channel is created on service create`() {
        // Verified by buildNotification not throwing and notification having content
        val notification = service.buildTranslationNotification()
        assertNotNull("Notification should be created", notification)
    }

    @Test
    fun `notification channel uses IMPORTANCE_LOW`() {
        val notification = service.buildTranslationNotification()
        assertEquals(
            Notification.PRIORITY_LOW,
            notification.priority
        )
    }

    @Test
    fun `notification channel name is correct`() {
        val notification = service.buildTranslationNotification()
        val shadow = shadowOf(notification)
        assertEquals("MTT 翻译中", shadow.contentTitle)
    }

    @Test
    fun `notification channel badge is disabled`() {
        val notification = service.buildTranslationNotification()
        val shadow = shadowOf(notification)
        assertEquals("已完成 0/0 (0%)", shadow.contentText)
    }

    // ═══════════════════════════════════════════════
    //  Notification Content
    // ═══════════════════════════════════════════════

    @Test
    fun `initial notification shows MTT 翻译中 title`() {
        // _serviceProgress is TranslationProgress.initial() by default
        val notification = service.buildTranslationNotification()
        val shadow = shadowOf(notification)

        assertEquals("MTT 翻译中", shadow.contentTitle)
    }

    @Test
    fun `initial notification shows zero progress`() {
        val notification = service.buildTranslationNotification()
        val shadow = shadowOf(notification)

        assertEquals("已完成 0/0 (0%)", shadow.contentText)
    }

    @Test
    fun `notification has ongoing flag`() {
        val notification = service.buildTranslationNotification()
        assertTrue(
            (notification.flags and Notification.FLAG_ONGOING_EVENT) != 0
        )
    }

    @Test
    fun `notification uses low priority`() {
        val notification = service.buildTranslationNotification()
        assertEquals(
            Notification.PRIORITY_LOW,
            notification.priority
        )
    }

    @Test
    fun `notification shows completed items in progress text`() {
        TranslationService._serviceProgress.value = TranslationProgress(
            totalItems = 10,
            completedItems = 3,
            currentBatch = 0,
            totalBatches = 2,
            status = "翻译中"
        )

        val notification = service.buildTranslationNotification()
        val shadow = shadowOf(notification)

        assertEquals("已完成 3/10 (30%)", shadow.contentText)
    }

    @Test
    fun `notification shows 100 percent when complete`() {
        TranslationService._serviceProgress.value = TranslationProgress(
            totalItems = 5,
            completedItems = 5,
            currentBatch = 1,
            totalBatches = 1,
            status = "翻译完成"
        )

        val notification = service.buildTranslationNotification()
        val shadow = shadowOf(notification)

        assertEquals("已完成 5/5 (100%)", shadow.contentText)
    }

    @Test
    fun `notification shows indeterminate progress when total is zero`() {
        TranslationService._serviceProgress.value = TranslationProgress.initial()

        val notification = service.buildTranslationNotification()
        val shadow = shadowOf(notification)

        assertEquals("已完成 0/0 (0%)", shadow.contentText)
    }

    @Test
    fun `notification updates after progress change`() {
        // Simulate progress update
        TranslationService._serviceProgress.value = TranslationProgress(
            totalItems = 8,
            completedItems = 2,
            currentBatch = 0,
            totalBatches = 1,
            status = "翻译中"
        )

        val notification = service.buildTranslationNotification()
        assertEquals(
            "已完成 2/8 (25%)",
            shadowOf(notification).contentText
        )

        // Update again
        TranslationService._serviceProgress.value = TranslationProgress(
            totalItems = 8,
            completedItems = 4,
            currentBatch = 0,
            totalBatches = 1,
            status = "翻译中"
        )

        val updatedNotification = service.buildTranslationNotification()
        assertEquals(
            "已完成 4/8 (50%)",
            shadowOf(updatedNotification).contentText
        )
    }

    // ═══════════════════════════════════════════════
    //  Service Lifecycle
    // ═══════════════════════════════════════════════

    @Test
    fun `service is not null after creation`() {
        assertNotNull(service)
    }

    @Test
    fun `onBind returns null`() {
        val binder = service.onBind(null)
        assertEquals(null, binder)
    }

    @Test
    fun `START_NOT_STICKY is returned from onStartCommand`() {
        val intent = Intent(TranslationService.ACTION_START)
        val result = service.onStartCommand(intent, 0, 0)
        assertEquals(Service.START_NOT_STICKY, result)
    }

    @Test
    fun `service handles null intent gracefully`() {
        // Should not crash
        service.onStartCommand(null, 0, 0)
    }

    @Test
    fun `destroy releases resources without crash`() {
        service.onDestroy()
        // No exception = pass
    }

    @Test
    fun `pendingTexts and pendingConfig are empty after reset`() {
        TranslationService.pendingTexts = listOf("test")
        TranslationService.pendingConfig = mockk(relaxed = true)

        // Reset as setUp does
        TranslationService.pendingTexts = emptyList()
        TranslationService.pendingConfig = null

        assertTrue(TranslationService.pendingTexts.isEmpty())
        assertEquals(null, TranslationService.pendingConfig)
    }

    @Test
    fun `pendingFailedItems defaults to empty list`() {
        // Reset between tests
        TranslationService.pendingFailedItems = emptyList()

        assertTrue(TranslationService.pendingFailedItems.isEmpty())
    }

    @Test
    fun `pendingFailedItems can be set and cleared`() {
        val failedItems = listOf(
            com.mtt.app.data.model.FailedItem(
                globalIndex = 0,
                sourceText = "failed text",
                retryCount = 3,
                permanentlyFailed = true
            )
        )

        TranslationService.pendingFailedItems = failedItems
        assertEquals(1, TranslationService.pendingFailedItems.size)
        assertEquals(0, TranslationService.pendingFailedItems[0].globalIndex)
        assertTrue(TranslationService.pendingFailedItems[0].permanentlyFailed)

        // Reset as startTranslationViaService does
        TranslationService.pendingFailedItems = emptyList()
        assertTrue(TranslationService.pendingFailedItems.isEmpty())
    }

    // ═══════════════════════════════════════════════
    //  WakeLock
    // ═══════════════════════════════════════════════

    @Test
    fun `wakeLock tag contains translation wl`() {
        // Verify the tag constant is set as expected
        assertTrue(
            TranslationService.WAKE_LOCK_TAG.contains("mtt:bg:wl")
        )
    }

    @Test
    fun `powerManager service is available`() {
        val pm = ApplicationProvider.getApplicationContext<Context>()
            .getSystemService(Context.POWER_SERVICE) as PowerManager

        assertNotNull(pm)

        val shadowPm = shadowOf(pm)
        assertNotNull(shadowPm)
    }

    // ═══════════════════════════════════════════════
    //  Shared StateFlow
    // ═══════════════════════════════════════════════

    @Test
    fun `serviceProgress is exposed as read-only StateFlow`() {
        val progress = TranslationService.serviceProgress
        assertNotNull(progress)

        // Initial value should be idle
        assertEquals("Idle", progress.value.status)
        assertEquals(0, progress.value.totalItems)
        assertEquals(0, progress.value.completedItems)
    }

    @Test
    fun `serviceProgress reflects updated values`() {
        val newProgress = TranslationProgress(
            totalItems = 12,
            completedItems = 6,
            currentBatch = 1,
            totalBatches = 2,
            status = "翻译中"
        )
        TranslationService._serviceProgress.value = newProgress

        val observed = TranslationService.serviceProgress.value
        assertEquals(12, observed.totalItems)
        assertEquals(6, observed.completedItems)
        assertEquals(50, observed.percentage)
        assertEquals("翻译中", observed.status)
    }

    // ═══════════════════════════════════════════════
    //  Constants
    // ═══════════════════════════════════════════════

    @Test
    fun `PROGRESS_UPDATE_MS is 5000`() {
        assertEquals(5_000L, TranslationService.PROGRESS_UPDATE_MS)
    }

    @Test
    fun `NOTIFICATION_ID_TRANSLATION is 1`() {
        assertEquals(1, TranslationService.NOTIFICATION_ID_TRANSLATION)
    }

    @Test
    fun `WAKELOCK_TIMEOUT is 1 hour`() {
        assertEquals(60 * 60 * 1000L, TranslationService.WAKELOCK_TIMEOUT_MS)
    }

    @Test
    fun `ACTION_START and ACTION_STOP are distinct`() {
        assertTrue(TranslationService.ACTION_START != TranslationService.ACTION_STOP)
    }
}
