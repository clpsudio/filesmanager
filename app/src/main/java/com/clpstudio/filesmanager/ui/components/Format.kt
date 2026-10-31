package com.clpstudio.filesmanager.ui.components

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.log10
import kotlin.math.pow

fun formatSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val group = (log10(bytes.toDouble()) / log10(1024.0)).toInt().coerceIn(0, units.size - 1)
    val v = bytes / 1024.0.pow(group.toDouble())
    return if (group == 0) "$bytes B" else String.format(Locale.US, if (v >= 100) "%.0f %s" else "%.1f %s", v, units[group])
}

private val dateFmt = ThreadLocal.withInitial { SimpleDateFormat("dd MMM yyyy", Locale.getDefault()) }
private val timeFmt = ThreadLocal.withInitial { SimpleDateFormat("h:mm a", Locale.getDefault()) }
private val fullFmt = ThreadLocal.withInitial { SimpleDateFormat("dd MMM yyyy, h:mm a", Locale.getDefault()) }

fun formatDate(millis: Long): String {
    val now = Calendar.getInstance()
    val c = Calendar.getInstance().apply { timeInMillis = millis }
    return if (now.get(Calendar.YEAR) == c.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == c.get(Calendar.DAY_OF_YEAR)) {
        "Today, " + timeFmt.get()!!.format(Date(millis))
    } else {
        dateFmt.get()!!.format(Date(millis))
    }
}

fun formatDateTime(millis: Long): String = fullFmt.get()!!.format(Date(millis))

fun formatDuration(ms: Long): String {
    if (ms <= 0) return "0:00"
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec) else String.format(Locale.US, "%d:%02d", m, sec)
}
