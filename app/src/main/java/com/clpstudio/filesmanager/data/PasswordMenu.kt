package com.clpstudio.filesmanager.data

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Whether a file has a password, as far as the menus know. */
enum class LockState { UNKNOWN, NONE, PROTECTED, NOT_LOCKABLE }

/** The password entries a ⋮ / long-press menu shows. */
enum class PasswordEntry { ADD, REMOVE }

/**
 * Which password items a menu shows: only "Add password" for a PDF without one, only "Remove password"
 * for a protected PDF, nothing while it's still being checked or for a type the app can't lock.
 */
object PasswordMenu {
    /** Types the app can add a password to / remove one from in place. (ZIP: "Compress with password" makes a new zip.) */
    fun lockable(kind: FileKind): Boolean = kind == FileKind.PDF

    fun entries(kind: FileKind, state: LockState): List<PasswordEntry> {
        if (!lockable(kind)) return emptyList()
        return when (state) {
            LockState.NONE -> listOf(PasswordEntry.ADD)
            LockState.PROTECTED -> listOf(PasswordEntry.REMOVE)
            LockState.UNKNOWN, LockState.NOT_LOCKABLE -> emptyList()
        }
    }

    fun fromPdf(lock: PdfSecurity.Lock): LockState = when (lock) {
        PdfSecurity.Lock.NONE -> LockState.NONE
        PdfSecurity.Lock.OWNER_ONLY, PdfSecurity.Lock.USER_PASSWORD -> LockState.PROTECTED
    }
}

/** Checked on a background thread, cached per path + last-modified time. */
object LockStateCache {
    private val cache = ConcurrentHashMap<String, Pair<Long, LockState>>()
    private val _version = MutableStateFlow(0)
    /** Bumped when a password was added / removed, so open menus check again. */
    val version: StateFlow<Int> = _version

    fun cached(path: String, modified: Long): LockState? = cache[path]?.takeIf { it.first == modified }?.second

    fun invalidate(path: String) {
        cache.remove(path)
        _version.value++
    }

    fun detect(context: Context, file: File, kind: FileKind): LockState {
        if (!PasswordMenu.lockable(kind)) return LockState.NOT_LOCKABLE
        val modified = file.lastModified()
        cached(file.absolutePath, modified)?.let { return it }
        val state = runCatching {
            when (kind) {
                FileKind.PDF -> { PdfSecurity.init(context); PasswordMenu.fromPdf(PdfSecurity.lockState(file)) }
                // Only ZIPs can be locked / unlocked here; 7z is open-only.
                FileKind.ARCHIVE -> if (!ArchiveOps.isZip(file.name)) LockState.NOT_LOCKABLE else if (ArchiveOps.isEncrypted(file)) LockState.PROTECTED else LockState.NONE
                else -> LockState.NOT_LOCKABLE
            }
        }.getOrDefault(LockState.UNKNOWN)
        if (state != LockState.UNKNOWN) cache[file.absolutePath] = modified to state
        return state
    }
}

/** The password entries for [item]'s menu ([PasswordMenu.entries]); empty until the check finishes. */
@Composable
fun rememberPasswordEntries(item: FileItem?): List<PasswordEntry> {
    val context = LocalContext.current
    val v by LockStateCache.version.collectAsState()
    if (item == null || !PasswordMenu.lockable(item.kind) || item.path.startsWith("content://")) return emptyList()
    val f = File(item.path)
    val state by produceState(LockStateCache.cached(item.path, f.lastModified()) ?: LockState.UNKNOWN, item.path, v) {
        value = withContext(Dispatchers.IO) { LockStateCache.detect(context.applicationContext, f, item.kind) }
    }
    return PasswordMenu.entries(item.kind, state)
}
