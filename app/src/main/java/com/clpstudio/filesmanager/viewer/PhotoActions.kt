package com.clpstudio.filesmanager.viewer

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.clpstudio.filesmanager.data.Favorites
import com.clpstudio.filesmanager.data.FileOps
import com.clpstudio.filesmanager.data.FileTypes
import com.clpstudio.filesmanager.data.uniqueName
import java.io.File

/** Photo-viewer actions: Edit, Set as, Hide. */
object PhotoActions {
    const val HIDDEN_DIR = ".hidden"

    /** Opens an editor (Photo Editor, Snapseed…). Falls back to "Open with" when no editor is installed. */
    fun edit(context: Context, uri: Uri, mime: String) {
        val intent = Intent(Intent.ACTION_EDIT).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        // Started directly (no chooser), so "no editor installed" surfaces as ActivityNotFoundException;
        // the system shows its own picker when several editors exist.
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            FileOps.openWithOtherApp(context, uri, mime)
        } catch (_: SecurityException) {
            FileOps.openWithOtherApp(context, uri, mime)
        }
    }

    fun edit(context: Context, file: File) = edit(context, FileOps.uriFor(context, file), FileTypes.mimeOf(file.name))

    /** "Set as" wallpaper / contact photo, through the system chooser. */
    fun setAs(context: Context, uri: Uri, mime: String) {
        val intent = Intent(Intent.ACTION_ATTACH_DATA).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
            setDataAndType(uri, mime)
            putExtra("mimeType", mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            context.startActivity(Intent.createChooser(intent, "Set as").apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "No app can use this photo", Toast.LENGTH_SHORT).show()
        } catch (_: SecurityException) {
            Toast.makeText(context, "No app can use this photo", Toast.LENGTH_SHORT).show()
        }
    }

    fun setAs(context: Context, file: File) = setAs(context, FileOps.uriFor(context, file), FileTypes.mimeOf(file.name))

    /** Where [file] goes when hidden: "<same folder>/.hidden/<name>" (a clash gets "name (1)"). */
    fun hiddenTarget(file: File): File {
        val dir = File(file.parentFile, HIDDEN_DIR)
        return File(dir, uniqueName(file.name, file.isDirectory) { File(dir, it).exists() })
    }

    /**
     * Moves [file] into a ".hidden" folder next to it (created with a .nomedia file so galleries skip it),
     * rescans both paths and moves its star along. Returns the new file, or null when it couldn't move.
     */
    fun hide(context: Context, file: File): File? {
        if (file.parentFile?.name == HIDDEN_DIR) return null
        val target = hiddenTarget(file)
        val dir = target.parentFile ?: return null
        if (!dir.isDirectory && !dir.mkdirs()) return null
        runCatching { File(dir, ".nomedia").let { if (!it.exists()) it.createNewFile() } }
        if (!file.renameTo(target)) return null
        Favorites.init(context)
        Favorites.onMoved(file.absolutePath, target.absolutePath)
        FileOps.scan(context, listOf(file.absolutePath, target.absolutePath))
        return target
    }
}
