package com.klaatu.rss.sync

import android.content.Context
import android.util.Log
import androidx.glance.appwidget.updateAll
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.klaatu.rss.data.FeedRepository
import com.klaatu.rss.widget.KlaatuWidget
import com.klaatu.rss.widget.WidgetPreferences
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

class FeedSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val repo = FeedRepository(applicationContext)
        Log.d("KlaatuSync", "FeedSyncWorker started")
        try {
            val synced = withTimeoutOrNull(25_000L) {
                repo.syncAllFeeds()
            }
            Log.d("KlaatuSync", "FeedSyncWorker finished, synced=$synced")
            withTimeoutOrNull(10_000L) {
                repo.prefetchRecentThumbnails()
            }
        } finally {
            WidgetPreferences.clearAllRefreshing(applicationContext)
            KlaatuWidget().updateAll(applicationContext)
        }
        return Result.success()
    }

    companion object {
        private const val PERIODIC_WORK_NAME = "klaatu_periodic_feed_sync"
        private const val IMMEDIATE_WORK_NAME = "klaatu_immediate_feed_sync"

        fun schedulePeriodic(
            context: Context,
            intervalMinutes: Long = WidgetPreferences.getRefreshIntervalMinutes(context),
        ) {
            if (intervalMinutes <= 0L) {
                WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK_NAME)
                return
            }
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = PeriodicWorkRequestBuilder<FeedSyncWorker>(
                intervalMinutes.coerceAtLeast(15L),
                TimeUnit.MINUTES,
            )
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }

        fun enqueueImmediate(context: Context) {
            val request = OneTimeWorkRequestBuilder<FeedSyncWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                IMMEDIATE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }
    }
}
