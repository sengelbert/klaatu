package com.klaatu.rss.sync

import android.content.Context
import androidx.glance.appwidget.updateAll
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.klaatu.rss.data.FeedRepository
import com.klaatu.rss.widget.KlaatuWidget
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Fetches uncached widget thumbnail URLs in the background and refreshes widgets
 * once at least one new image has been written to the disk cache.
 */
class ThumbnailPrefetchWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val urls = inputData.getStringArray(KEY_URLS)?.toList().orEmpty()
        if (urls.isEmpty()) return Result.success()

        val cached = withTimeoutOrNull(12_000L) {
            FeedRepository(applicationContext).prefetchThumbnails(urls)
        } ?: 0

        if (cached > 0) {
            KlaatuWidget().updateAll(applicationContext)
        }
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "klaatu_thumbnail_prefetch"
        private const val KEY_URLS = "urls"
        private const val COOLDOWN_MS = 10 * 60 * 1_000L

        private val recentAttempts = ConcurrentHashMap<String, Long>()

        fun enqueue(context: Context, urls: List<String>) {
            val now = System.currentTimeMillis()
            recentAttempts.entries.removeIf { now - it.value > COOLDOWN_MS }

            val pending = urls.asSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .filter { url ->
                    val lastAttempt = recentAttempts[url]
                    lastAttempt == null || (now - lastAttempt) > COOLDOWN_MS
                }
                .take(30)
                .toList()

            if (pending.isEmpty()) return
            pending.forEach { recentAttempts[it] = now }

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = OneTimeWorkRequestBuilder<ThumbnailPrefetchWorker>()
                .setConstraints(constraints)
                .setInputData(workDataOf(KEY_URLS to pending.toTypedArray()))
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request,
            )
        }
    }
}
