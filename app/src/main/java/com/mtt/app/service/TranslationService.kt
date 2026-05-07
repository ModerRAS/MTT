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
import com.mtt.app.data.local.dao.ExtractionJobDao
import com.mtt.app.data.local.dao.TranslationJobDao
import com.mtt.app.data.model.ExtractedTerm
import com.mtt.app.data.model.ExtractionJobEntity
import com.mtt.app.data.model.TranslationConfig
import com.mtt.app.data.model.TranslationJobEntity
import com.mtt.app.data.model.TranslationProgress
import com.mtt.app.domain.pipeline.BatchResult
import com.mtt.app.domain.pipeline.TranslationExecutor
import com.mtt.app.domain.usecase.ExtractTermsUseCase
import com.mtt.app.ui.glossary.ExtractionProgress
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
import javax.inject.Inject

/**
 * Foreground Service for background translation AND glossary extraction.
 *
 * ### Translation
 * Runs the [TranslationExecutor] pipeline on a background coroutine,
 * updates a persistent notification with progress, and exposes
 * [serviceProgress] for ViewModel observation.
 *
 * ### Glossary Extraction
 * Runs [ExtractTermsUseCase] (2-phase: frequency analysis + LLM validation)
 * with its own progress notification, exposing [extractionProgress] and
 * [extractionResult] for ViewModel observation.
 *
 * Requires WAKE_LOCK to prevent CPU sleep during long-running tasks.
 * Notification channel uses IMPORTANCE_LOW (silent but visible).
 */
@AndroidEntryPoint
class TranslationService : Service() {

    @Inject lateinit var cacheManager: CacheManager
    @Inject lateinit var executor: TranslationExecutor
    @Inject lateinit var translationJobDao: TranslationJobDao
    @Inject lateinit var extractTermsUseCase: ExtractTermsUseCase
    @Inject lateinit var extractionJobDao: ExtractionJobDao

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var currentJobId: String? = null
    private var currentExtractionJobId: String? = null

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
            ACTION_EXTRACT_TERMS -> handleExtractTerms()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        activeJob?.cancel()
        serviceScope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ═══════════════════════════════════════════════
    //  Translation
    // ═══════════════════════════════════════════════

    private fun handleStart() {
        // Snapshot pending data (set by ViewModel before starting)
        val texts = pendingTexts.toList()
        val config = pendingConfig ?: return
        val jobId = pendingJobId
        pendingTexts = emptyList()
        pendingConfig = null
        pendingJobId = null

        // Load and validate the persisted job
        currentJobId = jobId
        if (jobId != null) {
            serviceScope.launch {
                try {
                    val now = System.currentTimeMillis()
                    translationJobDao.updateProgress(
                        jobId = jobId,
                        status = TranslationJobEntity.STATUS_IN_PROGRESS,
                        completedItems = 0,
                        updatedAt = now
                    )
                } catch (_: Exception) {
                    // Non-critical: job tracking failure doesn't block translation
                }
            }
        }

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
        startForeground(NOTIFICATION_ID_TRANSLATION, buildTranslationNotification())

        activeJob = serviceScope.launch {
            // Resolve cache projectId from job's source file name (for file-scoped caching)
            val cacheProjectId = if (jobId != null) {
                try {
                    translationJobDao.getMetadataById(jobId)?.sourceFileName
                        ?: CacheManager.DEFAULT_PROJECT_ID
                } catch (_: Exception) {
                    CacheManager.DEFAULT_PROJECT_ID
                }
            } else {
                CacheManager.DEFAULT_PROJECT_ID
            }

            // Periodic notification updater (every 5 seconds)
            val updaterJob = launch {
                while (isActive) {
                    updateTranslationNotification()
                    delay(PROGRESS_UPDATE_MS)
                }
            }

            try {
                executor.executeBatch(texts, config, projectId = cacheProjectId).collect { result ->
                    when (result) {
                        is BatchResult.Started -> {
                            _serviceProgress.value = _serviceProgress.value.copy(
                                status = "开始翻译"
                            )
                        }
                        is BatchResult.Progress -> {
                            _serviceProgress.value = _serviceProgress.value.copy(
                                completedItems = result.completed,
                                status = "翻译中",
                                totalInputTokens = _serviceProgress.value.totalInputTokens + result.inputTokens,
                                totalOutputTokens = _serviceProgress.value.totalOutputTokens + result.outputTokens
                            )
                            // Persist progress to DB every progress tick
                            persistJobUpdate(
                                status = TranslationJobEntity.STATUS_IN_PROGRESS,
                                completedItems = result.completed
                            )
                        }
                        is BatchResult.Success -> {
                            _serviceProgress.value = _serviceProgress.value.copy(
                                completedItems = texts.size,
                                status = "翻译完成",
                                totalInputTokens = _serviceProgress.value.totalInputTokens + result.inputTokens,
                                totalOutputTokens = _serviceProgress.value.totalOutputTokens + result.outputTokens,
                                totalCacheTokens = _serviceProgress.value.totalCacheTokens + result.cacheTokens
                            )
                            // Final persistence: mark job as COMPLETED
                            // (cache entries were already saved incrementally per chunk)
                            persistJobUpdate(
                                status = TranslationJobEntity.STATUS_COMPLETED,
                                completedItems = texts.size
                            )
                        }
                        is BatchResult.Failure -> {
                            _serviceProgress.value = _serviceProgress.value.copy(
                                status = "翻译失败"
                            )
                            persistJobUpdate(
                                status = TranslationJobEntity.STATUS_FAILED,
                                completedItems = _serviceProgress.value.completedItems
                            )
                        }
                        is BatchResult.Retrying -> {
                            _serviceProgress.value = _serviceProgress.value.copy(
                                status = "重试中 (${result.attempt})"
                            )
                        }
                    }
                    // Immediate notification refresh on each progress event
                    updateTranslationNotification()
                }
            } catch (_: CancellationException) {
                _serviceProgress.value = _serviceProgress.value.copy(status = "已取消")
                persistJobUpdate(
                    status = TranslationJobEntity.STATUS_CANCELLED,
                    completedItems = _serviceProgress.value.completedItems
                )
            } finally {
                currentJobId = null
                updaterJob.cancel()
                releaseWakeLock()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    /**
     * Persist translation job progress to the database.
     * Non-critical: failures during DB writes are silently ignored.
     */
    private fun persistJobUpdate(status: String, completedItems: Int) {
        val jobId = currentJobId ?: return
        serviceScope.launch {
            try {
                translationJobDao.updateProgress(
                    jobId = jobId,
                    status = status,
                    completedItems = completedItems,
                    updatedAt = System.currentTimeMillis()
                )
            } catch (_: Exception) {
                // Non-critical
            }
        }
    }

    private fun handleStop() {
        activeJob?.cancel()
    }

    // ═══════════════════════════════════════════════
    //  Glossary Extraction
    // ═══════════════════════════════════════════════

    /**
     * Run glossary term extraction in the foreground service.
     *
     * Reads pending data from companion fields, creates/updates an
     * [ExtractionJobEntity] in the database, runs [ExtractTermsUseCase],
     * and exposes progress + results via companion StateFlows.
     */
    private fun handleExtractTerms() {
        val texts = pendingExtractionTexts
        val sourceLang = pendingExtractionSourceLang ?: return
        val extractionJobId = pendingExtractionJobId
        pendingExtractionTexts = emptyMap()
        pendingExtractionSourceLang = null
        pendingExtractionJobId = null
        pendingExtractionResult = null

        currentExtractionJobId = extractionJobId

        // Initialize progress and show foreground notification
        _extractionProgress.value = ExtractionProgress(0, 0)
        startForeground(NOTIFICATION_ID_EXTRACTION, buildExtractionNotification())
        acquireWakeLock()

        activeJob = serviceScope.launch {
            try {
                val result = extractTermsUseCase.extractTerms(texts, sourceLang) { completed, total ->
                    _extractionProgress.value = ExtractionProgress(completed, total)
                    updateExtractionNotification()

                    // Persist extraction job progress
                    persistExtractionJobUpdate(
                        status = ExtractionJobEntity.STATUS_LLM_VALIDATION,
                        completedChunks = completed.coerceAtMost(total)
                    )
                }

                when (result) {
                    is com.mtt.app.core.error.Result.Success -> {
                        pendingExtractionResult = result.data
                        _extractionProgress.value = ExtractionProgress(1, 1)
                        persistExtractionJobUpdate(
                            status = ExtractionJobEntity.STATUS_COMPLETED,
                            completedChunks = result.data.size
                        )
                    }
                    is com.mtt.app.core.error.Result.Failure -> {
                        val errorMsg = result.exception.message ?: "术语提取失败"
                        _extractionProgress.value = ExtractionProgress(0, 0, errorMessage = errorMsg)
                        persistExtractionJobUpdate(
                            status = ExtractionJobEntity.STATUS_FAILED,
                            completedChunks = 0
                        )
                    }
                }
                updateExtractionNotification()
            } catch (_: CancellationException) {
                persistExtractionJobUpdate(
                    status = ExtractionJobEntity.STATUS_FAILED,
                    completedChunks = 0
                )
            } finally {
                currentExtractionJobId = null
                releaseWakeLock()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    /**
     * Persist extraction job progress to the database.
     */
    private fun persistExtractionJobUpdate(status: String, completedChunks: Int) {
        val jobId = currentExtractionJobId ?: return
        serviceScope.launch {
            try {
                extractionJobDao.updateProgress(
                    jobId = jobId,
                    status = status,
                    completedChunks = completedChunks,
                    updatedAt = System.currentTimeMillis()
                )
            } catch (_: Exception) {
                // Non-critical
            }
        }
    }

    // ═══════════════════════════════════════════════
    //  Notifications
    // ═══════════════════════════════════════════════

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "MTT 后台任务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "后台翻译与术语提取进度"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    /** Build notification for translation progress. */
    internal fun buildTranslationNotification(): Notification {
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

    /** Build notification for glossary extraction progress. */
    internal fun buildExtractionNotification(): Notification {
        val ep = _extractionProgress.value
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = "MTT 术语提取中"
        val text: String
        val max: Int
        val progress: Int
        val indeterminate: Boolean
        if (ep.total > 0) {
            text = "验证候选术语 ${ep.completed}/${ep.total}"
            max = ep.total
            progress = ep.completed
            indeterminate = false
        } else {
            text = "正在分析文本..."
            max = 0
            progress = 0
            indeterminate = true
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setProgress(max, progress, indeterminate)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openIntent)
            .build()
    }

    private fun updateTranslationNotification() {
        notificationManager.notify(NOTIFICATION_ID_TRANSLATION, buildTranslationNotification())
    }

    private fun updateExtractionNotification() {
        notificationManager.notify(NOTIFICATION_ID_EXTRACTION, buildExtractionNotification())
    }

    // ═══════════════════════════════════════════════
    //  WakeLock
    // ═══════════════════════════════════════════════

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

    // ═══════════════════════════════════════════════
    //  Companion (shared state + constants)
    // ═══════════════════════════════════════════════

    companion object {
        const val CHANNEL_ID = "mtt_background_task_channel"
        const val NOTIFICATION_ID_TRANSLATION = 1
        const val NOTIFICATION_ID_EXTRACTION = 2
        const val PROGRESS_UPDATE_MS = 5_000L
        const val WAKE_LOCK_TAG = "mtt:bg:wl"
        const val WAKELOCK_TIMEOUT_MS = 60 * 60 * 1000L // 1 hour

        const val ACTION_START = "com.mtt.app.action.START_TRANSLATION"
        const val ACTION_STOP = "com.mtt.app.action.STOP_TRANSLATION"
        const val ACTION_EXTRACT_TERMS = "com.mtt.app.action.EXTRACT_TERMS"

        // ── Translation shared state ─────────────

        /** Read-only translation progress exposed to ViewModel. */
        val serviceProgress: StateFlow<TranslationProgress>
            get() = _serviceProgress.asStateFlow()

        @PublishedApi
        internal val _serviceProgress = MutableStateFlow(TranslationProgress.initial())

        /** Pending data for translation, set by ViewModel before starting the service. */
        var pendingTexts: List<String> = emptyList()
        var pendingConfig: TranslationConfig? = null
        var pendingJobId: String? = null

        // ── Extraction shared state ──────────────

        /** Read-only extraction progress exposed to ViewModel. */
        val extractionProgress: StateFlow<ExtractionProgress>
            get() = _extractionProgress.asStateFlow()

        @PublishedApi
        internal val _extractionProgress = MutableStateFlow(ExtractionProgress(0, 0))

        /** Pending data for glossary extraction, set by ViewModel before starting the service. */
        var pendingExtractionTexts: Map<String, String> = emptyMap()
        var pendingExtractionSourceLang: String? = null
        var pendingExtractionJobId: String? = null

        /** Extraction result set by the service, read by ViewModel after completion. */
        var pendingExtractionResult: List<ExtractedTerm>? = null
    }
}
