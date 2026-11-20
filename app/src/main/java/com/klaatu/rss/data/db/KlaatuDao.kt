package com.klaatu.rss.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

data class FeedLatestArticle(
    val feedId: Long,
    val latestPublishedAt: Long,
)

@Dao
interface KlaatuDao {

    @Query("SELECT * FROM feeds ORDER BY category ASC, title ASC")
    fun observeFeeds(): Flow<List<FeedEntity>>

    @Query("SELECT * FROM feeds ORDER BY category ASC, title ASC")
    suspend fun getAllFeeds(): List<FeedEntity>

    @Query("SELECT DISTINCT category FROM feeds ORDER BY category ASC")
    fun observeCategories(): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertFeeds(feeds: List<FeedEntity>): List<Long>

    @Query("UPDATE feeds SET title = :title, htmlUrl = :htmlUrl, lastFetchedAt = :fetchedAt, lastError = '' WHERE id = :feedId")
    suspend fun updateFeedMetadata(feedId: Long, title: String, htmlUrl: String, fetchedAt: Long)

    @Query("UPDATE feeds SET lastError = :error WHERE id = :feedId")
    suspend fun updateFeedError(feedId: Long, error: String)

    @Query("UPDATE feeds SET xmlUrl = :xmlUrl WHERE id = :feedId")
    suspend fun updateFeedUrl(feedId: Long, xmlUrl: String)

    @Query("UPDATE feeds SET category = :category, title = :title WHERE xmlUrl = :xmlUrl")
    suspend fun updateFeedCategoryByUrl(xmlUrl: String, category: String, title: String)

    @Query("DELETE FROM feeds WHERE id = :feedId")
    suspend fun deleteFeed(feedId: Long)

    @Query("DELETE FROM feeds WHERE category = :category")
    suspend fun deleteFeedsByCategory(category: String)

    @Query("DELETE FROM feeds WHERE lastError != ''")
    suspend fun deleteBrokenFeeds()

    @Query("DELETE FROM feeds WHERE id IN (:feedIds)")
    suspend fun deleteFeedsByIds(feedIds: List<Long>)

    @Query("SELECT feedId, MAX(publishedAt) AS latestPublishedAt FROM articles GROUP BY feedId")
    fun observeFeedLatestArticleTimes(): Flow<List<FeedLatestArticle>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertArticles(articles: List<ArticleEntity>)

    @Query("UPDATE articles SET imageUrl = :imageUrl WHERE id = :articleId")
    suspend fun updateArticleImageUrl(articleId: Long, imageUrl: String)

    @Query(
        """
        SELECT a.id, a.title, a.link, a.imageUrl, a.publishedAt, f.title AS feedTitle, f.category AS category
        FROM articles a
        INNER JOIN feeds f ON a.feedId = f.id
        WHERE (:showAll = 1 OR f.category IN (:categories) OR a.feedId IN (:feedIds))
        ORDER BY a.publishedAt DESC
        LIMIT :limit
        """
    )
    suspend fun getFilteredArticles(
        showAll: Int,
        categories: List<String>,
        feedIds: List<Long>,
        limit: Int = 40,
    ): List<ArticleWithFeed>

    @Query(
        """
        SELECT COUNT(*)
        FROM articles a
        INNER JOIN feeds f ON a.feedId = f.id
        WHERE (:showAll = 1 OR f.category IN (:categories) OR a.feedId IN (:feedIds))
        """
    )
    suspend fun getFilteredArticleCount(
        showAll: Int,
        categories: List<String>,
        feedIds: List<Long>,
    ): Int

    @Query(
        """
        SELECT a.id, a.title, a.link, a.imageUrl, a.publishedAt, f.title AS feedTitle, f.category AS category
        FROM articles a
        INNER JOIN feeds f ON a.feedId = f.id
        WHERE (:category IS NULL OR f.category = :category)
        ORDER BY a.publishedAt DESC
        LIMIT :limit
        """
    )
    fun observeArticles(category: String?, limit: Int = 150): Flow<List<ArticleWithFeed>>

    @Query("SELECT COUNT(*) FROM articles")
    fun observeTotalArticleCount(): Flow<Int>

    @Query(
        """
        SELECT DISTINCT imageUrl
        FROM articles
        WHERE imageUrl IS NOT NULL AND imageUrl != ''
        ORDER BY publishedAt DESC
        LIMIT :limit
        """
    )
    suspend fun getRecentImageUrls(limit: Int = 40): List<String>
}
