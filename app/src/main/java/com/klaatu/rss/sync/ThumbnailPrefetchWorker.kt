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
import com.klaatu.rss.data.AuditLog
import com.klaatu.rss.data.FeedRepository
import com.klaatu.rss.widget.KlaatuWidget
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

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
        val articleIds = inputData.getLongArray(KEY_ARTICLE_IDS) ?: LongArray(0)
        val articleLinks = inputData.getStringArray(KEY_ARTICLE_LINKS) ?: emptyArray()
        val articlesToScrape = articleIds.indices
            .take(articleLinks.size)
            .map { i -> articleIds[i] to articleLinks[i] }

        if (urls.isEmpty() && articlesToScrape.isEmpty()) return Result.success()

        val repo = FeedRepository(applicationContext)
        val scrapedUrls = if (articlesToScrape.isNotEmpty()) {
            withTimeoutOrNull(15_000L) {
                repo.scrapeArticleImages(articlesToScrape)
            }.orEmpty()
        } else {
            emptyList()
        }

        val allUrls = (urls + scrapedUrls).distinct()
        val cachedCount = AtomicInteger(0)
        if (allUrls.isNotEmpty()) {
            withTimeoutOrNull(25_000L) {
                repo.prefetchThumbnails(allUrls, cachedCount)
            }
        }

        if (cachedCount.get() > 0 || scrapedUrls.isNotEmpty()) {
            AuditLog.success(
                applicationContext,
                "THUMBS",
                "Prefetch: scraped ${scrapedUrls.size}/${articlesToScrape.size}, cached ${cachedCount.get()}/${allUrls.size}",
            )
            KlaatuWidget.updateAllSequential(applicationContext)
        } else {
            AuditLog.info(
                applicationContext,
                "THUMBS",
                "Prefetch: 0 new (urls=${urls.size}, scrape=${articlesToScrape.size})",
            )
        }
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "klaatu_thumbnail_prefetch"
        private const val KEY_URLS = "urls"
        private const val KEY_ARTICLE_IDS = "article_ids"
        private const val KEY_ARTICLE_LINKS = "article_links"
        private const val COOLDOWN_MS = 10 * 60 * 1_000L

        private val recentAttempts = ConcurrentHashMap<String, Long>()

        fun enqueue(
            context: Context,
            urls: List<String>,
            articlesToScrape: List<Pair<Long, String>> = emptyList(),
        ) {
            val now = System.currentTimeMillis()
            recentAttempts.entries.removeIf { now - it.value > COOLDOWN_MS }

            val pendingUrls = urls.asSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .filter { url ->
                    val lastAttempt = recentAttempts[url]
                    lastAttempt == null || (now - lastAttempt) > COOLDOWN_MS
                }
                .take(30)
                .toList()

            val pendingArticles = articlesToScrape.asSequence()
                .filter { (_, link) -> link.startsWith("http", ignoreCase = true) }
                .distinctBy { it.first }
                .filter { (_, link) ->
                    val lastAttempt = recentAttempts[link]
                    lastAttempt == null || (now - lastAttempt) > COOLDOWN_MS
                }
                .take(15)
                .toList()

            if (pendingUrls.isEmpty() && pendingArticles.isEmpty()) return
            pendingUrls.forEach { recentAttempts[it] = now }
            pendingArticles.forEach { (_, link) -> recentAttempts[link] = now }

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = OneTimeWorkRequestBuilder<ThumbnailPrefetchWorker>()
                .setConstraints(constraints)
                .setInputData(
                    workDataOf(
                        KEY_URLS to pendingUrls.toTypedArray(),
                        KEY_ARTICLE_IDS to pendingArticles.map { it.first }.toLongArray(),
                        KEY_ARTICLE_LINKS to pendingArticles.map { it.second }.toTypedArray(),
                    )
                )
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request,
            )
        }
    }
}
