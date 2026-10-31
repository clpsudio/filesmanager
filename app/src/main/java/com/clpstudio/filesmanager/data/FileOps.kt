package com.clpstudio.filesmanager.data

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.coroutines.coroutineContext

data class OpProgress(
    val title: String,
    val currentName: String = "",
    val doneBytes: Long = 0,
    val totalBytes: Long = 0,
    val doneItems: Int = 0,
    val totalItems: Int = 0,
) {
    val fraction: Float get() = if (totalBytes > 0) (doneBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
}

class FileOpException(message: String) : IOException(message)

object FileOps {
    private const val BUFFER = 256 * 1024

    fun authority(context: Context) = context.packageName + ".files"

    fun uriFor(context: Context, file: File): Uri = FileProvider.getUriForFile(context, authority(context), file)

    fun sizeOf(file: File): Long = if (file.isDirectory) file.walkTopDown().filter { it.isFile }.sumOf { it.length() } else file.length()

    fun createFolder(parent: File, name: String): File {
        if (!isValidFileName(name)) throw FileOpException("That name can't be used")
        val dir = File(parent, name.trim())
        if (dir.exists()) throw FileOpException("A file or folder with that name already exists")
        if (!dir.mkdirs()) throw FileOpException("Couldn't create the folder here")
        return dir
    }

    fun rename(context: Context, file: File, newName: String): File {
        if (!isValidFileName(newName)) throw FileOpException("That name can't be used")
        val target = File(file.parentFile, newName.trim())
        if (target.exists()) throw FileOpException("A file or folder with that name already exists")
        if (!file.renameTo(target)) throw FileOpException("Couldn't rename it")
        Favorites.onMoved(file.absolutePath, target.absolutePath)
        scan(context, listOf(file.absolutePath, target.absolutePath))
        return target
    }

    /** Copies or moves [sources] into [targetDir]. Name clashes get "name (1)". */
    suspend fun transfer(
        context: Context,
        sources: List<File>,
        targetDir: File,
        move: Boolean,
        onProgress: (OpProgress) -> Unit,
    ): List<File> = withContext(Dispatchers.IO) {
        val title = if (move) "Moving" else "Copying"
        for (src in sources) {
            if (src.isDirectory && (targetDir.absolutePath + "/").startsWith(src.absolutePath + "/")) {
                throw FileOpException("Can't put a folder inside itself")
            }
        }
        val total = sources.sumOf { sizeOf(it) }
        var done = 0L
        val results = ArrayList<File>()
        val scanPaths = ArrayList<String>()
        sources.forEachIndexed { index, src ->
            coroutineContext.ensureActive()
            if (move && src.parentFile?.absolutePath == targetDir.absolutePath) {
                results += src
                return@forEachIndexed
            }
            val name = uniqueName(src.name, src.isDirectory) { File(targetDir, it).exists() }
            val dest = File(targetDir, name)
            onProgress(OpProgress(title, src.name, done, total, index, sources.size))
            if (move && src.renameTo(dest)) {
                done += sizeOf(dest)
            } else {
                copyRecursively(src, dest) { bytes, current ->
                    done += bytes
                    onProgress(OpProgress(title, current, done, total, index, sources.size))
                }
                if (move && !src.deleteRecursively()) throw FileOpException("Copied, but couldn't remove the original of ${src.name}")
            }
            if (move) {
                scanPaths += src.absolutePath
                Favorites.onMoved(src.absolutePath, dest.absolutePath)
            }
            scanPaths += dest.absolutePath
            results += dest
        }
        onProgress(OpProgress(title, "", total, total, sources.size, sources.size))
        scan(context, scanPaths)
        results
    }

    private suspend fun copyRecursively(src: File, dest: File, onBytes: (Long, String) -> Unit) {
        if (src.isDirectory) {
            if (!dest.exists() && !dest.mkdirs()) throw FileOpException("Couldn't create ${dest.name}")
            src.listFiles()?.forEach { child -> copyRecursively(child, File(dest, child.name), onBytes) }
        } else {
            dest.parentFile?.mkdirs()
            src.inputStream().use { input ->
                dest.outputStream().use { output ->
                    val buf = ByteArray(BUFFER)
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        onBytes(n.toLong(), src.name)
                    }
                }
            }
            dest.setLastModified(src.lastModified())
        }
    }

    /** Tell the media index about changed paths (folders: their files). */
    fun scan(context: Context, paths: List<String>) {
        val all = ArrayList<String>()
        for (p in paths) {
            val f = File(p)
            if (f.isDirectory) f.walkTopDown().take(2000).filter { it.isFile }.forEach { all += it.absolutePath } else all += p
        }
        if (all.isNotEmpty()) MediaScannerConnection.scanFile(context.applicationContext, all.toTypedArray(), null, null)
    }

    fun share(context: Context, files: List<File>) {
        val list = files.filter { it.isFile }
        if (list.isEmpty()) {
            Toast.makeText(context, "Folders can't be shared, pick files", Toast.LENGTH_SHORT).show()
            return
        }
        val uris = list.map { uriFor(context, it) }
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = FileTypes.mimeOf(list[0].name)
                putExtra(Intent.EXTRA_STREAM, uris[0])
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = commonMime(list)
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            }
        }
        intent.clipData = ClipData.newRawUri("", uris[0]).also { clip -> uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) } }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun commonMime(files: List<File>): String {
        val mimes = files.map { FileTypes.mimeOf(it.name) }.distinct()
        if (mimes.size == 1) return mimes[0]
        val families = mimes.map { it.substringBefore('/') }.distinct()
        return if (families.size == 1) families[0] + "/*" else "*/*"
    }

    /** "Open with" another app, never this one (avoids a loop). */
    fun openWithOtherApp(context: Context, uri: Uri, mime: String) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooser = Intent.createChooser(intent, "Open with").apply {
            putExtra(
                Intent.EXTRA_EXCLUDE_COMPONENTS,
                arrayOf(
                    android.content.ComponentName(context, com.clpstudio.filesmanager.viewer.OpenWithActivity::class.java),
                ),
            )
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(chooser)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "No app on this phone can open it", Toast.LENGTH_SHORT).show()
        }
    }
}
