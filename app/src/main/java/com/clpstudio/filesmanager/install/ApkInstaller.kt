package com.clpstudio.filesmanager.install

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Tapping an APK opens the system "Do you want to install this app?" prompt directly.
 *
 * The APK is streamed into a [PackageInstaller] session (nothing is copied to Downloads), so the
 * prompt comes from the system installer even though Files itself is registered as an
 * "Open with" app for every file type. If "Install unknown apps" isn't allowed for Files yet,
 * the user is sent to that settings page first and the install continues when they come back.
 */
object ApkInstaller {
    fun install(context: Context, file: File) = install(context, Uri.fromFile(file))

    fun install(context: Context, uri: Uri) {
        context.startActivity(
            Intent(context, InstallApkActivity::class.java)
                .setData(uri)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
    }

    /** "Install unknown apps" is allowed for Files (always true before Android 8). */
    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    fun unknownSourcesIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.packageName))
}

/** Invisible activity that runs the install flow and shows the system prompts on top. */
class InstallApkActivity : ComponentActivity() {
    private var askedPermission = false
    private var started = false

    private val unknownSources = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (ApkInstaller.canInstall(this)) startInstall()
        else done("To install apps, allow \"Install unknown apps\" for Files")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) {
            started = savedInstanceState.getBoolean("started")
            askedPermission = savedInstanceState.getBoolean("asked")
        }
        if (handleStatus(intent)) return
        if (started || askedPermission) return
        if (intent.data == null) return done("Nothing to install")
        if (ApkInstaller.canInstall(this)) {
            startInstall()
        } else {
            askedPermission = true
            Toast.makeText(this, "Allow \"Install unknown apps\" for Files, then go back", Toast.LENGTH_LONG).show()
            try {
                unknownSources.launch(ApkInstaller.unknownSourcesIntent(this))
            } catch (_: ActivityNotFoundException) {
                startInstall()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("started", started)
        outState.putBoolean("asked", askedPermission)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleStatus(intent)
    }

    private fun startInstall() {
        val uri = intent.data ?: return done("Nothing to install")
        started = true
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { commitSession(uri) } }
            result.onFailure { fallbackInstall(uri) }
        }
    }

    /** Streams the APK into a new install session and commits it; the result comes back to [onNewIntent]. */
    @SuppressLint("RequestInstallPackagesPolicy")
    private fun commitSession(uri: Uri) {
        val installer = packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val length = runCatching { contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } }.getOrNull() ?: -1L
        if (length > 0) params.setSize(length)
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                val input = contentResolver.openInputStream(uri) ?: error("Can't read the APK")
                input.use { inp ->
                    session.openWrite("base.apk", 0, length).use { out ->
                        inp.copyTo(out, 256 * 1024)
                        session.fsync(out)
                    }
                }
                val status = Intent(this, InstallApkActivity::class.java)
                    .setAction(ACTION_STATUS)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val pending = PendingIntent.getActivity(this, id, status, flags)
                session.commit(pending.intentSender)
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(id) }
            throw e
        }
    }

    /** True if [intent] is a session status (then it's fully handled here). */
    private fun handleStatus(intent: Intent?): Boolean {
        if (intent?.action != ACTION_STATUS) return false
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                if (confirm == null) return done("The installer isn't available").let { true }
                try {
                    startActivity(confirm)
                } catch (_: Exception) {
                    done("The installer isn't available")
                }
            }
            PackageInstaller.STATUS_SUCCESS -> done("App installed")
            PackageInstaller.STATUS_FAILURE_ABORTED -> done(null)
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                done(installError(status, msg))
            }
        }
        return true
    }

    /** If the session API isn't usable on this phone, hand the APK to the system installer by name. */
    private fun fallbackInstall(uri: Uri) {
        val content = if (uri.scheme == "file") {
            androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", File(uri.path!!))
        } else uri
        @Suppress("DEPRECATION")
        val base = Intent(Intent.ACTION_INSTALL_PACKAGE).setDataAndType(content, APK_MIME)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
        val installerPkg = packageManager.queryIntentActivities(base, PackageManager.MATCH_DEFAULT_ONLY)
            .map { it.activityInfo.packageName }
            .firstOrNull { it != packageName }
        try {
            startActivity(if (installerPkg != null) base.setPackage(installerPkg) else base)
            done(null)
        } catch (_: Exception) {
            done("The installer isn't available")
        }
    }

    private fun done(message: String?) {
        if (message != null) Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
        finish()
    }

    companion object {
        const val APK_MIME = "application/vnd.android.package-archive"
        private const val ACTION_STATUS = "com.clpstudio.filesmanager.INSTALL_STATUS"

        fun installError(status: Int, message: String?): String = when (status) {
            PackageInstaller.STATUS_FAILURE_BLOCKED -> "Install blocked by the phone"
            PackageInstaller.STATUS_FAILURE_CONFLICT -> "Not installed: an app with the same name but a different signature is already installed"
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "Not installed: this app isn't compatible with this phone"
            PackageInstaller.STATUS_FAILURE_INVALID -> "Not installed: the APK is damaged or incomplete"
            PackageInstaller.STATUS_FAILURE_STORAGE -> "Not installed: not enough storage"
            else -> "Not installed" + (message?.let { ": $it" } ?: "")
        }
    }
}
