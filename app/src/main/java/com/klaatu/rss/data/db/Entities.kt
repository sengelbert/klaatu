package com.klaatu.rss.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "feeds",
    indices = [Index(value = ["xmlUrl"], unique = true)],
)
data class FeedEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val xmlUrl: String,
    val htmlUrl: String = "",
    val category: String = "Uncategorized",
    val lastFetchedAt: Long = 0L,
    val lastError: String = "",
)

@Entity(
    tableName = "articles",
    foreignKeys = [
        ForeignKey(
            entity = FeedEntity::class,
            parentColumns = ["id"],
            childColumns = ["feedId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["feedId"]),
        Index(value = ["externalId"], unique = true),
        Index(value = ["publishedAt"]),
    ],
)
data class ArticleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val feedId: Long,
    val externalId: String,
    val title: String,
    val link: String,
    val imageUrl: String?,
    val publishedAt: Long,
)

data class ArticleWithFeed(
    val id: Long,
    val title: String,
    val link: String,
    val imageUrl: String?,
    val publishedAt: Long,
    val feedTitle: String,
    val category: String,
)
