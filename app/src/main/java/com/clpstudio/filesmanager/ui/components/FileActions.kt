package com.clpstudio.filesmanager.ui.components

import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Visibility
import android.widget.Toast
import androidx.compose.material.icons.filled.DriveFolderUpload
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Unarchive
import com.clpstudio.filesmanager.Graph
import com.clpstudio.filesmanager.data.ArchiveOps
import com.clpstudio.filesmanager.data.FileKind
import com.clpstudio.filesmanager.data.PdfSecurity
import com.clpstudio.filesmanager.data.uniqueName
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.runtime.collectAsState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.clpstudio.filesmanager.data.FileItem
import com.clpstudio.filesmanager.data.FileOpException
import com.clpstudio.filesmanager.data.FileOps
import com.clpstudio.filesmanager.data.FileTypes
import com.clpstudio.filesmanager.data.StoragePaths
import com.clpstudio.filesmanager.ui.AppState
import com.clpstudio.filesmanager.ui.ClipboardState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Stable
class SelectionState {
    val paths = mutableStateListOf<String>()
    val active: Boolean get() = paths.isNotEmpty()
    fun isSelected(path: String) = path in paths
    fun toggle(path: String) {
        if (!paths.remove(path)) paths.add(path)
    }
    fun clear() = paths.clear()
    fun selectAll(all: List<String>) {
        paths.clear()
        paths.addAll(all)
    }
}

@Stable
class FileActions(private val context: Context, private val afterCopyOrMove: () -> Unit, private val afterAction: () -> Unit = {}) {
    fun toast(text: String) = android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_SHORT).show()
    var renameTarget by mutableStateOf<FileItem?>(null)
    var detailsTarget by mutableStateOf<FileItem?>(null)
    var deleteTargets by mutableStateOf<List<FileItem>>(emptyList())
    /** Compress (withPassword = "Locked zip", AES-256). */
    var compressRequest by mutableStateOf<Pair<List<FileItem>, Boolean>?>(null)
    /** "Extract to...": the ZIPs waiting for a destination folder. */
    var extractPickFor by mutableStateOf<List<FileItem>?>(null)
    var passwordAsk by mutableStateOf<PasswordAsk?>(null)
    var pdfLockTarget by mutableStateOf<FileItem?>(null)
    var pdfUnlockTarget by mutableStateOf<FileItem?>(null)
    var pdfUnlockError by mutableStateOf<String?>(null)
    var pdfChecking by mutableStateOf(false)

    class PasswordAsk(val fileName: String, val error: String?, val answer: CompletableDeferred<String?>, val checking: Boolean = false)

    /** Shows the password dialog and waits for the answer (null = cancelled). */
    suspend fun askPassword(fileName: String, error: String? = null): String? {
        val d = CompletableDeferred<String?>()
        passwordAsk = PasswordAsk(fileName, error, d)
        return try { d.await() } finally { passwordAsk = null }
    }

    fun compress(items: List<FileItem>, withPassword: Boolean) {
        compressRequest = items to withPassword
    }

    fun doCompress(items: List<FileItem>, name: String, password: String?) {
        val sources = items.map { File(it.path) }
        val dir = sources.first().parentFile ?: return
        val out = File(dir, uniqueName(name, false) { File(dir, it).exists() })
        afterAction()
        AppState.runOperation(context, "Compressing") { report ->
            ArchiveOps.compress(sources, out, password?.toCharArray(), report)
            (if (password != null) "Locked zip saved: " else "Saved: ") + out.name to listOf(out)
        }
    }

    /** Extract each ZIP into a folder next to it, or everything into [dest]. Asks for passwords first. */
    fun extract(items: List<FileItem>, dest: File?) {
        val zips = items.filter { !it.isDir && ArchiveOps.isZip(it.name) }.map { File(it.path) }
        if (zips.isEmpty()) {
            Toast.makeText(context, "Select ZIP files to extract", Toast.LENGTH_SHORT).show()
            return
        }
        Graph.appScope.launch(Dispatchers.Main) {
            val plan = ArrayList<Triple<File, CharArray?, File>>()
            for (zip in zips) {
                val encrypted = withContext(Dispatchers.IO) { runCatching { ArchiveOps.isEncrypted(zip) } }.getOrElse {
                    Toast.makeText(context, "Can't open ${zip.name}: it may be damaged", Toast.LENGTH_LONG).show()
                    continue
                }
                var password: CharArray? = null
                if (encrypted) {
                    var error: String? = null
                    while (true) {
                        val typed = askPassword(zip.name, error) ?: return@launch
                        // Keep the dialog up with "Checking password…" while the check runs.
                        passwordAsk = PasswordAsk(zip.name, null, CompletableDeferred(), checking = true)
                        val ok = withContext(Dispatchers.IO) { runCatching { ArchiveOps.checkPassword(zip, typed.toCharArray()) }.getOrDefault(false) }
                        passwordAsk = null
                        if (ok) {
                            password = typed.toCharArray()
                            break
                        }
                        error = "Wrong password, try again"
                    }
                }
                val parent = zip.parentFile ?: continue
                val target = when {
                    dest == null -> ArchiveOps.newFolderFor(zip, parent)
                    zips.size == 1 -> dest
                    else -> File(dest, uniqueName(zip.nameWithoutExtension, true) { File(dest, it).exists() })
                }
                plan += Triple(zip, password, target)
            }
            if (plan.isEmpty()) return@launch
            afterAction()
            val single = plan.map { it.third }.distinct().singleOrNull()
            AppState.runOperation(
                context, "Extracting",
                followUp = { if (single != null) AppState.DoneAction("Extracted to ${single.name}", "Open folder", single) else null },
            ) { report ->
                var files = 0
                for ((zip, pw, target) in plan) files += ArchiveOps.extract(zip, pw, target, onProgress = report)
                val where = plan.map { it.third }.distinct()
                "Extracted $files file" + (if (files == 1) "" else "s") + " to " + where.joinToString { it.name } to where
            }
        }
    }

    fun lockPdf(item: FileItem, user: String, owner: String?, replace: Boolean) {
        val src = File(item.path)
        val out = if (replace) src else PdfSecurity.copyName(src, "_locked")
        AppState.runOperation(context, "Adding password") {
            withContext(Dispatchers.IO) {
                PdfSecurity.init(context)
                PdfSecurity.encrypt(src, user, out, ownerPassword = owner)
            }
            com.clpstudio.filesmanager.data.LockStateCache.invalidate(src.absolutePath)
            com.clpstudio.filesmanager.data.LockStateCache.invalidate(out.absolutePath)
            "Password added: " + out.name to listOf(out)
        }
    }

    fun unlockPdf(item: FileItem, password: String, replace: Boolean) {
        val src = File(item.path)
        Graph.appScope.launch(Dispatchers.Main) {
            pdfChecking = true
            pdfUnlockError = null
            val ok = withContext(Dispatchers.IO) {
                PdfSecurity.init(context)
                runCatching { PdfSecurity.checkPassword(src, password) }.getOrDefault(false)
            }
            pdfChecking = false
            if (!ok) {
                pdfUnlockError = "Wrong password"
                return@launch
            }
            pdfUnlockTarget = null
            pdfUnlockError = null
            val out = if (replace) src else PdfSecurity.copyName(src, "_unlocked")
            AppState.runOperation(
                context, "Removing password",
                followUp = { AppState.DoneAction("Saved ${out.name}", "Open", out) },
            ) {
                withContext(Dispatchers.IO) { PdfSecurity.decrypt(src, password, out) }
                com.clpstudio.filesmanager.data.LockStateCache.invalidate(src.absolutePath)
                com.clpstudio.filesmanager.data.LockStateCache.invalidate(out.absolutePath)
                "Password removed: " + out.name to listOf(out)
            }
        }
    }

    /** Hide = move into a ".hidden" folder next to it (with .nomedia); Unhide reverses it. */
    fun hide(items: List<FileItem>) {
        Graph.appScope.launch(Dispatchers.Main) {
            val n = withContext(Dispatchers.IO) { items.count { com.clpstudio.filesmanager.data.HideFile.hide(context, File(it.path)) != null } }
            AppState.refresh()
            afterAction()
            toast(if (n == items.size) "Hidden. Find them in Hidden files." else "Hid $n of ${items.size}")
        }
    }

    fun unhide(items: List<FileItem>) {
        Graph.appScope.launch(Dispatchers.Main) {
            val n = withContext(Dispatchers.IO) { com.clpstudio.filesmanager.data.HiddenOps.unhideAll(context, items.map { File(it.path) }) }
            AppState.refresh()
            afterAction()
            toast(if (n == items.size) "Unhidden" else "Unhid $n of ${items.size}")
        }
    }

    fun share(items: List<FileItem>) = FileOps.share(context, items.map { File(it.path) })
    fun copy(items: List<FileItem>) {
        AppState.clipboard = ClipboardState(items.map { it.path }, move = false)
        afterCopyOrMove()
    }
    fun move(items: List<FileItem>) {
        AppState.clipboard = ClipboardState(items.map { it.path }, move = true)
        afterCopyOrMove()
    }
    fun openWith(item: FileItem) {
        val f = File(item.path)
        FileOps.openWithOtherApp(context, FileOps.uriFor(context, f), FileTypes.mimeOf(f.name))
    }
}

@Composable
fun rememberFileActions(afterCopyOrMove: () -> Unit, afterAction: () -> Unit = {}): FileActions {
    val context = LocalContext.current
    return remember { FileActions(context, afterCopyOrMove, afterAction) }
}

/** The dialogs that file actions open (rename, delete to Bin, details). */
@Composable
fun FileActionDialogs(actions: FileActions, onDeleted: () -> Unit = {}) {
    val context = LocalContext.current
    actions.renameTarget?.let { item ->
        TextInputDialog(
            title = "Rename",
            initial = item.name,
            confirm = "Rename",
            selectBaseName = !item.isDir,
            onConfirm = { name ->
                try {
                    FileOps.rename(context, File(item.path), name)
                    AppState.refresh()
                    null
                } catch (e: FileOpException) {
                    e.message
                }
            },
            onDismiss = { actions.renameTarget = null },
        )
    }
    if (actions.deleteTargets.isNotEmpty()) {
        val items = actions.deleteTargets
        ConfirmDialog(
            title = if (items.size == 1) "Delete ${items[0].name}?" else "Delete ${items.size} items?",
            text = "They go to the Bin and are deleted for good after 30 days. You can restore them from the Bin until then.",
            confirm = "Move to Bin",
            destructive = true,
            onConfirm = { AppState.moveToBin(context, items.map { File(it.path) }, onDeleted) },
            onDismiss = { actions.deleteTargets = emptyList() },
        )
    }
    actions.compressRequest?.let { (items, withPassword) ->
        CompressDialog(
            defaultName = ArchiveOps.defaultZipName(items.map { File(it.path) }),
            withPassword = withPassword,
            onConfirm = { name, password -> actions.compressRequest = null; actions.doCompress(items, name, password) },
            onDismiss = { actions.compressRequest = null },
        )
    }
    actions.extractPickFor?.let { items ->
        FolderPickerDialog(
            start = File(items.first().path).parentFile ?: File(StoragePaths.primary),
            title = "Extract to",
            confirm = "Extract here",
            onPick = { dir -> actions.extractPickFor = null; actions.extract(items, dir) },
            onDismiss = { actions.extractPickFor = null },
        )
    }
    actions.passwordAsk?.let { ask ->
        EnterPasswordDialog(
            fileName = ask.fileName,
            error = ask.error,
            onConfirm = { ask.answer.complete(it) },
            onDismiss = { ask.answer.complete(null) },
            confirm = "Extract",
            checking = ask.checking,
        )
    }
    androidx.compose.runtime.DisposableEffect(actions) { onDispose { actions.passwordAsk?.answer?.complete(null) } }
    actions.pdfLockTarget?.let { item ->
        PdfLockDialog(
            fileName = item.name,
            onConfirm = { user, owner, replace -> actions.pdfLockTarget = null; actions.lockPdf(item, user, owner, replace) },
            onDismiss = { actions.pdfLockTarget = null },
        )
    }
    actions.pdfUnlockTarget?.let { item ->
        PdfUnlockDialog(
            fileName = item.name,
            error = actions.pdfUnlockError,
            checking = actions.pdfChecking,
            onConfirm = { pass, replace -> actions.unlockPdf(item, pass, replace) },
            onDismiss = { actions.pdfUnlockTarget = null; actions.pdfUnlockError = null },
        )
    }
    actions.detailsTarget?.let { item ->
        val size by produceState<Long?>(if (item.isDir) null else item.size, item.path) {
            if (item.isDir) value = withContext(Dispatchers.IO) { FileOps.sizeOf(File(item.path)) }
        }
        InfoDialog(
            title = item.name,
            rows = listOf(
                "Location" to StoragePaths.relative(item.parentPath),
                "Size" to (size?.let { formatSize(it) } ?: "Counting..."),
                "Modified" to formatDateTime(item.modified),
                "Type" to if (item.isDir) "Folder" else FileTypes.mimeOf(item.name),
            ),
            onDismiss = { actions.detailsTarget = null },
        )
    }
}

/** Top-bar actions while files are selected. */
@Composable
fun SelectionActionsRow(
    selected: List<FileItem>,
    actions: FileActions,
    onSelectAll: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    IconButton(onClick = onSelectAll) { Icon(Icons.Filled.SelectAll, "Select all") }
    IconButton(onClick = { actions.share(selected) }) { Icon(Icons.Filled.Share, "Share") }
    IconButton(onClick = { actions.deleteTargets = selected }) { Icon(Icons.Filled.Delete, "Delete") }
    Box {
        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            val favs by com.clpstudio.filesmanager.data.Favorites.paths.collectAsState()
            val allFav = selected.isNotEmpty() && selected.all { it.path in favs }
            MenuRow(if (allFav) Icons.Filled.StarBorder else Icons.Filled.Star, if (allFav) "Remove from favorites" else "Add to favorites") {
                menu = false
                if (allFav) com.clpstudio.filesmanager.data.Favorites.remove(selected.map { it.path })
                else com.clpstudio.filesmanager.data.Favorites.add(selected.map { it.path })
                actions.toast(if (allFav) "Removed from favorites" else "Added to favorites")
            }
            // Hidden items get Unhide (never Hide); normal ones Hide.
            val hideChoice = com.clpstudio.filesmanager.data.HideMenu.decide(selected.map { it.path })
            if (hideChoice.unhide) MenuRow(Icons.Filled.Visibility, "Unhide") { menu = false; actions.unhide(selected.filter { com.clpstudio.filesmanager.data.HiddenNames.isHidden(it.path) }) }
            if (hideChoice.hide) MenuRow(Icons.Filled.VisibilityOff, "Hide") { menu = false; actions.hide(selected.filter { !com.clpstudio.filesmanager.data.HiddenNames.isHidden(it.path) }) }
            MenuRow(Icons.Filled.ContentCopy, "Copy to...") { menu = false; actions.copy(selected) }
            MenuRow(Icons.AutoMirrored.Filled.DriveFileMove, "Move to...") { menu = false; actions.move(selected) }
            MenuRow(Icons.Filled.FolderZip, "Compress") { menu = false; actions.compress(selected, withPassword = false) }
            MenuRow(Icons.Filled.Lock, "Compress with password") { menu = false; actions.compress(selected, withPassword = true) }
            if (selected.isNotEmpty() && selected.all { !it.isDir && ArchiveOps.isZip(it.name) }) {
                MenuRow(Icons.Filled.Unarchive, "Extract") { menu = false; actions.extract(selected, null) }
                MenuRow(Icons.Filled.DriveFolderUpload, "Extract to...") { menu = false; actions.extractPickFor = selected }
            }
            if (selected.size == 1 && selected[0].kind == FileKind.PDF) {
                // Only the one that applies (checked in the background; neither while unknown).
                val pw = com.clpstudio.filesmanager.data.rememberPasswordEntries(selected[0])
                if (com.clpstudio.filesmanager.data.PasswordEntry.ADD in pw) MenuRow(Icons.Filled.Lock, "Add password to PDF") { menu = false; actions.pdfLockTarget = selected[0] }
                if (com.clpstudio.filesmanager.data.PasswordEntry.REMOVE in pw) MenuRow(Icons.Filled.LockOpen, "Remove PDF password") { menu = false; actions.pdfUnlockTarget = selected[0] }
            }
            if (selected.size == 1) {
                MenuRow(Icons.Filled.DriveFileRenameOutline, "Rename") { menu = false; actions.renameTarget = selected[0] }
                MenuRow(Icons.Filled.Info, "Details") { menu = false; actions.detailsTarget = selected[0] }
                if (!selected[0].isDir) {
                    MenuRow(Icons.AutoMirrored.Filled.OpenInNew, "Open with another app") { menu = false; actions.openWith(selected[0]) }
                }
            }
        }
    }
}

@Composable
fun CloseSelectionButton(onClose: () -> Unit) {
    IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Cancel selection") }
}
