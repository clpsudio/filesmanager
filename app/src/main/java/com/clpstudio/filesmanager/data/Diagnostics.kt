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
    /** Settings > Logs (item 38): the longer rolling log (about 2 MB), app start, errors and crashes. */
    @Volatile var rolling: RollingLog? = null
        private set
    private val fullFmt = ThreadLocal.withInitial { SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US) }
    private val fmt = ThreadLocal.withInitial { SimpleDateFormat("dd/MM HH:mm:ss.SSS", Locale.US) }

    fun init(context: Context) {
        val f = File(context.filesDir, "diagnostics.log")
        file = f
        rolling = RollingLog(logsDir(context))
        rolling?.append(
            "${fullFmt.get()!!.format(Date())} [app] App start — Files ${com.clpstudio.filesmanager.BuildConfig.VERSION_NAME} " +
                "(${com.clpstudio.filesmanager.BuildConfig.VERSION_CODE}), Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT}), " +
                "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
        )
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
        rolling?.append(
            "${fullFmt.get()!!.format(Date())} [$tag] $message" +
                (error?.let { "\n" + Log.getStackTraceString(it).lines().take(30).joinToString("\n") { l -> "    $l" } } ?: ""),
        )
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

    /** Latest "first items" time per category (ms, item count), shown on the diagnostics screen. */
    val timings = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Int>>()

    fun timing(name: String, ms: Long, count: Int) {
        timings[name] = ms to count
        log("speed", "$name: first $count items in $ms ms")
    }

    /** Time from opening a category to its list on screen (from the cache or fresh). */
    fun shown(name: String, ms: Long, fromCache: Boolean) {
        timings["$name (on screen" + (if (fromCache) ", cached)" else ")")] = ms to -1
    }

    fun snapshot(): List<String> = synchronized(lines) { lines.toList() }

    fun clear() {
        synchronized(lines) { lines.clear() }
        runCatching { file?.writeText("") }
        rolling?.deleteAll()
    }

    fun logsDir(context: Context): File = File(context.filesDir, "logs")

    /** An uncaught crash, written before the app closes (item 38). */
    fun crash(threadName: String, error: Throwable) {
        rolling?.append("${fullFmt.get()!!.format(Date())} [CRASH] on thread $threadName\n" + Log.getStackTraceString(error).trimEnd())
    }
}
