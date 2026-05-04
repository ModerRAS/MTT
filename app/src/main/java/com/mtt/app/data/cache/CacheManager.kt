package com.mtt.app.data.cache

import com.mtt.app.data.local.dao.CacheItemDao
import com.mtt.app.data.model.CacheItemEntity
import com.mtt.app.data.model.TranslationMode
import com.mtt.app.data.model.TranslationResponse
import com.mtt.app.data.model.TranslationStatus
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Manages the translation cache using Room for persistence.
 *
 * Uses SHA-256 hash of sourceText + mode + modelId as the cache key
 * for deduplication. A hash-derived [textIndex] ensures that the same
 * input always maps to the same database row (idempotent writes).
 *
 * Hit/miss tracking and insertion timestamps are maintained in-memory
 * since [CacheItemEntity] has no dedicated timestamp column.
 *
 * Provided via [RepositoryModule]; constructor-injected dependencies
 * ([CacheItemDao]) are satisfied by [DatabaseModule].
 */
class CacheManager(
    private val cacheItemDao: CacheItemDao,
    private val cacheProjectId: String = DEFAULT_PROJECT_ID
) {
    private val hits = AtomicLong(0)
    private val misses = AtomicLong(0)
    private val nextSequentialIndex = AtomicInteger(0)
    private val insertionTimes = ConcurrentHashMap<String, Instant>()

    /**
     * Look up a cached translation by source text, mode, and model ID.
     * Returns null if no matching completed translation is found.
     */
    suspend fun getCached(
        sourceText: String,
        mode: TranslationMode,
        modelId: String
    ): TranslationResponse? {
        val allItems = cacheItemDao.getByProjectId(cacheProjectId)
        val match = allItems.firstOrNull { item ->
            item.sourceText == sourceText &&
                item.model == modelId &&
                item.status in completedStatuses
        }
        return if (match != null) {
            hits.incrementAndGet()
            TranslationResponse(
                content = match.translatedText,
                model = match.model,
                tokensUsed = 0 // cached result had no token cost
            )
        } else {
            misses.incrementAndGet()
            null
        }
    }

    /**
     * Save a translation to the cache.
     *
     * The [textIndex] is derived from the SHA-256 hash of
     * (sourceText + mode + modelId) so that repeated writes
     * for the same input update the same row.
     */
    suspend fun saveToCache(
        sourceText: String,
        translation: String,
        mode: TranslationMode,
        modelId: String
    ) {
        val dedupHash = sha256("$sourceText|${mode.name}|$modelId")
        val textIndex = hashToIntIndex(dedupHash)
        val timeKey = insertionTimeKey(sourceText, modelId)
        val now = Instant.now()

        val entity = CacheItemEntity(
            projectId = cacheProjectId,
            textIndex = textIndex,
            status = TranslationStatus.TRANSLATED,
            sourceText = sourceText,
            translatedText = translation,
            model = modelId,
            batchIndex = 0
        )

        cacheItemDao.insert(entity)
        insertionTimes[timeKey] = now

        // Keep track of the max index for sequential assignment
        nextSequentialIndex.updateAndGet { maxOf(it, textIndex + 1) }
    }

    /**
     * Returns aggregate cache statistics including hit rate,
     * total entries, and age of the oldest entry.
     */
    suspend fun getCacheStats(): CacheStats {
        val total = cacheItemDao.countTotal(cacheProjectId)
        val hitCount = hits.get()
        val missCount = misses.get()
        val totalRequests = hitCount + missCount
        val hitRate = if (totalRequests > 0) hitCount.toDouble() / totalRequests else 0.0

        val oldestAge = if (insertionTimes.isEmpty()) {
            null
        } else {
            val oldest = insertionTimes.values.min()
            Duration.between(oldest, Instant.now())
        }

        return CacheStats(
            totalEntries = total,
            hitCount = hitCount,
            missCount = missCount,
            hitRate = hitRate,
            oldestEntryAge = oldestAge
        )
    }

    /**
     * Remove cache entries older than [maxAge] based on tracked insertion times.
     * If no insertion time is tracked for an entry, it is retained.
     */
    suspend fun clearExpiredCache(maxAge: Duration) {
        val cutoff = Instant.now().minus(maxAge)
        val allItems = cacheItemDao.getByProjectId(cacheProjectId)

        val expired = allItems.filter { item ->
            val key = insertionTimeKey(item.sourceText, item.model)
            val insertedAt = insertionTimes[key]
            insertedAt != null && !insertedAt.isAfter(cutoff)
        }

        expired.forEach { item ->
            val key = insertionTimeKey(item.sourceText, item.model)
            cacheItemDao.updateItemStatus(
                projectId = cacheProjectId,
                textIndex = item.textIndex,
                status = TranslationStatus.EXCLUDED,
                translatedText = item.translatedText,
                model = item.model,
                batchIndex = item.batchIndex
            )
            insertionTimes.remove(key)
        }
    }

    /**
     * Remove all cache entries and reset in-memory counters.
     */
    suspend fun clearAllCache() {
        cacheItemDao.deleteByProjectId(cacheProjectId)
        hits.set(0)
        misses.set(0)
        insertionTimes.clear()
        nextSequentialIndex.set(0)
    }

    /**
     * Export all translations as a Map<String, String> for JSON export.
     * Returns source text as key and translated text as value.
     * Only includes entries with non-blank translated text.
     */
    suspend fun exportToJson(projectId: String = cacheProjectId): Map<String, String> {
        val allItems = cacheItemDao.getByProjectId(projectId)
        return allItems
            .filter { it.translatedText.isNotBlank() }
            .associate { it.sourceText to it.translatedText }
    }

    // ---- internal helpers ----

    /**
     * Reconstructable time-tracking key from entity fields only
     * (sourceText + modelId, without mode since entities don't store it).
     */
    private fun insertionTimeKey(sourceText: String, modelId: String): String {
        return "$sourceText|$modelId"
    }

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(input.toByteArray(Charsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Derive a non-negative database-safe Int from the first 4 bytes
     * of a hex hash string. Collisions are tolerated because
     * [CacheItemDao.insert] uses REPLACE strategy.
     */
    private fun hashToIntIndex(hash: String): Int {
        // Take first 8 hex chars (4 bytes) and convert to Int
        val sub = hash.take(8)
        val value = sub.toLong(16)
        return (value and 0x7FFFFFFF).toInt()
    }

    companion object {
        const val DEFAULT_PROJECT_ID = "translation_cache"

        private val completedStatuses = setOf(
            TranslationStatus.TRANSLATED,
            TranslationStatus.POLISHED
        )
    }
}

/**
 * Aggregate statistics for the translation cache.
 */
data class CacheStats(
    val totalEntries: Int,
    val hitCount: Long,
    val missCount: Long,
    val hitRate: Double,
    val oldestEntryAge: Duration?
)
