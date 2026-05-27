package com.cromacompany.odo

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class EventLogger private constructor(private val context: Context) {
    private val logFile: File
        get() {
            val directory = File(context.filesDir, "logs")
            if (!directory.exists()) directory.mkdirs()
            return File(directory, LOG_FILE_NAME)
        }

    fun event(message: String, details: Map<String, Any?> = emptyMap()) {
        append("EVENT", message, details)
    }

    fun error(message: String, throwable: Throwable? = null, details: Map<String, Any?> = emptyMap()) {
        val errorDetails = if (throwable == null) {
            details
        } else {
            details + mapOf(
                "errorType" to throwable::class.java.name,
                "errorMessage" to throwable.message,
                "stackTrace" to throwable.stackTraceString(),
            )
        }
        append("ERROR", message, errorDetails)
    }

    fun shareIntent(): Intent {
        ensureFileExists()
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            logFile,
        )
        return Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "Odo events and errors")
            .putExtra(Intent.EXTRA_TEXT, "Odo events and errors log")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun fileSizeLabel(): String {
        ensureFileExists()
        val bytes = logFile.length()
        return when {
            bytes >= 1_048_576 -> String.format(Locale.ENGLISH, "%.1f MB", bytes / 1_048_576f)
            bytes >= 1_024 -> String.format(Locale.ENGLISH, "%.1f KB", bytes / 1_024f)
            else -> "$bytes B"
        }
    }

    fun clear() {
        logFile.writeText("", Charsets.UTF_8)
        event("Log cleared")
    }

    private fun append(level: String, message: String, details: Map<String, Any?>) {
        runCatching {
            val line = buildString {
                append(timestamp())
                append(" [")
                append(level)
                append("] ")
                append(message)
                if (details.isNotEmpty()) {
                    append(" | ")
                    append(details.entries.joinToString(separator = "; ") { (key, value) ->
                        "$key=${value.toString().replace('\n', ' ')}"
                    })
                }
                append('\n')
            }
            logFile.appendText(line, Charsets.UTF_8)
        }
    }

    private fun ensureFileExists() {
        if (!logFile.exists()) {
            logFile.writeText("${timestamp()} [EVENT] Log created\n", Charsets.UTF_8)
        }
    }

    private fun timestamp(): String = FORMATTER.format(Instant.now().atZone(ZoneId.systemDefault()))

    private fun Throwable.stackTraceString(): String {
        val writer = StringWriter()
        printStackTrace(PrintWriter(writer))
        return writer.toString()
    }

    companion object {
        private const val LOG_FILE_NAME = "odo-events.txt"
        private val FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS z", Locale.ENGLISH)

        @Volatile
        private var instance: EventLogger? = null

        fun get(context: Context): EventLogger {
            return instance ?: synchronized(this) {
                instance ?: EventLogger(context.applicationContext).also { instance = it }
            }
        }
    }
}
