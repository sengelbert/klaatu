package com.klaatu.rss.data

import android.content.Context
import org.json.JSONObject
import java.io.File

enum class AuditLevel {
    INFO,
    SUCCESS,
    ERROR,
}

data class AuditEntry(
    val timestamp: Long,
    val level: AuditLevel,
    val category: String,
    val message: String,
)

/**
 * Lightweight persistent ring-buffer audit log stored in filesDir/audit_log.jsonl.
 * Capped at MAX_ENTRIES so it never grows unbounded.
 */
object AuditLog {

    private const val FILE_NAME = "audit_log.jsonl"
    private const val MAX_ENTRIES = 300
    private val lock = Any()

    fun info(context: Context, category: String, message: String) =
        append(context, AuditLevel.INFO, category, message)

    fun success(context: Context, category: String, message: String) =
        append(context, AuditLevel.SUCCESS, category, message)

    fun error(context: Context, category: String, message: String) =
        append(context, AuditLevel.ERROR, category, message)

    fun readAll(context: Context): List<AuditEntry> = synchronized(lock) {
        val file = logFile(context)
        if (!file.exists()) return emptyList()
        runCatching {
            file.readLines()
                .asReversed()
                .mapNotNull { line ->
                    if (line.isBlank()) return@mapNotNull null
                    runCatching {
                        val obj = JSONObject(line)
                        AuditEntry(
                            timestamp = obj.optLong("ts", 0L),
                            level = runCatching {
                                AuditLevel.valueOf(obj.optString("lvl", "INFO"))
                            }.getOrDefault(AuditLevel.INFO),
                            category = obj.optString("cat", "APP"),
                            message = obj.optString("msg", ""),
                        )
                    }.getOrNull()
                }
        }.getOrDefault(emptyList())
    }

    fun clear(context: Context) {
        synchronized(lock) {
            logFile(context).delete()
        }
    }

    private fun append(context: Context, level: AuditLevel, category: String, message: String) {
        val entry = JSONObject().apply {
            put("ts", System.currentTimeMillis())
            put("lvl", level.name)
            put("cat", category)
            put("msg", message)
        }.toString()

        synchronized(lock) {
            runCatching {
                val file = logFile(context)
                val existing = if (file.exists()) file.readLines() else emptyList()
                val updated = (existing + entry).takeLast(MAX_ENTRIES)
                file.writeText(updated.joinToString("\n") + "\n")
            }
        }
    }

    private fun logFile(context: Context): File =
        File(context.applicationContext.filesDir, FILE_NAME)
}
