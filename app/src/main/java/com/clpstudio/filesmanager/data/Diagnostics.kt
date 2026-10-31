package com.clpstudio.filesmanager.data

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * In-app diagnostics log (Settings → About → Diagnostics). Kept on the phone only: the app has no
 * network permission. The last [MAX_LINES] lines live in memory and in a small file in the app's
 * private storage, so they survive a restart.
 */
object Diagnostics {
    const val MAX_LINES = 300
    private const val MAX_FILE_BYTES = 128 * 1024
    private val lines = ArrayDeque<String>()
    private var file: File? = null
    private val fmt = ThreadLocal.withInitial { SimpleDateFormat("dd/MM HH:mm:ss.SSS", Locale.US) }

    fun init(context: Context) {
        val f = File(context.filesDir, "diagnostics.log")
        file = f
        runCatching {
            if (f.exists()) {
                val old = f.readLines().takeLast(MAX_LINES)
                synchronized(lines) { old.forEach { add(it) } }
            }
        }
    }

    fun log(tag: String, message: String, error: Throwable? = null) {
        val line = "${fmt.get()!!.format(Date())} [$tag] $message" + (error?.let { " — ${it.javaClass.simpleName}: ${it.message}" } ?: "")
        runCatching { if (error != null) Log.w("Files", line, error) else Log.i("Files", line) }
        synchronized(lines) { add(line) }
        val f = file ?: return
        runCatching {
            if (f.length() > MAX_FILE_BYTES) f.writeText(snapshot().joinToString("\n", postfix = "\n"))
            else f.appendText(line + "\n")
        }
    }

    private fun add(line: String) {
        lines.addLast(line)
        while (lines.size > MAX_LINES) lines.removeFirst()
    }

    fun snapshot(): List<String> = synchronized(lines) { lines.toList() }

    fun clear() {
        synchronized(lines) { lines.clear() }
        runCatching { file?.writeText("") }
    }
}
