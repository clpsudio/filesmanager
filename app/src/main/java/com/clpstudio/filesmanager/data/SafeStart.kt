package com.clpstudio.filesmanager.data

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast

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
}
