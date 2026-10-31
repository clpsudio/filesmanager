package com.clpstudio.filesmanager.ui.vault

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.DriveFolderUpload
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.clpstudio.filesmanager.data.FileKind
import com.clpstudio.filesmanager.data.StoragePaths
import com.clpstudio.filesmanager.security.CredentialType
import com.clpstudio.filesmanager.ui.components.ConfirmDialog
import com.clpstudio.filesmanager.ui.components.FolderPickerDialog
import com.clpstudio.filesmanager.ui.components.GlassTopBar
import com.clpstudio.filesmanager.ui.components.kindColor
import com.clpstudio.filesmanager.ui.components.kindIcon
import com.clpstudio.filesmanager.ui.glass.GlassScaffold
import com.clpstudio.filesmanager.ui.glass.glassPanel
import com.clpstudio.filesmanager.ui.security.CredentialSetupForm
import com.clpstudio.filesmanager.ui.security.SecureScreen
import com.clpstudio.filesmanager.ui.security.UnlockForm
import com.clpstudio.filesmanager.vault.Vault
import com.clpstudio.filesmanager.vault.VaultImages
import com.clpstudio.filesmanager.vault.VaultItem
import com.clpstudio.filesmanager.vault.VaultState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Marks a vault screen as showing: FLAG_SECURE + "lock when you leave the vault" counting. */
@Composable
fun VaultScreenScope() {
    SecureScreen()
    DisposableEffect(Unit) {
        Vault.screenShown()
        onDispose { Vault.screenGone() }
    }
}

/** Home → Vault: set up, unlock, or the vault's files. */
@Composable
fun VaultScreen(
    onBack: () -> Unit,
    onOpen: (VaultItem) -> Unit,
    onSettings: () -> Unit,
) {
    VaultScreenScope()
    val state by Vault.state.collectAsState()
    when (state) {
        VaultState.NOT_SET_UP -> VaultSetup(onBack)
        VaultState.LOCKED -> VaultUnlock(onBack, onSettings)
        VaultState.UNLOCKED -> VaultList(onBack, onOpen, onSettings)
    }
}

/** The warning every vault setup shows: there is no way back from a forgotten PIN. */
const val NO_RECOVERY_WARNING =
    "There is NO recovery. If you forget the PIN or password, uninstall Files, or clear its data, " +
        "the files in the vault are lost for good. Nobody can open them, not even us."

@Composable
fun VaultSetup(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var understood by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    GlassScaffold(topBar = { GlassTopBar("Set up vault", onBack = onBack) }) { padding ->
        Column(
            Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.fillMaxWidth().glassPanel(RoundedCornerShape(22.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Shield, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.size(10.dp))
                    Text("Private vault", style = MaterialTheme.typography.titleMedium)
                }
                Text(
                    "Files you move here are hidden from every other app and from the rest of Files. " +
                        "They're encrypted with AES-256, and the key is locked by this phone's secure hardware together with your PIN or password.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(MaterialTheme.colorScheme.errorContainer).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.onErrorContainer)
                    Spacer(Modifier.size(10.dp))
                    Text("No recovery", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onErrorContainer, fontWeight = FontWeight.SemiBold)
                }
                Text(NO_RECOVERY_WARNING, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                Row(
                    Modifier.fillMaxWidth().toggleable(understood, role = androidx.compose.ui.semantics.Role.Checkbox) { understood = it },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(understood, onCheckedChange = null)
                    Text("I understand", color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
            if (understood) {
                Column(Modifier.fillMaxWidth().glassPanel(RoundedCornerShape(22.dp)).padding(16.dp)) {
                    Text("Vault PIN or password", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text("Separate from the app lock.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    CredentialSetupForm(
                        confirmLabel = "Create vault",
                        busy = busy,
                        onSubmit = { type, secret ->
                            busy = true
                            scope.launch {
                                val r = runCatching { Vault.setup(type, secret) }
                                busy = false
                                Toast.makeText(context, if (r.isSuccess) "Vault created" else (r.exceptionOrNull()?.message ?: "Couldn't create the vault"), Toast.LENGTH_SHORT).show()
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun VaultUnlock(onBack: () -> Unit, onSettings: () -> Unit) {
    var lost by remember { mutableStateOf<String?>(null) }
    GlassScaffold(topBar = {
        GlassTopBar("Vault", onBack = onBack, actions = { IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, "Vault settings") } })
    }) { padding ->
        Column(
            Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(top = padding.calculateTopPadding()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            val type = remember { Vault.store.credentialType ?: CredentialType.PIN }
            UnlockForm(
                type = type,
                title = "Vault is locked",
                subtitle = lost ?: "${Vault.count.collectAsState().value} files · enter your vault ${if (type == CredentialType.PIN) "PIN" else "password"}",
                check = { s ->
                    when (val r = Vault.unlock(s)) {
                        Vault.UnlockResult.Ok -> null
                        is Vault.UnlockResult.Wrong -> r.message
                        is Vault.UnlockResult.Lost -> { lost = r.message; r.message }
                    }
                },
                onUnlocked = {},
                waitUntil = { Vault.attempts.let { it.lockedUntil to it.failures } },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VaultList(onBack: () -> Unit, onOpen: (VaultItem) -> Unit, onSettings: () -> Unit) {
    val context = LocalContext.current
    val items by Vault.items.collectAsState()
    val selected = remember { mutableStateListOf<String>() }
    var confirmDelete by remember { mutableStateOf<List<VaultItem>?>(null) }
    var pickDestFor by remember { mutableStateOf<List<VaultItem>?>(null) }
    var menu by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val picked = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val files = withContext(Dispatchers.IO) { uris.mapNotNull { VaultUris.fileOf(context, it) } }
            val skipped = uris.size - files.size
            if (files.isNotEmpty()) Vault.moveIn(context, files)
            if (skipped > 0) Toast.makeText(context, "$skipped couldn't be added from there. Select them in Files and use Move to vault.", Toast.LENGTH_LONG).show()
        }
    }
    val selItems = items.filter { it.id in selected }
    BackHandler(selected.isNotEmpty()) { selected.clear() }
    LaunchedEffect(items) { selected.retainAll(items.map { it.id }.toSet()) }

    GlassScaffold(
        topBar = {
            if (selected.isEmpty()) {
                GlassTopBar(
                    "Vault", onBack = onBack,
                    subtitle = "${items.size} file${if (items.size == 1) "" else "s"} · ${Formatter.formatShortFileSize(context, items.sumOf { it.size })}",
                    actions = {
                        IconButton(onClick = { Vault.lock() }) { Icon(Icons.Filled.Lock, "Lock vault") }
                        IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, "Vault settings") }
                    },
                )
            } else {
                GlassTopBar(
                    "${selected.size} selected",
                    navigation = { IconButton(onClick = { selected.clear() }) { Icon(Icons.Filled.Close, "Cancel selection") } },
                    actions = {
                        IconButton(onClick = { Vault.moveOut(context, selItems, null) { selected.clear() } }) { Icon(Icons.Filled.DriveFolderUpload, "Move out") }
                        IconButton(onClick = { confirmDelete = selItems }) { Icon(Icons.Filled.DeleteForever, "Delete forever") }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text(if (selected.size == items.size) "Select none" else "Select all") }, onClick = {
                                    menu = false
                                    if (selected.size == items.size) selected.clear() else { selected.clear(); selected.addAll(items.map { it.id }) }
                                })
                                DropdownMenuItem(text = { Text("Move out (back where it was)") }, leadingIcon = { Icon(Icons.Filled.DriveFolderUpload, null) }, onClick = { menu = false; Vault.moveOut(context, selItems, null) { selected.clear() } })
                                DropdownMenuItem(text = { Text("Move out to...") }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.DriveFileMove, null) }, onClick = { menu = false; pickDestFor = selItems })
                                DropdownMenuItem(text = { Text("Delete forever") }, leadingIcon = { Icon(Icons.Filled.DeleteForever, null) }, onClick = { menu = false; confirmDelete = selItems })
                            }
                        }
                    },
                )
            }
        },
        floatingActionButton = {
            if (selected.isEmpty()) ExtendedFloatingActionButton(
                onClick = { com.clpstudio.filesmanager.security.AppLock.allowExternalScreen(); runCatching { picked.launch(arrayOf("*/*")) } },
                icon = { Icon(Icons.Filled.Add, null) },
                text = { Text("Add files") },
            )
        },
    ) { padding ->
        if (items.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(Icons.Filled.Shield, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(12.dp))
                Text("The vault is empty", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Tap Add files, or select files anywhere in Files and choose Move to vault from the ⋮ menu.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(108.dp),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = padding.calculateTopPadding() + 8.dp, bottom = 96.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(items, key = { it.id }) { item ->
                    val isSel = item.id in selected
                    VaultCell(
                        item, isSel,
                        Modifier.combinedClickable(
                            onClick = { if (selected.isNotEmpty()) { if (!selected.remove(item.id)) selected.add(item.id) } else onOpen(item) },
                            onLongClick = { if (!selected.remove(item.id)) selected.add(item.id) },
                        ),
                    )
                }
            }
        }
    }

    confirmDelete?.let { list ->
        ConfirmDialog(
            title = if (list.size == 1) "Delete ${list[0].name} forever?" else "Delete ${list.size} files forever?",
            text = "They don't go to the Bin. This can't be undone.",
            confirm = "Delete forever",
            destructive = true,
            onConfirm = { confirmDelete = null; Vault.deleteForever(context, list) { selected.clear() } },
            onDismiss = { confirmDelete = null },
        )
    }
    pickDestFor?.let { list ->
        FolderPickerDialog(
            start = File(StoragePaths.primary),
            title = "Move out to",
            confirm = "Move here",
            onPick = { dir -> pickDestFor = null; Vault.moveOut(context, list, dir) { selected.clear() } },
            onDismiss = { pickDestFor = null },
        )
    }
}

@Composable
private fun VaultCell(item: VaultItem, selected: Boolean, modifier: Modifier) {
    val thumb by produceState<Bitmap?>(Vault.thumbs.get(item.id), item.id) {
        if (value == null && item.kind in setOf(FileKind.IMAGE, FileKind.VIDEO, FileKind.AUDIO)) {
            value = withContext(Dispatchers.IO) { VaultImages.thumb(item) }
        }
    }
    Column(modifier.clip(RoundedCornerShape(14.dp))) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(14.dp)).background(kindColor(item.kind).copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            val b = thumb
            if (b != null) {
                Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                if (item.kind == FileKind.VIDEO) Icon(Icons.Filled.PlayCircle, null, Modifier.size(32.dp), tint = Color.White)
            } else {
                Icon(kindIcon(item.kind), null, Modifier.size(40.dp), tint = kindColor(item.kind))
            }
            if (selected) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)))
                Icon(Icons.Filled.CheckCircle, "Selected", Modifier.align(Alignment.TopEnd).padding(6.dp).size(24.dp).background(Color.White, CircleShape), tint = MaterialTheme.colorScheme.primary)
            }
        }
        Text(item.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp))
    }
}

/** A real path for a file chosen in the system picker (so the original can be removed after the move). */
object VaultUris {
    fun fileOf(context: Context, uri: Uri): File? {
        val f = runCatching { resolve(context, uri) }.getOrNull() ?: return null
        return f.takeIf { it.isFile && it.parentFile?.canWrite() == true }
    }

    private fun resolve(context: Context, uri: Uri): File? {
        if (uri.scheme == "file") return uri.path?.let(::File)
        if (DocumentsContract.isDocumentUri(context, uri)) {
            val id = DocumentsContract.getDocumentId(uri)
            when (uri.authority) {
                "com.android.externalstorage.documents" -> {
                    val parts = id.split(":", limit = 2)
                    val rel = parts.getOrElse(1) { "" }
                    return if (parts[0].equals("primary", true)) File(StoragePaths.primary, rel) else File("/storage/${parts[0]}", rel)
                }
                context.packageName + ".documents" -> return File(id)
                "com.android.providers.downloads.documents" -> if (id.startsWith("raw:")) return File(id.removePrefix("raw:"))
                "com.android.providers.media.documents" -> {
                    val parts = id.split(":", limit = 2)
                    val base = when (parts[0]) {
                        "image" -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                        "video" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                        "audio" -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                        else -> MediaStore.Files.getContentUri("external")
                    }
                    return dataColumn(context, base, "_id = ?", arrayOf(parts.getOrElse(1) { "" }))
                }
            }
        }
        return dataColumn(context, uri, null, null)
    }

    @Suppress("DEPRECATION")
    private fun dataColumn(context: Context, uri: Uri, sel: String?, args: Array<String>?): File? =
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), sel, args, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) File(c.getString(0)) else null
        }
}
