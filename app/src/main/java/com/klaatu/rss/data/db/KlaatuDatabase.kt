package com.klaatu.rss.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [FeedEntity::class, ArticleEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class KlaatuDatabase : RoomDatabase() {
    abstract fun dao(): KlaatuDao

    companion object {
        @Volatile
        private var instance: KlaatuDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE feeds ADD COLUMN lastError TEXT NOT NULL DEFAULT ''")
            }
        }

        fun getInstance(context: Context): KlaatuDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    KlaatuDatabase::class.java,
                    "klaatu.db",
                ).addMigrations(MIGRATION_1_2).build().also { instance = it }
            }
    }
}
