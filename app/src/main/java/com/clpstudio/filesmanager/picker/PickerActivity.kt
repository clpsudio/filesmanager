package com.clpstudio.filesmanager.picker

import android.content.ClipData
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.clpstudio.filesmanager.Graph
import com.clpstudio.filesmanager.data.CategoryRepository
import com.clpstudio.filesmanager.data.FileItem
import com.clpstudio.filesmanager.data.FileOps
import com.clpstudio.filesmanager.data.NaturalOrder
import com.clpstudio.filesmanager.data.SearchQuery
import com.clpstudio.filesmanager.data.StoragePaths
import com.clpstudio.filesmanager.ui.components.rememberLiveFiles
import com.clpstudio.filesmanager.ui.screens.StoragePermission
import com.clpstudio.filesmanager.ui.theme.FilesTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Files as a source in other apps' "choose a file" flow (ACTION_GET_CONTENT / ACTION_PICK):
 * the gallery's "Browse files in other apps" list, WhatsApp's document picker, and so on.
 * Returns content:// links from our FileProvider with read permission (ClipData for several).
 * The vault and the Bin are never listed; hidden files only with "Show hidden files" on.
 */
class PickerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Graph.init(this)
        enableEdgeToEdge()
        // Shown inside another app's flow: never blanked by "Hide content while switching apps".
        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val request = PickerRequest.from(
            type = intent.type ?: runCatching { intent.resolveType(this) }.getOrNull(),
            extraMimeTypes = intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES),
            allowMultiple = intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false),
        )
        setContent {
            val settings by Graph.settings.collectAsState()
            FilesTheme(settings.theme, settings.liquidGlass) {
                PickerHost(
                    request = request,
                    showHidden = settings.showHidden,
                    onPicked = ::finishWith,
                    onCancel = { setResult(RESULT_CANCELED); finish() },
                    onAllowAccess = {
                        runCatching { startActivity(StoragePermission.allFilesIntent(this)) }
                            .onFailure { runCatching { startActivity(Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) } }
                    },
                )
            }
        }
    }

    private fun finishWith(items: List<FileItem>) {
        val files = items.map { File(it.path) }.filter { it.isFile && it.canRead() }
        if (files.isEmpty()) {
            Toast.makeText(this, "Couldn't open that file", Toast.LENGTH_SHORT).show()
            return
        }
        val uris = runCatching { files.map { FileOps.uriFor(this, it) } }.getOrElse {
            Toast.makeText(this, "Couldn't share that file", Toast.LENGTH_SHORT).show()
            return
        }
        val clip = runCatching { ClipData.newUri(contentResolver, files[0].name, uris[0]) }.getOrElse { ClipData.newRawUri(files[0].name, uris[0]) }
        uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
        val result = Intent().apply {
            data = uris[0]
            clipData = clip
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        setResult(RESULT_OK, result)
        finish()
    }
}

@Composable
private fun PickerHost(
    request: PickerRequest,
    showHidden: Boolean,
    onPicked: (List<FileItem>) -> Unit,
    onCancel: () -> Unit,
    onAllowAccess: () -> Unit,
) {
    val context = LocalContext.current
    var section by remember { mutableStateOf(request.startSection) }
    var folder by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    val selected = remember { mutableStateListOf<String>() }
    var granted by remember { mutableStateOf(StoragePermission.granted(context)) }

    // Back from Android's "All files access" screen: check again.
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) granted = StoragePermission.granted(context) }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    val volumes = remember(granted) { StoragePaths.volumes(context) }
    val cat = section.category

    // A category's list (the same shared loader the app uses).
    val loader = remember(cat, showHidden) { cat?.let { CategoryRepository.loader(it, showHidden) } }
    LaunchedEffect(loader, granted) { if (granted) loader?.ensureLoaded() }
    val catState = loader?.state?.collectAsState()
    val catItems = catState?.value?.value

    // Browse: one folder's folders + accepted files.
    val listing by produceState<List<FileItem>?>(null, folder, showHidden, granted) {
        val dir = folder
        value = if (dir == null || !granted) null else withContext(Dispatchers.IO) {
            val filter = Graph.hiddenFilter(showHidden)
            val entries = File(dir).listFiles().orEmpty().filter { filter.visibleEntry(it) }
            val dirs = entries.filter { it.isDirectory }.map { FileItem.of(it, countChildren = true) }
                .sortedWith { a, b -> NaturalOrder.compare(a.name, b.name) }
            val files = entries.filter { it.isFile }.map { FileItem.of(it) }.filter(request::accepts)
                .sortedByDescending { it.modified }
            dirs + files
        }
    }

    // Search: the open category's list, or storage (under the open folder) when browsing.
    val found by produceState<List<FileItem>?>(null, query, section, folder, showHidden, granted) {
        val q = query.trim()
        if (q.isEmpty() || section != PickerSection.BROWSE || !granted) { value = null; return@produceState }
        delay(250)
        value = null
        val roots = volumes.map { it.path }
        Graph.search.search(SearchQuery(name = q, range = null, root = folder), Graph.hiddenFilter(showHidden), roots)
            .collect { list -> value = list.filter(request::accepts) }
    }

    val raw: List<FileItem>? = when {
        section == PickerSection.BROWSE && query.isNotBlank() -> found
        section == PickerSection.BROWSE -> listing
        else -> catItems?.filter(request::accepts)?.let { pickerFilter(it, query) }
    }
    val items = rememberLiveFiles(raw)

    fun toggle(f: FileItem) {
        if (f.path in selected) selected.remove(f.path) else selected.add(f.path)
    }

    PickerContent(
        ui = PickerUi(
            request = request,
            section = section,
            items = items,
            folder = folder,
            volumes = volumes,
            query = query,
            searching = query.isNotBlank() && found == null && section == PickerSection.BROWSE,
            needsPermission = !granted,
        ),
        selected = selected.toSet(),
        onSection = { s -> section = s; if (s != PickerSection.BROWSE) folder = null },
        onQuery = { query = it },
        onOpenFolder = { p -> section = PickerSection.BROWSE; folder = p; query = "" },
        onUp = {
            val f = folder ?: return@PickerContent
            folder = if (volumes.any { it.path == f }) null else File(f).parent
        },
        onTap = { f ->
            when {
                f.isDir -> { folder = f.path; section = PickerSection.BROWSE }
                request.multiple -> toggle(f)
                else -> onPicked(listOf(f))
            }
        },
        onLongPress = { f -> if (!f.isDir) { if (request.multiple) toggle(f) else onPicked(listOf(f)) } },
        onDone = {
            val all = (items.orEmpty() + catItems.orEmpty() + listing.orEmpty() + found.orEmpty()).associateBy { it.path }
            val picked = selected.mapNotNull { p -> all[p] ?: File(p).takeIf { it.isFile }?.let { FileItem.of(it) } }
            onPicked(picked)
        },
        onCancel = onCancel,
        onAllowAccess = onAllowAccess,
    )
}
