package com.clpstudio.filesmanager.data

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import kotlinx.coroutines.launch

/**
 * Item 27 (crash from Samsung Camera's preview): some providers (Samsung's secure media, content://secmedia/...)
 * don't allow passing their uri on with a read grant, and startActivity then throws SecurityException.
 * Every start that may carry someone else's uri goes through here: first as asked; on SecurityException again
 * without any grant flags or ClipData; if that fails too, a short message. Never a crash.
 */
object SafeStart {
    private const val GRANTS = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION

    /** True when [intent] asks to pass a uri grant on. */
    fun hasGrant(intent: Intent): Boolean = intent.flags and GRANTS != 0 || intent.clipData != null

    /** The same intent without grant flags and ClipData (inside our app the process already has access). */
    fun withoutGrants(intent: Intent): Intent = Intent(intent).apply {
        flags = flags and GRANTS.inv()
        clipData = null
        // A chooser wraps the real intent: strip that too.
        @Suppress("DEPRECATION")
        (getParcelableExtra<Intent>(Intent.EXTRA_INTENT))?.let { putExtra(Intent.EXTRA_INTENT, withoutGrants(it)) }
    }

    /** Returns true when something was started. */
    fun start(context: Context, intent: Intent, failMessage: String = "Couldn't open it"): Boolean {
        try {
            context.startActivity(intent)
            return true
        } catch (e: SecurityException) {
            if (hasGrant(intent)) {
                try {
                    context.startActivity(withoutGrants(intent))
                    return true
                } catch (_: SecurityException) {
                } catch (_: ActivityNotFoundException) {
                }
            }
        } catch (_: ActivityNotFoundException) {
        }
        runCatching { Toast.makeText(context, failMessage, Toast.LENGTH_SHORT).show() }
        return false
    }

    /**
     * Share someone else's uri (opened from another app). When its provider refuses to let the grant be passed on,
     * a copy in our cache is shared through our own FileProvider instead, so the other app can still read it.
     */
    fun shareForeign(context: Context, uri: android.net.Uri, mime: String, name: String?) {
        val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            context.startActivity(Intent.createChooser(send, "Share"))
            return
        } catch (_: ActivityNotFoundException) {
            runCatching { Toast.makeText(context, "No app to share with", Toast.LENGTH_SHORT).show() }
            return
        } catch (_: SecurityException) {
        }
        com.clpstudio.filesmanager.Graph.appScope.launch(kotlinx.coroutines.Dispatchers.Main) {
            val copy = runCatching { ExternalFiles.copyToCache(context, uri, name ?: shareName(mime)) }.getOrNull()
            if (copy == null) {
                runCatching { Toast.makeText(context, "This file can't be shared from here", Toast.LENGTH_SHORT).show() }
                return@launch
            }
            val ours = FileOps.uriFor(context, copy)
            start(
                context,
                Intent.createChooser(Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, ours).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                "This file can't be shared from here",
            )
        }
    }

    /** A file name for a copy when the other app gave none. */
    fun shareName(mime: String): String = "shared." + when {
        mime.startsWith("image/") -> "jpg"
        mime.startsWith("video/") -> "mp4"
        mime.startsWith("audio/") -> "m4a"
        mime == "application/pdf" -> "pdf"
        else -> "bin"
    }
}
