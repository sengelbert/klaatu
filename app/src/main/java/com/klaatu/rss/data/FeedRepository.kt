package com.klaatu.rss.data

import android.content.Context
import android.graphics.drawable.BitmapDrawable
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.klaatu.rss.data.db.ArticleEntity
import com.klaatu.rss.data.db.ArticleWithFeed
import com.klaatu.rss.data.db.FeedEntity
import com.klaatu.rss.data.db.KlaatuDatabase
import com.klaatu.rss.data.feed.FeedParser
import com.klaatu.rss.data.opml.OpmlCodec
import com.klaatu.rss.data.opml.OpmlFeed
import com.klaatu.rss.widget.WidgetThumbnailStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class FeedRepository(context: Context) {

    private val appContext = context.applicationContext
    private val dao = KlaatuDatabase.getInstance(appContext).dao()
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    fun observeFeeds(): Flow<List<FeedEntity>> = dao.observeFeeds()
    fun observeCategories(): Flow<List<String>> = dao.observeCategories()
    fun observeTotalArticleCount(): Flow<Int> = dao.observeTotalArticleCount()
    fun observeFeedLatestArticleMap(): Flow<Map<Long, Long>> =
        dao.observeFeedLatestArticleTimes().map { rows ->
            rows.associate { it.feedId to it.latestPublishedAt }
        }
    fun observeArticles(category: String?, limit: Int = 150): Flow<List<ArticleWithFeed>> =
        dao.observeArticles(category, limit)

    suspend fun importOpml(inputStream: InputStream): Int = withContext(Dispatchers.IO) {
        val parsed = OpmlCodec.parse(inputStream)
        if (parsed.isEmpty()) return@withContext 0
        val entities = parsed.map {
            FeedEntity(
                title = it.title,
                xmlUrl = it.xmlUrl,
                htmlUrl = it.htmlUrl,
                category = it.category,
            )
        }
        val inserted = dao.insertFeeds(entities)
        // Update category/title for any existing feeds so re-importing OPML updates folders cleanly
        parsed.forEachIndexed { index, opmlFeed ->
            if (inserted[index] == -1L) {
                dao.updateFeedCategoryByUrl(
                    xmlUrl = opmlFeed.xmlUrl,
                    category = opmlFeed.category,
                    title = opmlFeed.title,
                )
            }
        }
        inserted.count { it != -1L }
    }

    suspend fun exportOpml(): String = withContext(Dispatchers.IO) {
        val feeds = dao.getAllFeeds().map {
            OpmlFeed(
                title = it.title,
                xmlUrl = it.xmlUrl,
                htmlUrl = it.htmlUrl,
                category = it.category,
            )
        }
        OpmlCodec.export(feeds)
    }

    suspend fun addFeed(url: String, category: String = OpmlFeed.DEFAULT_CATEGORY): Boolean =
        withContext(Dispatchers.IO) {
            val cleanUrl = url.trim()
            if (cleanUrl.isEmpty()) return@withContext false
            val ids = dao.insertFeeds(
                listOf(
                    FeedEntity(
                        title = cleanUrl,
                        xmlUrl = cleanUrl,
                        category = category.ifBlank { OpmlFeed.DEFAULT_CATEGORY },
                    )
                )
            )
            ids.firstOrNull() != -1L
        }

    suspend fun deleteFeed(feedId: Long) = withContext(Dispatchers.IO) {
        dao.deleteFeed(feedId)
    }

    suspend fun deleteFeedsByCategory(category: String) = withContext(Dispatchers.IO) {
        dao.deleteFeedsByCategory(category)
    }

    suspend fun deleteBrokenFeeds() = withContext(Dispatchers.IO) {
        dao.deleteBrokenFeeds()
    }

    suspend fun deleteFeedsByIds(feedIds: List<Long>) = withContext(Dispatchers.IO) {
        if (feedIds.isNotEmpty()) {
            dao.deleteFeedsByIds(feedIds)
        }
    }

    suspend fun syncAllFeeds(): Int = withContext(Dispatchers.IO) {
        val feeds = dao.getAllFeeds()
        if (feeds.isEmpty()) return@withContext 0
        val syncedCount = AtomicInteger(0)
        val failedCount = AtomicInteger(0)
        val semaphore = Semaphore(8)

        coroutineScope {
            feeds.map { feed ->
                async {
                    semaphore.withPermit {
                        val result = runCatching {
                            val initialUrl = if (feed.xmlUrl.startsWith("http://", ignoreCase = true)) {
                                "https://" + feed.xmlUrl.substring(7)
                            } else {
                                feed.xmlUrl
                            }
                            val firstAttempt = executeFeedRequest(initialUrl)
                            val attempt = if (firstAttempt.response == null && initialUrl != feed.xmlUrl) {
                                executeFeedRequest(feed.xmlUrl)
                            } else {
                                firstAttempt
                            }
                            val res = attempt.response
                            if (res == null) {
                                val err = attempt.error ?: "Sync failed"
                                dao.updateFeedError(feed.id, err)
                                failedCount.incrementAndGet()
                                AuditLog.error(appContext, "SYNC", "${feed.title}: $err")
                                return@runCatching
                            }
                            res.use { okRes ->
                                val bodyStream = okRes.body?.byteStream()
                                if (bodyStream == null) {
                                    dao.updateFeedError(feed.id, "Empty response")
                                    failedCount.incrementAndGet()
                                    AuditLog.error(appContext, "SYNC", "${feed.title}: Empty response")
                                    return@use
                                }
                                val finalUrl = okRes.request.url.toString()
                                val upgradedUrl = when {
                                    finalUrl.startsWith("https://", ignoreCase = true) -> finalUrl
                                    initialUrl != feed.xmlUrl && okRes.request.url.isHttps -> initialUrl
                                    else -> null
                                }
                                if (upgradedUrl != null && upgradedUrl != feed.xmlUrl) {
                                    runCatching { dao.updateFeedUrl(feed.id, upgradedUrl) }
                                }
                                val parsed = FeedParser.parse(bodyStream, upgradedUrl ?: feed.xmlUrl)
                                val resolvedTitle = parsed.title.ifBlank { feed.title }
                                dao.updateFeedMetadata(
                                    feedId = feed.id,
                                    title = resolvedTitle,
                                    htmlUrl = parsed.siteUrl.ifBlank { feed.htmlUrl },
                                    fetchedAt = System.currentTimeMillis(),
                                )
                                val articles = parsed.articles.map {
                                    ArticleEntity(
                                        feedId = feed.id,
                                        externalId = "${feed.id}:${it.externalId}",
                                        title = it.title,
                                        link = it.link,
                                        imageUrl = it.imageUrl,
                                        publishedAt = it.publishedAt,
                                    )
                                }
                                dao.insertArticles(articles)
                                syncedCount.incrementAndGet()
                            }
                        }
                        result.onFailure { err ->
                            val msg = err.message?.take(60) ?: err.javaClass.simpleName
                            dao.updateFeedError(feed.id, msg)
                            failedCount.incrementAndGet()
                            AuditLog.error(appContext, "SYNC", "${feed.title}: $msg")
                        }
                    }
                }
            }.awaitAll()
        }
        val synced = syncedCount.get()
        val failed = failedCount.get()
        if (failed == 0) {
            AuditLog.success(appContext, "SYNC", "Synced $synced/${feeds.size} feeds")
        } else {
            AuditLog.info(appContext, "SYNC", "Synced $synced/${feeds.size} feeds ($failed failed)")
        }
        synced
    }

    private data class FeedFetchAttempt(
        val response: okhttp3.Response?,
        val error: String?,
    )

    private fun executeFeedRequest(url: String): FeedFetchAttempt = try {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "KlaatuRSS/0.1 (Android)")
            .build()
        val response = httpClient.newCall(request).execute()
        if (response.isSuccessful) {
            FeedFetchAttempt(response = response, error = null)
        } else {
            val code = response.code
            response.close()
            FeedFetchAttempt(response = null, error = "HTTP $code")
        }
    } catch (e: Exception) {
        val reason = e.javaClass.simpleName.removeSuffix("Exception").ifBlank { "Network error" }
        FeedFetchAttempt(response = null, error = reason)
    }

    suspend fun getWidgetArticles(
        categories: Set<String>,
        feedIds: Set<Long>,
        limit: Int = 40,
    ): List<ArticleWithFeed> = withContext(Dispatchers.IO) {
        val showAll = if (categories.isEmpty() && feedIds.isEmpty()) 1 else 0
        dao.getFilteredArticles(
            showAll = showAll,
            categories = categories.ifEmpty { setOf("") }.toList(),
            feedIds = feedIds.ifEmpty { setOf(-1L) }.toList(),
            limit = limit,
        )
    }

    suspend fun getWidgetArticleCount(
        categories: Set<String>,
        feedIds: Set<Long>,
    ): Int = withContext(Dispatchers.IO) {
        val showAll = if (categories.isEmpty() && feedIds.isEmpty()) 1 else 0
        dao.getFilteredArticleCount(
            showAll = showAll,
            categories = categories.ifEmpty { setOf("") }.toList(),
            feedIds = feedIds.ifEmpty { setOf(-1L) }.toList(),
        )
    }

    /**
     * Prefetches thumbnails for the most recent articles into Coil's disk cache so
     * widget updates can render images immediately without blocking on the network.
     */
    suspend fun prefetchRecentThumbnails(limit: Int = 40): Int = withContext(Dispatchers.IO) {
        val urls = dao.getRecentImageUrls(limit)
        prefetchThumbnails(urls)
    }

    /**
     * Fetches the <head> (up to 48 KB) of article pages that lack an RSS imageUrl,
     * extracts og:image / twitter:image, persists it to Room, and returns the found URLs.
     */
    suspend fun scrapeArticleImages(articles: List<Pair<Long, String>>): List<String> =
        withContext(Dispatchers.IO) {
            val candidates = articles
                .filter { (_, link) -> link.startsWith("http", ignoreCase = true) }
                .distinctBy { it.first }
                .take(15)
            if (candidates.isEmpty()) return@withContext emptyList()

            val semaphore = Semaphore(4)
            coroutineScope {
                candidates.map { (articleId, link) ->
                    async {
                        semaphore.withPermit {
                            withTimeoutOrNull(4_000L) {
                                runCatching {
                                    val request = Request.Builder()
                                        .url(link)
                                        .header("User-Agent", "KlaatuRSS/0.1 (Android)")
                                        .build()
                                    httpClient.newCall(request).execute().use { res ->
                                        if (!res.isSuccessful) return@use null
                                        val contentType = res.header("Content-Type").orEmpty()
                                        if (contentType.isNotEmpty() && !contentType.contains("html", ignoreCase = true)) {
                                            return@use null
                                        }
                                        val source = res.body?.source() ?: return@use null
                                        val head = readHeadSnippet(source)
                                        val rawImageUrl = extractMetaImageUrl(head) ?: return@use null
                                        val resolved = res.request.url.resolve(rawImageUrl)?.toString()
                                            ?: rawImageUrl.takeIf { it.startsWith("http", ignoreCase = true) }
                                            ?: return@use null
                                        dao.updateArticleImageUrl(articleId, resolved)
                                        resolved
                                    }
                                }.getOrNull()
                            }
                        }
                    }
                }.awaitAll().filterNotNull()
            }
        }

    private fun readHeadSnippet(source: okio.BufferedSource, maxBytes: Long = 49_152L): String {
        val buffer = okio.Buffer()
        var total = 0L
        while (total < maxBytes) {
            val read = source.read(buffer, minOf(8_192L, maxBytes - total))
            if (read == -1L) break
            total += read
            val current = buffer.clone().readUtf8()
            if (current.contains("</head>", ignoreCase = true)) {
                return current
            }
        }
        return buffer.readUtf8()
    }

    private fun extractMetaImageUrl(html: String): String? {
        for (match in META_TAG_REGEX.findAll(html)) {
            val tag = match.value
            val key = META_KEY_REGEX.find(tag)?.groupValues?.getOrNull(1)?.lowercase() ?: continue
            if (key == "og:image" || key == "twitter:image" || key == "twitter:image:src") {
                val content = META_CONTENT_REGEX.find(tag)?.groupValues?.getOrNull(1)?.trim()
                if (!content.isNullOrEmpty()) return content
            }
        }
        return null
    }

    private companion object {
        val META_TAG_REGEX = Regex("""<meta\b[^>]*>""", RegexOption.IGNORE_CASE)
        val META_KEY_REGEX = Regex("""(?:property|name)\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        val META_CONTENT_REGEX = Regex("""content\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    }

    /**
     * Downloads and caches the given image URLs at widget thumbnail dimensions.
     * Returns the number of URLs that resolved successfully.
     */
    suspend fun prefetchThumbnails(
        urls: List<String>,
        cachedCounter: AtomicInteger = AtomicInteger(0),
    ): Int = withContext(Dispatchers.IO) {
        val distinctUrls = urls.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(40)
            .toList()
        if (distinctUrls.isEmpty()) return@withContext 0

        val imageLoader = appContext.imageLoader
        val semaphore = Semaphore(6)

        coroutineScope {
            distinctUrls.map { url ->
                async {
                    if (WidgetThumbnailStore.isCached(appContext, url)) return@async
                    semaphore.withPermit {
                        val saved = withTimeoutOrNull(4_000L) {
                            runCatching {
                                val request = ImageRequest.Builder(appContext)
                                    .data(url)
                                    .size(WidgetThumbnailStore.THUMB_WIDTH, WidgetThumbnailStore.THUMB_HEIGHT)
                                    .allowHardware(false)
                                    .memoryCachePolicy(CachePolicy.DISABLED)
                                    .build()
                                val result = imageLoader.execute(request) as? SuccessResult
                                val bitmap = (result?.drawable as? BitmapDrawable)?.bitmap
                                bitmap != null && WidgetThumbnailStore.saveBitmap(appContext, url, bitmap)
                            }.getOrDefault(false)
                        } == true
                        if (saved) cachedCounter.incrementAndGet()
                    }
                }
            }.awaitAll()
        }
        WidgetThumbnailStore.trimCache(appContext)
        cachedCounter.get()
    }
}
