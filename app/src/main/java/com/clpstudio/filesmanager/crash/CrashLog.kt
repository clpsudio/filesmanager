package com.clpstudio.filesmanager.crash

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import com.clpstudio.filesmanager.BuildConfig
import com.clpstudio.filesmanager.data.Diagnostics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

/** One saved crash report (filesDir/crash/<timestamp>.txt). */
data class CrashReport(val file: File, val time: Long, val title: String)

/**
 * Local, private crash log. Nothing leaves the phone unless the person taps Share.
 * The default uncaught-exception handler writes the stack trace with the device and app version
 * to filesDir/crash/<timestamp>.txt (last [KEEP] kept), then lets the app die normally.
 * On the next launch the app shows a one-time notice (see [CrashNoticeHost]).
 */
object CrashLog {
    const val KEEP = 10
    private const val PENDING = ".pending"
    /** A share intent's text has to fit in a Binder transaction (1 MB for the whole intent). */
    private const val SHARE_MAX_CHARS = 90_000

    @Volatile private var dir: File? = null
    @Volatile private var installed = false

    fun dir(context: Context): File = File(context.filesDir, "crash")

    /** Call first thing in Application.onCreate. Idempotent. */
    fun install(context: Context) {
        val d = dir(context.applicationContext)
        dir = d
        if (installed) return
        installed = true
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                runCatching { com.clpstudio.filesmanager.data.Diagnostics.crash(thread.name, error) }
                write(d, buildReport(thread.name, error, System.currentTimeMillis(), deviceLines()), System.currentTimeMillis())
            } catch (_: Throwable) {
                // Never let the crash logger itself get in the way of the crash.
            }
            if (previous != null) {
                previous.uncaughtException(thread, error)
            } else {
                Process.killProcess(Process.myPid())
                exitProcess(10)
            }
        }
    }

    /**
     * For background coroutines (Graph.appScope and loaders): an escaped exception is logged
     * (Diagnostics + a crash-style report marked non-fatal) instead of killing the app.
     */
    val coroutineHandler = CoroutineExceptionHandler { _, error ->
        if (error is CancellationException) return@CoroutineExceptionHandler
        nonFatal("coroutine", error)
    }

    /** Logs a caught exception to Diagnostics (not shown as a crash). */
    fun nonFatal(tag: String, error: Throwable) {
        runCatching { Diagnostics.log(tag, "caught", error) }
    }

    fun deviceLines(): List<String> = listOf(
        "App: Files ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) ${BuildConfig.BUILD_TYPE}",
        "Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})",
        "Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        "ABI: ${Build.SUPPORTED_ABIS.joinToString()}",
    )

    /** The report text: header, device/app lines, then the full stack trace (causes included). */
    fun buildReport(threadName: String, error: Throwable, now: Long, device: List<String>): String {
        val sw = StringWriter()
        PrintWriter(sw).use { error.printStackTrace(it) }
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date(now))
        return buildString {
            append("Files crash report\n")
            append("Time: ").append(stamp).append('\n')
            device.forEach { append(it).append('\n') }
            append("Thread: ").append(threadName).append('\n')
            append('\n')
            append(sw.toString())
        }
    }

    private fun fileName(now: Long): String = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss-SSS", Locale.US).format(Date(now))

    /** Writes [text] as a new report in [dir], marks the next launch to show the notice, keeps the last [keep]. */
    fun write(dir: File, text: String, now: Long, keep: Int = KEEP): File {
        dir.mkdirs()
        val base = fileName(now)
        var f = File(dir, "$base.txt")
        var n = 1
        while (f.exists()) f = File(dir, "$base-${n++}.txt")
        f.writeText(text)
        runCatching { File(dir, PENDING).writeText(f.name) }
        rotate(dir, keep)
        return f
    }

    /** Deletes the oldest reports so at most [keep] stay. Names sort by time. */
    fun rotate(dir: File, keep: Int = KEEP) {
        val files = reportFiles(dir)
        if (files.size <= keep) return
        files.sortedBy { it.name }.take(files.size - keep).forEach { it.delete() }
    }

    private fun reportFiles(dir: File): List<File> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(".txt") }?.toList().orEmpty()

    /** Newest first. */
    fun list(dir: File): List<CrashReport> = reportFiles(dir)
        .sortedByDescending { it.name }
        .map { f -> CrashReport(f, f.lastModified(), titleOf(runCatching { f.readText() }.getOrDefault(""))) }

    fun list(context: Context): List<CrashReport> = list(dir(context))

    /** The first line of the stack trace ("java.lang.IllegalStateException: ..."), shortened. */
    fun titleOf(report: String): String {
        val lines = report.lines()
        val start = lines.indexOfFirst { it.isBlank() }
        val first = lines.drop(if (start >= 0) start + 1 else 0).firstOrNull { it.isNotBlank() } ?: "Crash"
        return first.trim().take(160)
    }

    fun clear(context: Context) {
        val d = dir(context)
        reportFiles(d).forEach { it.delete() }
        File(d, PENDING).delete()
    }

    fun delete(report: CrashReport) {
        report.file.delete()
    }

    /** True once after a crash: the next launch shows the notice. */
    fun consumePendingNotice(context: Context): Boolean = consumePendingNotice(dir(context))

    fun consumePendingNotice(dir: File): Boolean {
        val marker = File(dir, PENDING)
        if (!marker.exists()) return false
        marker.delete()
        return true
    }

    /** ACTION_SEND text (WhatsApp, mail...), via the system chooser. */
    fun shareIntent(report: CrashReport): Intent {
        val text = runCatching { report.file.readText() }.getOrDefault("(report could not be read)")
        val body = if (text.length > SHARE_MAX_CHARS) text.take(SHARE_MAX_CHARS) + "\n…(cut)" else text
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Files crash report ${report.file.nameWithoutExtension}")
            putExtra(Intent.EXTRA_TEXT, body)
        }
        return Intent.createChooser(send, "Share crash report")
    }
}
