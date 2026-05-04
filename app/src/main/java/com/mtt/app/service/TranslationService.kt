package com.mtt.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.mtt.app.MainActivity
import com.mtt.app.R
import com.mtt.app.data.cache.CacheManager
import com.mtt.app.data.llm.RateLimiter
import com.mtt.app.data.model.TranslationConfig
import com.mtt.app.data.model.TranslationProgress
import com.mtt.app.domain.pipeline.BatchResult
import com.mtt.app.domain.pipeline.TranslationExecutor
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import javax.inject.Inject

/**
 * Foreground Service for background translation.
 *
 * Runs the [TranslationExecutor] pipeline on a background coroutine,
 * updates a persistent notification with progress, and exposes
 * [serviceProgress] for ViewModel observation.
 *
 * Requires WAKE_LOCK to prevent CPU sleep during translation.
 * Notification channel uses IMPORTANCE_LOW (silent but visible).
 */
@AndroidEntryPoint
class TranslationService : Service() {

    @Inject lateinit var okHttpClient: OkHttpClient
    @Inject lateinit var rateLimiter: RateLimiter
    @Inject lateinit var cacheManager: CacheManager
    @Inject lateinit var executor: TranslationExecutor

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var translationJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private lateinit var notificationManager: NotificationManager

    // ── Lifecycle ────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart()
            ACTION_STOP -> handleStop()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        translationJob?.cancel()
        serviceScope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Start / Stop ─────────────────────────────

    private fun handleStart() {
        // Snapshot pending data (set by ViewModel before starting)
        val texts = pendingTexts.toList()
        val config = pendingConfig ?: return
        pendingTexts = emptyList()
        pendingConfig = null

        acquireWakeLock()

        // Initialize progress and show foreground notification IMMEDIATELY
        // (must call startForeground within ~5s to avoid ANR)
        _serviceProgress.value = TranslationProgress(
            totalItems = texts.size,
            completedItems = 0,
            currentBatch = 0,
            totalBatches = 1,
            status = "翻译中"
        )
        startForeground(NOTIFICATION_ID, buildNotification())

        translationJob = serviceScope.launch {
            // Periodic notification updater (every 5 seconds)
            val updaterJob = launch {
                while (isActive) {
                    updateNotification()
                    delay(PROGRESS_UPDATE_MS)
                }
            }

            try {
                executor.executeBatch(texts, config).collect { result ->
                    when (result) {
                        is BatchResult.Started -> {
                            _serviceProgress.value = _serviceProgress.value.copy(
                                status = "开始翻译"
                            )
                        }
                        is BatchResult.Progress -> {
                            _serviceProgress.value = _serviceProgress.value.copy(
                                completedItems = result.completed,
                                status = "翻译中"
                            )
                        }
                        is BatchResult.Success -> {
                            _serviceProgress.value = _serviceProgress.value.copy(
                                completedItems = texts.size,
                                status = "翻译完成"
                            )
                            // Save results to cache
                            result.items.forEachIndexed { i, translated ->
                                if (i < texts.size) {
                                    cacheManager.saveToCache(
                                        sourceText = texts[i],
                                        translation = translated,
                                        mode = config.mode,
                                        modelId = config.model.modelId
                                    )
                                }
                            }
                        }
                        is BatchResult.Failure -> {
                            _serviceProgress.value = _serviceProgress.value.copy(
                                status = "翻译失败"
                            )
                        }
                        is BatchResult.Retrying -> {
                            _serviceProgress.value = _serviceProgress.value.copy(
                                status = "重试中 (${result.attempt})"
                            )
                        }
                    }
                    // Immediate notification refresh on each progress event
                    updateNotification()
                }
            } catch (_: CancellationException) {
                _serviceProgress.value = _serviceProgress.value.copy(status = "已取消")
            } finally {
                updaterJob.cancel()
                releaseWakeLock()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun handleStop() {
        translationJob?.cancel()
    }

    // ── Notification ─────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "MTT 翻译服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "后台翻译进度"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * Builds the persistent foreground notification with current progress.
     * Format: "MTT 翻译中" with sub-text "已完成 X/Y (Z%)".
     */
    internal fun buildNotification(): Notification {
        val p = _serviceProgress.value
        val percentage = p.percentage
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MTT 翻译中")
            .setContentText("已完成 ${p.completedItems}/${p.totalItems} ($percentage%)")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setProgress(p.totalItems, p.completedItems, p.totalItems == 0)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openIntent)
            .build()
    }

    private fun updateNotification() {
        notificationManager.notify(NOTIFICATION_ID, buildNotification())
    }

    // ── WakeLock ─────────────────────────────────

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$WAKE_LOCK_TAG:${System.currentTimeMillis()}"
        ).apply {
            acquire(WAKELOCK_TIMEOUT_MS)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
    }

    // ── Companion (shared state + constants) ─────

    companion object {
        const val CHANNEL_ID = "mtt_translation_channel"
        const val NOTIFICATION_ID = 1
        const val PROGRESS_UPDATE_MS = 5_000L
        const val WAKE_LOCK_TAG = "mtt:translation:wl"
        const val WAKELOCK_TIMEOUT_MS = 10 * 60 * 1000L // 10 minutes

        const val ACTION_START = "com.mtt.app.action.START_TRANSLATION"
        const val ACTION_STOP = "com.mtt.app.action.STOP_TRANSLATION"

        // ── Shared state for ViewModel ──────────

        /** Read-only progress exposed to ViewModel. */
        val serviceProgress: StateFlow<TranslationProgress>
            get() = _serviceProgress.asStateFlow()

        @PublishedApi
        internal val _serviceProgress = MutableStateFlow(TranslationProgress.initial())

        /** Pending data set by ViewModel before starting the service. */
        var pendingTexts: List<String> = emptyList()
        var pendingConfig: TranslationConfig? = null
    }
}
