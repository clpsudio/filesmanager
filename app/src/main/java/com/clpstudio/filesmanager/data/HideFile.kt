package com.clpstudio.filesmanager.data

import android.content.Context
import java.io.File

/** "Hide": moves a file into a ".hidden" folder (with .nomedia) next to it. Shown again with "Show hidden files". */
object HideFile {
    fun hide(context: Context, file: File): File? {
        val dir = File(file.parentFile ?: return null, ".hidden")
        if (!dir.isDirectory && !dir.mkdirs()) return null
        File(dir, ".nomedia").let { if (!it.exists()) runCatching { it.createNewFile() } }
        var target = File(dir, file.name)
        var n = 1
        while (target.exists()) target = File(dir, file.nameWithoutExtension + " (${n++})" + (if (file.extension.isNotEmpty()) "." + file.extension else ""))
        val ok = file.renameTo(target) || runCatching { file.copyTo(target); file.delete() }.getOrDefault(false)
        if (!ok) return null
        Favorites.onMoved(file.absolutePath, target.absolutePath)
        FileOps.scan(context, listOf(file.absolutePath, target.absolutePath))
        return target
    }
}
