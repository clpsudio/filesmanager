package com.clpstudio.filesmanager.data

import android.content.Context
import java.io.File

/** The app's one rename path: names checked, case-only changes go through a temporary name. */
object RenameOps {
    /** Renames [file] to [typed]; returns the renamed file, or throws [FileOpException] with plain words. */
    fun rename(context: Context, file: File, typed: String): File {
        val name = typed.trim()
        val parent = file.parentFile ?: throw FileOpException("Can't rename this")
        InfoEdits.nameError(name, file.name) { File(parent, it).exists() }?.let { throw FileOpException(it) }
        if (name == file.name) return file
        return try {
            if (InfoEdits.isCaseOnly(name, file.name)) {
                // Shared storage ignores case when checking names: go through a temporary name.
                val tmp = FileOps.rename(context, file, ".rename-" + System.nanoTime() + "-" + file.name)
                try {
                    FileOps.rename(context, tmp, name)
                } catch (e: FileOpException) {
                    runCatching { FileOps.rename(context, tmp, file.name) }
                    throw e
                }
            } else {
                FileOps.rename(context, file, name)
            }
        } catch (e: SecurityException) {
            throw FileOpException("Android doesn't allow renaming this")
        }
    }
}
