package com.clpstudio.filesmanager.ui.screens

import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clpstudio.filesmanager.Graph
import com.clpstudio.filesmanager.data.BinEntry
import com.clpstudio.filesmanager.data.FileItem
import com.clpstudio.filesmanager.data.SystemTrash
import com.clpstudio.filesmanager.data.TrashItem
import com.clpstudio.filesmanager.data.TrashOutcome
import com.clpstudio.filesmanager.data.groupByDaysLeft
import com.clpstudio.filesmanager.data.itemCount
import com.clpstudio.filesmanager.ui.AppState
import com.clpstudio.filesmanager.ui.components.ConfirmDialog
import com.clpstudio.filesmanager.ui.components.FileThumb
import com.clpstudio.filesmanager.ui.components.formatSize
import com.clpstudio.filesmanager.ui.glass.GlassScaffold
import com.clpstudio.filesmanager.ui.glass.LocalGlass
import com.clpstudio.filesmanager.ui.glass.glassBar
import com.clpstudio.filesmanager.ui.glass.glassPanel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** One row of the Bin, from our own Bin ([local]) or from Android's trash ([system]). */
@Immutable
data class BinRowUi(
    val key: String,
    val name: String,
    /** What [FileThumb] draws: real path (for previews) + original name (for the type). */
    val thumb: FileItem,
    val isDir: Boolean,
    val size: Long,
    /** Direct children of a folder, or -1. */
    val childCount: Int,
    val deletedAt: Long,
    val daysLeft: Int?,
    /** Where it came from, e.g. "Internal storage/Download". */
    val location: String?,
    val local: BinEntry? = null,
    val system: TrashItem? = null,
) {
    val fromOtherApp: Boolean get() = system != null
    val sizeText: String get() = if (isDir && childCount >= 0) "$childCount item" + (if (childCount == 1) "" else "s") else formatSize(size)
}

private fun prettyFolder(path: String?): String? {
    val parent = path?.substringBeforeLast('/', "")?.takeIf { it.isNotEmpty() } ?: return null
    return parent.replace(Regex("^/storage/emulated/0"), "Internal storage").replace(Regex("^/sdcard"), "Internal storage")
}

fun BinEntry.toRow(now: Long = System.currentTimeMillis(), count: Int = itemCount()): BinRowUi = BinRowUi(
    key = "bin:$id",
    name = name,
    thumb = FileItem(dataFile.absolutePath, name, isDir, size, deletedAt, childCount = count),
    isDir = isDir,
    size = size,
    childCount = count,
    deletedAt = deletedAt,
    daysLeft = daysLeft(now),
    location = prettyFolder(originalPath),
    local = this,
)

fun TrashItem.toRow(now: Long = System.currentTimeMillis()): BinRowUi = BinRowUi(
    key = key,
    name = name,
    thumb = FileItem(path ?: name, name, isDir, size, deletedAt),
    isDir = isDir,
    size = size,
    childCount = -1,
    deletedAt = deletedAt,
    daysLeft = daysLeft(now),
    location = prettyFolder(path),
    system = this,
)

/** "31 Oct 10:51" (24 h) or "31 Oct 10:51 AM"; the year is added when it isn't this year. */
fun binDate(millis: Long, is24h: Boolean = true, now: Long = System.currentTimeMillis()): String {
    val c = Calendar.getInstance().apply { timeInMillis = millis }
    val n = Calendar.getInstance().apply { timeInMillis = now }
    val time = if (is24h) "HH:mm" else "h:mm a"
    val pattern = if (c.get(Calendar.YEAR) == n.get(Calendar.YEAR)) "d MMM $time" else "d MMM yyyy $time"
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(millis))
}

private const val ROW = "row:"

private class BinData(val local: List<BinRowUi>, val others: List<BinRowUi>)

/** Remembers whether a long press just happened, so the row's tap that follows it is ignored. */
private class LongPressGuard {
    var active = false
    var endedAt = 0L
    fun swallowClick(): Boolean = active || SystemClock.uptimeMillis() - endedAt < 350
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BinScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val trash = remember { SystemTrash(context.applicationContext) }
    var tick by remember { mutableIntStateOf(0) }
    val data by produceState<BinData?>(null, tick, AppState.refreshTick) {
        value = withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val local = runCatching { Graph.bin.list() }.getOrDefault(emptyList()).map { it.toRow(now) }
            val others = if (SystemTrash.supported) runCatching { trash.list() }.getOrDefault(emptyList()).map { it.toRow(now) } else emptyList()
            BinData(local, others)
        }
    }
    val all = remember(data) { data?.let { it.local + it.others }.orEmpty() }
    var selectMode by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var sheetRow by remember { mutableStateOf<BinRowUi?>(null) }
    var confirmDelete by remember { mutableStateOf<List<BinRowUi>>(emptyList()) }
    var confirmEmpty by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val is24h = remember { android.text.format.DateFormat.is24HourFormat(context) }

    fun exitSelect() { selectMode = false; selected = emptySet() }

    val confirmLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        tick++; AppState.refresh()
    }

    fun finish(verb: String, n: Int, out: TrashOutcome) {
        out.confirm?.let { runCatching { confirmLauncher.launch(IntentSenderRequest.Builder(it).build()) } }
        val total = n + out.done
        val msg = buildString {
            if (total > 0 || out.confirm == null) append("$verb $total item" + if (total == 1) "" else "s")
            if (out.failed > 0) append((if (isNotEmpty()) " · " else "") + "${out.failed} couldn't be changed")
        }
        if (msg.isNotEmpty()) Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        exitSelect(); tick++; AppState.refresh()
    }

    fun restore(rows: List<BinRowUi>) = scope.launch {
        val n = Graph.bin.restore(rows.mapNotNull { it.local })
        val sys = rows.mapNotNull { it.system }
        val out = if (sys.isEmpty()) TrashOutcome(0) else trash.restore(sys)
        finish("Restored", n, out)
    }

    fun deleteForever(rows: List<BinRowUi>) = scope.launch {
        val n = Graph.bin.deleteForever(rows.mapNotNull { it.local })
        val sys = rows.mapNotNull { it.system }
        val out = if (sys.isEmpty()) TrashOutcome(0) else trash.deleteForever(sys)
        finish("Deleted", n, out)
    }

    BackHandler(enabled = selectMode) { exitSelect() }

    val headerPx = with(androidx.compose.ui.platform.LocalDensity.current) { 150.dp.toPx() }
    val collapse by remember {
        derivedStateOf {
            if (listState.firstVisibleItemIndex > 0) 1f else (listState.firstVisibleItemScrollOffset / headerPx).coerceIn(0f, 1f)
        }
    }
    val chosen = all.filter { it.key in selected }

    GlassScaffold(
        topBar = {
            BinTopBar(
                collapse = if (selectMode) 1f else collapse,
                title = if (selectMode) (if (selected.isEmpty()) "Select items" else "${selected.size} selected") else "Bin",
                selectMode = selectMode,
                allSelected = all.isNotEmpty() && selected.size == all.size,
                hasItems = all.isNotEmpty(),
                onBack = onBack,
                onClose = { exitSelect() },
                onSelect = { selectMode = true },
                onToggleAll = { selected = if (selected.size == all.size) emptySet() else all.map { it.key }.toSet() },
                menu = {
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Restore all") }, enabled = all.isNotEmpty(), onClick = { menu = false; restore(all) })
                            DropdownMenuItem(text = { Text("Empty bin") }, enabled = all.isNotEmpty(), onClick = { menu = false; confirmEmpty = true })
                        }
                    }
                },
            )
        },
        bottomBar = {
            AnimatedVisibility(selectMode, enter = slideInVertically { it }, exit = slideOutVertically { it }) {
                BinSelectionBar(
                    enabled = selected.isNotEmpty(),
                    onRestore = { restore(chosen) },
                    onDelete = { confirmDelete = chosen },
                )
            }
        },
    ) { padding ->
        val d = data
        if (d == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { androidx.compose.material3.CircularProgressIndicator() }
        } else {
            BinContent(
                local = d.local,
                others = d.others,
                othersSupported = SystemTrash.supported,
                selectMode = selectMode,
                selected = selected,
                listState = listState,
                contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp),
                is24h = is24h,
                titleText = if (selectMode && selected.isNotEmpty()) "${selected.size} selected" else "Bin",
                onRowClick = { row ->
                    if (selectMode) selected = if (row.key in selected) selected - row.key else selected + row.key
                    else sheetRow = row
                },
                onDragSelect = { base, range ->
                    selectMode = true
                    selected = base + range
                },
            )
        }
    }

    sheetRow?.let { row ->
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { sheetRow = null }, sheetState = sheetState) {
            BinItemSheetContent(
                row = row,
                is24h = is24h,
                onRestore = { sheetRow = null; restore(listOf(row)) },
                onDelete = { sheetRow = null; confirmDelete = listOf(row) },
            )
        }
    }
    if (confirmDelete.isNotEmpty()) {
        val rows = confirmDelete
        val n = rows.size
        ConfirmDialog(
            "Delete forever?",
            (if (n == 1) "\"${rows[0].name}\"" else "$n items") + " will be deleted permanently. This can't be undone.",
            "Delete forever", destructive = true,
            onConfirm = { deleteForever(rows) }, onDismiss = { confirmDelete = emptyList() },
        )
    }
    if (confirmEmpty) {
        val others = all.count { it.fromOtherApp }
        ConfirmDialog(
            "Empty the Bin?",
            "All ${all.size} items will be deleted permanently" + (if (others > 0) ", including $others from other apps" else "") + ". This can't be undone.",
            "Empty bin", destructive = true,
            onConfirm = { deleteForever(all) }, onDismiss = { confirmEmpty = false },
        )
    }
}

@Composable
private fun BinTopBar(
    collapse: Float,
    title: String,
    selectMode: Boolean,
    allSelected: Boolean,
    hasItems: Boolean,
    onBack: () -> Unit,
    onClose: () -> Unit,
    onSelect: () -> Unit,
    onToggleAll: () -> Unit,
    menu: @Composable () -> Unit,
) {
    val bar = if (collapse > 0.6f) Modifier.glassBar() else Modifier
    Row(
        Modifier.fillMaxWidth().then(bar).windowInsetsPadding(WindowInsets.statusBars).height(56.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectMode) {
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Cancel") }
            Row(
                Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onToggleAll).padding(horizontal = 6.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SelectCircle(allSelected)
                Spacer(Modifier.width(6.dp))
                Text("All", style = MaterialTheme.typography.labelLarge)
            }
        } else {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        }
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 8.dp).graphicsLayer { alpha = ((collapse - 0.5f) * 2f).coerceIn(0f, 1f) },
        )
        if (!selectMode) {
            if (hasItems) {
                TextButton(onClick = onSelect) {
                    Text("Select", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                }
            }
            menu()
        }
    }
}

@Composable
private fun BinSelectionBar(enabled: Boolean, onRestore: () -> Unit, onDelete: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().glassBar().windowInsetsPadding(WindowInsets.navigationBars).height(68.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BarAction(Icons.Filled.RestoreFromTrash, "Restore", enabled, MaterialTheme.colorScheme.onSurface, onRestore)
        BarAction(Icons.Filled.DeleteForever, "Delete forever", enabled, MaterialTheme.colorScheme.error, onDelete)
    }
}

@Composable
private fun BarAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean, tint: Color, onClick: () -> Unit) {
    val c = if (enabled) tint else tint.copy(alpha = 0.38f)
    Column(
        Modifier.clip(RoundedCornerShape(14.dp)).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 20.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = c)
        Text(label, style = MaterialTheme.typography.labelMedium, color = c)
    }
}

/**
 * The Bin's list: big centred title, info card, rows grouped by days left, then other apps' trash.
 * Stateless, so it can be rendered in tests.
 */
@Composable
fun BinContent(
    local: List<BinRowUi>,
    others: List<BinRowUi>,
    othersSupported: Boolean,
    selectMode: Boolean,
    selected: Set<String>,
    listState: LazyListState,
    contentPadding: PaddingValues,
    is24h: Boolean,
    onRowClick: (BinRowUi) -> Unit,
    onDragSelect: (base: Set<String>, range: Set<String>) -> Unit,
    modifier: Modifier = Modifier,
    titleText: String = "Bin",
) {
    val groups = remember(local) { groupByDaysLeft(local) { it.daysLeft ?: 30 } }
    val ordered = remember(groups, others) { groups.flatMap { g -> g.items.map { it.key } } + others.map { it.key } }
    val total = local.size + others.size
    val guard = remember { LongPressGuard() }
    val scope = rememberCoroutineScope()
    val currentSelected by rememberUpdatedState(selected)
    val currentOrdered by rememberUpdatedState(ordered)
    val currentOnDrag by rememberUpdatedState(onDragSelect)
    var dragging by remember { mutableStateOf(false) }

    LazyColumn(
        state = listState,
        contentPadding = contentPadding,
        userScrollEnabled = !dragging,
        modifier = modifier.fillMaxSize().dragToSelect(
            state = listState,
            scope = scope,
            orderedKeys = { currentOrdered },
            onStart = { guard.active = true; dragging = true },
            onRange = { base, range -> currentOnDrag(base, range) },
            baseSelection = { currentSelected },
            onEnd = { guard.active = false; guard.endedAt = SystemClock.uptimeMillis(); dragging = false },
        ),
    ) {
        item(key = "header", contentType = "header") {
            Column(
                Modifier.fillMaxWidth().height(190.dp).graphicsLayer {
                    val collapse = if (listState.firstVisibleItemIndex == 0) listState.firstVisibleItemScrollOffset / 150.dp.toPx() else 1f
                    alpha = (1f - collapse * 1.6f).coerceIn(0f, 1f)
                    val s = 1f - 0.12f * collapse.coerceIn(0f, 1f)
                    scaleX = s; scaleY = s
                },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(titleText, fontSize = 34.sp, fontWeight = FontWeight.Normal, color = MaterialTheme.colorScheme.onBackground, textAlign = TextAlign.Center)
                Spacer(Modifier.height(6.dp))
                Text(
                    "$total item" + if (total == 1) "" else "s",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item(key = "info", contentType = "info") {
            Row(
                Modifier.padding(horizontal = 14.dp, vertical = 4.dp).fillMaxWidth().glassPanel(RoundedCornerShape(20.dp)).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Info, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text(
                    "Items in the Bin are deleted permanently after 30 days.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        if (total == 0) {
            item(key = "empty", contentType = "empty") {
                Column(Modifier.fillMaxWidth().padding(vertical = 56.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.DeleteOutline, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), modifier = Modifier.size(56.dp))
                    Spacer(Modifier.height(10.dp))
                    Text("Bin is empty", style = MaterialTheme.typography.titleMedium)
                    Text("Deleted files stay here for 30 days.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        groups.forEach { g ->
            item(key = "h:${g.daysLeft}", contentType = "section") { DottedHeader(g.label) }
            itemsIndexed(g.items, key = { _, r -> ROW + r.key }, contentType = { _, _ -> "row" }) { i, row ->
                BinRow(
                    row = row, index = i, count = g.items.size, selectMode = selectMode, selected = row.key in selected,
                    is24h = is24h, onClick = { if (!guard.swallowClick()) onRowClick(row) },
                )
            }
        }
        if (othersSupported && others.isNotEmpty()) {
            item(key = "others", contentType = "section") { DottedHeader("From other apps · ${others.size}") }
            itemsIndexed(others, key = { _, r -> ROW + r.key }, contentType = { _, _ -> "row" }) { i, row ->
                BinRow(
                    row = row, index = i, count = others.size, selectMode = selectMode, selected = row.key in selected,
                    is24h = is24h, onClick = { if (!guard.swallowClick()) onRowClick(row) },
                )
            }
        }
        item(key = "footnote", contentType = "footnote") {
            Text(
                (if (othersSupported) "Files other apps move to Android's trash also show here. " else "") +
                    "Samsung's own Recycle bin stays in Samsung's apps.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 18.dp),
            )
        }
    }
}

/** "30 days until deletion" with a dotted line running to the edge. */
@Composable
private fun DottedHeader(label: String) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 18.dp, top = 18.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = color)
        Spacer(Modifier.width(10.dp))
        Canvas(Modifier.weight(1f).height(2.dp)) {
            drawLine(
                color.copy(alpha = 0.45f), Offset(0f, size.height / 2), Offset(size.width, size.height / 2),
                strokeWidth = 1.5.dp.toPx(), cap = StrokeCap.Round,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(0.1f, 4.dp.toPx())),
            )
        }
    }
}

@Composable
private fun SelectCircle(checked: Boolean) {
    val primary = MaterialTheme.colorScheme.primary
    Box(
        Modifier.size(22.dp).clip(CircleShape)
            .then(if (checked) Modifier.background(primary) else Modifier.border(1.5.dp, MaterialTheme.colorScheme.outline, CircleShape)),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(15.dp))
    }
}

private fun cellShape(index: Int, count: Int): Shape {
    val r = 20.dp
    return when {
        count == 1 -> RoundedCornerShape(r)
        index == 0 -> RoundedCornerShape(topStart = r, topEnd = r)
        index == count - 1 -> RoundedCornerShape(bottomStart = r, bottomEnd = r)
        else -> androidx.compose.ui.graphics.RectangleShape
    }
}

@Composable
private fun cellColor(): Color {
    val g = LocalGlass.current
    return when {
        !g.enabled -> MaterialTheme.colorScheme.surfaceContainerLow
        g.dark -> Color.White.copy(alpha = 0.09f)
        else -> Color.White.copy(alpha = 0.68f)
    }
}

@Composable
private fun BinRow(
    row: BinRowUi,
    index: Int,
    count: Int,
    selectMode: Boolean,
    selected: Boolean,
    is24h: Boolean,
    onClick: () -> Unit,
) {
    val divider = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    val tint = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent
    Row(
        Modifier
            .padding(horizontal = 14.dp)
            .fillMaxWidth()
            .clip(cellShape(index, count))
            .background(cellColor())
            .background(tint)
            .drawBehind {
                if (index > 0) {
                    val start = (if (selectMode) 110 else 76).dp.toPx()
                    drawLine(divider, Offset(start, 0f), Offset(size.width - 16.dp.toPx(), 0f), strokeWidth = 1.dp.toPx())
                }
            }
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectMode) {
            SelectCircle(selected)
            Spacer(Modifier.width(12.dp))
        }
        FileThumb(row.thumb, 48.dp, corner = 10.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                row.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
            )
            val date = binDate(row.deletedAt, is24h)
            Text(
                if (row.fromOtherApp && row.daysLeft != null) "$date · ${row.daysLeft} days left" else date,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(row.sizeText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** What the tap sheet shows: the file, where it was, when it goes, and Restore / Delete forever. */
@Composable
fun BinItemSheetContent(row: BinRowUi, is24h: Boolean, onRestore: () -> Unit, onDelete: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp).padding(bottom = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FileThumb(row.thumb, 60.dp, corner = 12.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(row.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.MiddleEllipsis)
                Text(row.sizeText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(16.dp))
        SheetLine("Deleted", binDate(row.deletedAt, is24h))
        row.daysLeft?.let { SheetLine("Deleted for good in", if (it <= 0) "less than a day" else "$it day" + if (it == 1) "" else "s") }
        row.location?.let { SheetLine(if (row.fromOtherApp) "In" else "From", it) }
        if (row.fromOtherApp) SheetLine("Bin", "Android trash (other apps)")
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onDelete, modifier = Modifier.weight(1f), colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                Icon(Icons.Filled.DeleteForever, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Delete forever")
            }
            Button(onClick = onRestore, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.RestoreFromTrash, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Restore")
            }
        }
    }
}

@Composable
private fun SheetLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(140.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.MiddleEllipsis)
    }
}

/**
 * Long-press a row, then drag: every row from the first one to the one under the finger is
 * selected (added to what was selected before). Near the top/bottom edge the list scrolls by itself.
 */
private fun Modifier.dragToSelect(
    state: LazyListState,
    scope: CoroutineScope,
    orderedKeys: () -> List<String>,
    baseSelection: () -> Set<String>,
    onStart: () -> Unit,
    onRange: (base: Set<String>, range: Set<String>) -> Unit,
    onEnd: () -> Unit,
): Modifier = pointerInput(state) {
    var anchor = -1
    var current = -1
    var base = emptySet<String>()
    var lastY = 0f
    var speed = 0f
    var autoJob: Job? = null

    fun indexAt(y: Float): Int {
        val info = state.layoutInfo
        val yy = y.toInt() + info.viewportStartOffset
        val hit = info.visibleItemsInfo.firstOrNull { yy >= it.offset && yy < it.offset + it.size } ?: return -1
        val k = (hit.key as? String)?.takeIf { it.startsWith(ROW) } ?: return -1
        return orderedKeys().indexOf(k.removePrefix(ROW))
    }

    fun emit() {
        val keys = orderedKeys()
        if (anchor !in keys.indices || current !in keys.indices) return
        val lo = minOf(anchor, current)
        val hi = maxOf(anchor, current)
        onRange(base, keys.subList(lo, hi + 1).toSet())
    }

    fun update() {
        val i = indexAt(lastY)
        if (i >= 0 && i != current) { current = i; emit() }
    }

    fun stop() {
        autoJob?.cancel(); autoJob = null; speed = 0f
        if (anchor >= 0) onEnd()
        anchor = -1; current = -1
    }

    detectDragGesturesAfterLongPress(
        onDragStart = { offset ->
            lastY = offset.y
            val i = indexAt(offset.y)
            if (i >= 0) {
                anchor = i; current = i
                base = baseSelection()
                onStart()
                emit()
            }
        },
        onDrag = { change, _ ->
            if (anchor < 0) return@detectDragGesturesAfterLongPress
            change.consume()
            lastY = change.position.y
            update()
            val edge = 72.dp.toPx()
            val max = 22.dp.toPx()
            val h = size.height.toFloat()
            speed = when {
                lastY < edge -> -max * ((edge - lastY) / edge).coerceIn(0f, 1f)
                lastY > h - edge -> max * ((lastY - (h - edge)) / edge).coerceIn(0f, 1f)
                else -> 0f
            }
            if (speed != 0f && autoJob == null) {
                autoJob = scope.launch {
                    while (isActive && speed != 0f) {
                        state.scrollBy(speed)
                        update()
                        delay(16)
                    }
                    autoJob = null
                }
            }
        },
        onDragEnd = { stop() },
        onDragCancel = { stop() },
    )
}
