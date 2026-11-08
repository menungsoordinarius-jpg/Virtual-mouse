package com.sanny.pointertest

import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Logger {
    private val mainHandler: Handler? by lazy {
        try {
            val looper = Looper.getMainLooper()
            if (looper != null) Handler(looper) else null
        } catch (_: Throwable) {
            null
        }
    }

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    private val logEntries = mutableListOf<String>()
    private var listener: ((List<String>) -> Unit)? = null

    @Synchronized
    fun log(tag: String, message: String) {
        val timestamp = timeFormat.format(Date())
        val entry = "[$timestamp] [$tag] $message"
        logEntries.add(entry)
        if (logEntries.size > 1000) {
            logEntries.removeAt(0)
        }
        val copy = logEntries.toList()
        val handler = mainHandler
        if (handler != null) {
            handler.post {
                listener?.invoke(copy)
            }
        } else {
            listener?.invoke(copy)
        }
    }

    @Synchronized
    fun getLogs(): String {
        return logEntries.joinToString("\n")
    }

    @Synchronized
    fun clear() {
        logEntries.clear()
        val handler = mainHandler
        if (handler != null) {
            handler.post {
                listener?.invoke(emptyList())
            }
        } else {
            listener?.invoke(emptyList())
        }
    }

    @Synchronized
    fun setListener(callback: ((List<String>) -> Unit)?) {
        listener = callback
        val copy = logEntries.toList()
        val handler = mainHandler
        if (handler != null) {
            handler.post {
                listener?.invoke(copy)
            }
        } else {
            listener?.invoke(copy)
        }
    }
}
