package com.klaatu.rss.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Stores resized widget thumbnails as JPEGs in cacheDir/widget_thumbs and serves them
 * via FileProvider content:// URIs so RemoteViews never serialize raw Bitmap bytes over Binder.
 */
object WidgetThumbnailStore {

    const val THUMB_WIDTH = 216
    const val THUMB_HEIGHT = 148
    private const val RENDER_WIDTH = 108
    private const val RENDER_HEIGHT = 74
    private const val MIN_CACHED_FILES = 400
    private const val FILES_PER_WIDGET = 150

    private const val THUMBS_DIR = "widget_thumbs"
    private const val JPEG_QUALITY = 82
    private const val TOUCH_INTERVAL_MS = 60 * 60 * 1_000L

    private val grantedUriKeys = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var cachedLauncherPackages: List<String>? = null

    fun isCached(context: Context, url: String): Boolean {
        val file = fileForUrl(context, url)
        return file.exists() && file.length() > 0L
    }

    fun saveBitmap(context: Context, url: String, bitmap: Bitmap): Boolean {
        val target = fileForUrl(context, url)
        val tmp = File(target.parentFile, "${target.name}.tmp")
        return runCatching {
            FileOutputStream(tmp).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            }
            if (tmp.length() > 0L && (tmp.renameTo(target) || (target.delete() && tmp.renameTo(target)))) {
                true
            } else {
                tmp.delete()
                false
            }
        }.getOrElse {
            tmp.delete()
            false
        }
    }

    fun loadBitmapIfCached(context: Context, url: String?): Bitmap? {
        if (url.isNullOrBlank()) return null
        val file = fileForUrl(context, url)
        if (!file.exists() || file.length() <= 0L) return null

        val now = System.currentTimeMillis()
        if (now - file.lastModified() > TOUCH_INTERVAL_MS) {
            file.setLastModified(now)
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = 2
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return runCatching {
            val decoded = BitmapFactory.decodeFile(file.absolutePath, options) ?: return@runCatching null
            if (decoded.width > RENDER_WIDTH + 8 || decoded.height > RENDER_HEIGHT + 8) {
                Bitmap.createScaledBitmap(decoded, RENDER_WIDTH, RENDER_HEIGHT, true).also {
                    if (it !== decoded) decoded.recycle()
                }
            } else {
                decoded
            }
        }.getOrNull()
    }

    fun getContentUriIfCached(context: Context, url: String?): Uri? {
        if (url.isNullOrBlank()) return null
        val file = fileForUrl(context, url)
        if (!file.exists() || file.length() <= 0L) return null

        val now = System.currentTimeMillis()
        if (now - file.lastModified() > TOUCH_INTERVAL_MS) {
            file.setLastModified(now)
        }

        val uri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }.getOrNull() ?: return null

        grantReadToLaunchers(context, uri)
        return uri
    }

    fun trimCache(context: Context) {
        val dir = thumbsDir(context)
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".jpg") } ?: return
        val maxFiles = maxCachedFiles(context)
        if (files.size <= maxFiles) return

        files.sortBy { it.lastModified() }
        val toDelete = files.size - maxFiles
        for (i in 0 until toDelete) {
            files[i].delete()
        }
    }

    fun maxCachedFiles(context: Context): Int {
        val widgetCount = runCatching {
            AppWidgetManager.getInstance(context)
                .getAppWidgetIds(ComponentName(context, KlaatuWidgetReceiver::class.java))
                .size
        }.getOrDefault(1)
        return maxOf(MIN_CACHED_FILES, widgetCount * FILES_PER_WIDGET)
    }

    private fun grantReadToLaunchers(context: Context, uri: Uri) {
        val uriKey = uri.toString()
        val packages = launcherPackages(context)
        for (pkg in packages) {
            val cacheKey = "$pkg|$uriKey"
            if (grantedUriKeys.add(cacheKey)) {
                runCatching {
                    context.grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
        }
    }

    private fun launcherPackages(context: Context): List<String> {
        cachedLauncherPackages?.let { return it }
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolved = context.packageManager
            .queryIntentActivities(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
            .mapNotNull { it.activityInfo?.packageName }
        val packages = (resolved + "com.android.systemui").distinct()
        cachedLauncherPackages = packages
        return packages
    }

    private fun thumbsDir(context: Context): File =
        File(context.cacheDir, THUMBS_DIR).apply { mkdirs() }

    private fun fileForUrl(context: Context, url: String): File =
        File(thumbsDir(context), "${sha256(url)}.jpg")

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
