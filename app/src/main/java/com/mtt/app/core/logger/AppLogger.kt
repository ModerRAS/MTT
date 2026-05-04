package com.mtt.app.core.logger

import android.app.Application
import android.util.Log
import com.mtt.app.BuildConfig
import timber.log.Timber
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Application logger using Timber.
 *
 * - DEBUG: Logcat logging with full verbose output
 * - RELEASE: File logging with warn+ only
 * - NEVER logs API keys, source text, or LLM responses
 */
object AppLogger {

    private const val TAG_PREFIX = "MTT"
    private const val LOG_DIR = "logs"
    private const val MAX_LOG_SIZE = 5 * 1024 * 1024L // 5MB

    private var isInitialized = false

    fun init(application: Application) {
        if (isInitialized) return
        isInitialized = true

        val tree = if (BuildConfig.DEBUG) {
            DebugTree()
        } else {
            ReleaseTree(application)
        }
        Timber.plant(tree)
    }

    fun d(tag: String, message: String) {
        Timber.tag("$TAG_PREFIX-$tag").d(message)
    }

    fun i(tag: String, message: String) {
        Timber.tag("$TAG_PREFIX-$tag").i(message)
    }

    fun w(tag: String, message: String) {
        Timber.tag("$TAG_PREFIX-$tag").w(message)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        Timber.tag("$TAG_PREFIX-$tag").e(throwable, message)
    }

    /**
     * Debug tree: Logcat with verbose output
     */
    private class DebugTree : Timber.DebugTree() {
        override fun createStackElementTag(element: StackTraceElement): String {
            return super.createStackElementTag(element)?.substringAfterLast('.') ?: element.className
        }
    }

    /**
     * Release tree: File logging with warn+ only
     */
    private class ReleaseTree(private val application: Application) : Timber.Tree() {

        private val logFile: File
            get() {
                val dir = File(application.filesDir, LOG_DIR)
                if (!dir.exists()) dir.mkdirs()
                return File(dir, "mtt.log")
            }

        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            if (priority < Log.WARN) return

            val timestamp = java.text.SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss.SSS",
                java.util.Locale.getDefault()
            ).format(java.util.Date())

            val level = when (priority) {
                Log.ERROR -> "E"
                Log.WARN -> "W"
                else -> "I"
            }

            val logEntry = buildString {
                append("[$timestamp] $level/$tag: $message")
                t?.let {
                    append("\n")
                    append(getStackTraceString(it))
                }
                append("\n")
            }

            writeToFile(logEntry)
        }

        private fun writeToFile(entry: String) {
            try {
                val file = logFile
                // Rotate if too large
                if (file.exists() && file.length() > MAX_LOG_SIZE) {
                    val backup = File(file.parent, "mtt.log.old")
                    file.renameTo(backup)
                }
                file.appendText(entry)
            } catch (_: Exception) {
                // Silently fail to avoid crashes
            }
        }

        private fun getStackTraceString(t: Throwable): String {
            val sw = StringWriter()
            t.printStackTrace(PrintWriter(sw))
            return sw.toString()
        }
    }
}