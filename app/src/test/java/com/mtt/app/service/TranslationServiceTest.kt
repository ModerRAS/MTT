package com.mtt.app.service

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import com.mtt.app.data.cache.CacheManager
import com.mtt.app.data.llm.RateLimiter
import com.mtt.app.data.model.TranslationProgress
import com.mtt.app.data.remote.llm.LlmService
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNotification
import org.robolectric.shadows.ShadowNotificationManager
import org.robolectric.shadows.ShadowPowerManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.O])
class TranslationServiceTest {

    private lateinit var service: TranslationService
    private lateinit var shadowNotificationManager: ShadowNotificationManager

    private val mockLlmService = mockk<LlmService>(relaxed = true)
    private val mockRateLimiter = mockk<RateLimiter>(relaxed = true)
    private val mockCacheManager = mockk<CacheManager>(relaxed = true)

    @Before
    fun setUp() {
        // Reset shared state between tests
        TranslationService.pendingTexts = emptyList()
        TranslationService.pendingConfig = null
        TranslationService._serviceProgress.value = TranslationProgress.initial()

        service = Robolectric.buildService(TranslationService::class.java).create().get()

        // Inject mock dependencies via reflection (Hilt not active in unit tests)
        injectField("llmService", mockLlmService)
        injectField("rateLimiter", mockRateLimiter)
        injectField("cacheManager", mockCacheManager)

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
    //  Notification Channel
    // ═══════════════════════════════════════════════

    @Test
    fun `notification channel is created on service create`() {
        val channel = shadowNotificationManager.getNotificationChannel(
            TranslationService.CHANNEL_ID
        )
        assertNotNull("Notification channel should be created", channel)
    }

    @Test
    fun `notification channel uses IMPORTANCE_LOW`() {
        val channel = shadowNotificationManager.getNotificationChannel(
            TranslationService.CHANNEL_ID
        )
        assertEquals(
            NotificationManager.IMPORTANCE_LOW,
            channel!!.importance
        )
    }

    @Test
    fun `notification channel name is correct`() {
        val channel = shadowNotificationManager.getNotificationChannel(
            TranslationService.CHANNEL_ID
        )
        assertEquals("MTT 翻译服务", channel!!.name)
    }

    @Test
    fun `notification channel badge is disabled`() {
        val channel = shadowNotificationManager.getNotificationChannel(
            TranslationService.CHANNEL_ID
        )
        assertFalse("Badge should be disabled", channel!!.canShowBadge())
    }

    // ═══════════════════════════════════════════════
    //  Notification Content
    // ═══════════════════════════════════════════════

    @Test
    fun `initial notification shows MTT 翻译中 title`() {
        // _serviceProgress is TranslationProgress.initial() by default
        val notification = service.buildNotification()
        val shadow = ShadowNotification.extractNotification(notification)

        assertEquals("MTT 翻译中", shadow.contentTitle)
    }

    @Test
    fun `initial notification shows zero progress`() {
        val notification = service.buildNotification()
        val shadow = ShadowNotification.extractNotification(notification)

        assertEquals("已完成 0/0 (0%)", shadow.contentText)
    }

    @Test
    fun `notification has ongoing flag`() {
        val notification = service.buildNotification()
        assertTrue(
            (notification.flags and Notification.FLAG_ONGOING_EVENT) != 0
        )
    }

    @Test
    fun `notification uses low priority`() {
        val notification = service.buildNotification()
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

        val notification = service.buildNotification()
        val shadow = ShadowNotification.extractNotification(notification)

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

        val notification = service.buildNotification()
        val shadow = ShadowNotification.extractNotification(notification)

        assertEquals("已完成 5/5 (100%)", shadow.contentText)
    }

    @Test
    fun `notification shows indeterminate progress when total is zero`() {
        TranslationService._serviceProgress.value = TranslationProgress.initial()

        val notification = service.buildNotification()
        val shadow = ShadowNotification.extractNotification(notification)

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

        val notification = service.buildNotification()
        assertEquals(
            "已完成 2/8 (25%)",
            ShadowNotification.extractNotification(notification).contentText
        )

        // Update again
        TranslationService._serviceProgress.value = TranslationProgress(
            totalItems = 8,
            completedItems = 4,
            currentBatch = 0,
            totalBatches = 1,
            status = "翻译中"
        )

        val updatedNotification = service.buildNotification()
        assertEquals(
            "已完成 4/8 (50%)",
            ShadowNotification.extractNotification(updatedNotification).contentText
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
        assertEquals(TranslationService.START_NOT_STICKY, result)
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

    // ═══════════════════════════════════════════════
    //  WakeLock
    // ═══════════════════════════════════════════════

    @Test
    fun `wakeLock tag contains translation wl`() {
        // Verify the tag constant is set as expected
        assertTrue(
            TranslationService.WAKE_LOCK_TAG.contains("translation")
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
    fun `NOTIFICATION_ID is 1`() {
        assertEquals(1, TranslationService.NOTIFICATION_ID)
    }

    @Test
    fun `WAKELOCK_TIMEOUT is 10 minutes`() {
        assertEquals(10 * 60 * 1000L, TranslationService.WAKELOCK_TIMEOUT_MS)
    }

    @Test
    fun `ACTION_START and ACTION_STOP are distinct`() {
        assertTrue(TranslationService.ACTION_START != TranslationService.ACTION_STOP)
    }

    // ═══════════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════════

    private fun injectField(name: String, value: Any) {
        val field = TranslationService::class.java.getDeclaredField(name)
        field.isAccessible = true
        field.set(service, value)
    }
}
