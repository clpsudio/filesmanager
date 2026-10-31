package com.clpstudio.filesmanager.viewer

import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.testTag
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clpstudio.filesmanager.data.FileOps
import com.clpstudio.filesmanager.data.FileTypes
import com.clpstudio.filesmanager.data.apk.AndroidVersions
import com.clpstudio.filesmanager.data.apk.ApkDetails
import com.clpstudio.filesmanager.data.apk.ApkFiles
import com.clpstudio.filesmanager.data.apk.ApkInspector
import com.clpstudio.filesmanager.data.apk.ApkPermissions
import com.clpstudio.filesmanager.ui.Opener
import com.clpstudio.filesmanager.ui.components.GlassTopBar
import com.clpstudio.filesmanager.ui.components.formatDate
import com.clpstudio.filesmanager.ui.components.formatDateTime
import com.clpstudio.filesmanager.ui.components.formatSize
import com.clpstudio.filesmanager.ui.glass.GlassScaffold
import com.clpstudio.filesmanager.ui.glass.glassPanel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** What the phone itself offers, so the page can say "works on this phone" (overridable in tests). */
data class DeviceProfile(val sdk: Int, val abis: List<String>) {
    companion object {
        fun current() = DeviceProfile(Build.VERSION.SDK_INT, Build.SUPPORTED_ABIS.toList())
    }
}

/** Pure checks the page shows (unit tested). */
object ApkCompat {
    fun sdkOk(minSdk: Int?, device: DeviceProfile): Boolean = minSdk == null || device.sdk >= minSdk
    /** No native code = runs on any CPU; else at least one ABI must be one this phone runs. */
    fun abiOk(abis: List<String>, device: DeviceProfile): Boolean = abis.isEmpty() || abis.any { it in device.abis }
}

/**
 * The APK preview page: a clean, readable "app card" for an .apk (or a split bundle), like the PDF / document
 * viewers, fully offline. Header (icon, name, version, size, package, Install / Update / Open), then collapsible
 * cards (long-press any value to copy it).
 */
@Composable
fun ApkViewerScreen(
    path: String,
    onBack: () -> Unit,
    onBrowseFiles: (File) -> Unit,
    onOpenText: (File) -> Unit,
) {
    val context = LocalContext.current
    val file = remember(path) { File(path) }
    val loaded by produceState<Result<ApkDetails>?>(null, path) {
        value = withContext(Dispatchers.IO) { runCatching { ApkInspector.load(context, file) } }
    }
    val d = loaded?.getOrNull()
    if (d == null) {
        GlassScaffold(topBar = { GlassTopBar(file.name, onBack, subtitle = "APK") }) { padding ->
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                val failure = loaded?.exceptionOrNull()
                if (failure == null) CircularProgressIndicator()
                else Text("Couldn't read this APK.\n${failure.message.orEmpty()}", modifier = Modifier.padding(32.dp), textAlign = TextAlign.Center)
            }
        }
        return
    }
    val scope = rememberCoroutineScope()
    ApkViewerContent(
        d, DeviceProfile.current(), onBack,
        onInstall = { Opener.installApk(context, FileOps.uriFor(context, d.apk)) },
        onInstallBundle = { plan -> com.clpstudio.filesmanager.install.ApkInstaller.installBundle(context, d.file, plan.chosenEntries, plan.obb) },
        onOpenApp = {
            val pkg = d.manifest?.packageName ?: return@ApkViewerContent
            context.packageManager.getLaunchIntentForPackage(pkg)?.let { com.clpstudio.filesmanager.data.SafeStart.start(context, it, "Couldn't open the app") }
        },
        onOtherApp = { FileOps.openWithOtherApp(context, FileOps.uriFor(context, d.file), FileTypes.mimeOf(d.file.name)) },
        onBrowseFiles = { onBrowseFiles(d.file) },
        onViewManifest = {
            scope.launch {
                val f = withContext(Dispatchers.IO) { ApkInspector.manifestXml(context, d) }
                if (f != null) onOpenText(f) else Toast.makeText(context, "The manifest couldn't be decoded", Toast.LENGTH_SHORT).show()
            }
        },
    )
}

@Composable
fun ApkViewerContent(
    d: ApkDetails,
    device: DeviceProfile,
    onBack: () -> Unit,
    onInstall: () -> Unit = {},
    onInstallBundle: (com.clpstudio.filesmanager.data.apk.SplitPlan) -> Unit = {},
    showSplitSheet: Boolean = false,
    onOpenApp: () -> Unit = {},
    onOtherApp: () -> Unit = {},
    onBrowseFiles: () -> Unit = {},
    onViewManifest: () -> Unit = {},
    expandAll: Boolean = false,
) {
    val m = d.manifest
    var splitSheet by remember { mutableStateOf(showSplitSheet) }
    val plan = d.splitPlan
    if (splitSheet && plan != null) {
        SplitInstallSheet(d, plan, onInstall = { splitSheet = false; onInstallBundle(plan) }, onDismiss = { splitSheet = false })
    }
    GlassScaffold(topBar = { GlassTopBar(d.label, onBack, subtitle = if (d.isBundle) "Split APK bundle" else "Android app (APK)") }) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "header") { Header(d, onInstall, onOpenApp, onOtherApp, onInstallBundle = { splitSheet = true }) }
            d.error?.let { e -> item(key = "error") { Notice(Icons.Filled.Warning, e, MaterialTheme.colorScheme.error) } }
            if (d.isBundle) item(key = "bundle") {
                Notice(
                    Icons.Filled.Layers,
                    "Split APK bundle: ${d.bundleParts.size} APK${if (d.bundleParts.size == 1) "" else "s"} inside (base + parts for languages, screen sizes or CPUs). " +
                        "Details below are from ${d.apk.name}. " + (plan?.problem ?: "Install picks only the parts this phone needs."),
                    MaterialTheme.colorScheme.primary,
                )
            }
            if (m?.split != null) item(key = "split") {
                Notice(Icons.Filled.Layers, "This is a split part (${m.split}) of a bigger app. It only installs together with its base APK.", MaterialTheme.colorScheme.primary)
            } else if (m?.isSplitRequired == true && !d.isBundle) item(key = "splitreq") {
                Notice(Icons.Filled.Layers, "This app needs extra split APKs to install (it was made as a Play bundle). This single file may not install on its own.", MaterialTheme.colorScheme.tertiary)
            }
            if (d.signatureMatches == false) item(key = "sigwarn") {
                Notice(Icons.Filled.Warning, "Signed by a different key than the installed app. Android won't update over it: uninstall the installed app first (its data is lost).", MaterialTheme.colorScheme.error)
            }

            item(key = "compat") {
                Section("Compatibility", Icons.Filled.PhoneAndroid, initiallyOpen = true, forceOpen = expandAll) {
                    val sdkOk = ApkCompat.sdkOk(m?.minSdk, device)
                    val abiOk = ApkCompat.abiOk(d.scan.abis, device)
                    StatusLine(
                        ok = sdkOk && abiOk,
                        text = when {
                            !sdkOk -> "Needs ${AndroidVersions.name(m?.minSdk ?: 0)} — this phone has ${AndroidVersions.name(device.sdk)}"
                            !abiOk -> "Built for ${d.scan.abis.joinToString()} CPUs only — this phone runs ${device.abis.joinToString()}"
                            else -> "Works on this phone (${AndroidVersions.name(device.sdk)})"
                        },
                    )
                    m?.minSdk?.let { InfoRow("Requires", AndroidVersions.minLabel(it)) } ?: m?.minSdkCodename?.let { InfoRow("Requires", "Preview SDK $it") }
                    m?.targetSdk?.let { InfoRow("Made for", AndroidVersions.label(it)) }
                    m?.compileSdk?.let { InfoRow("Built with", AndroidVersions.label(it)) }
                    InfoRow("CPU (ABIs)", if (d.scan.abis.isEmpty()) "Any (no native code)" else d.scan.abis.joinToString(", "))
                    if (m?.debuggable == true) InfoRow("Build", "Debuggable (test build)")
                }
            }
            item(key = "installed") {
                Section("Installed status", Icons.Filled.InstallMobile, initiallyOpen = true, forceOpen = expandAll) {
                    val inst = d.installed
                    InfoRow("On this phone", installLine(d))
                    if (inst != null) {
                        InfoRow("Installed version", listOfNotNull(inst.versionName, "(${inst.versionCode})").joinToString(" "))
                        InfoRow("This file", d.versionText)
                        if (inst.firstInstall > 0) InfoRow("First installed", formatDateTime(inst.firstInstall))
                        if (inst.lastUpdate > 0) InfoRow("Last updated", formatDateTime(inst.lastUpdate))
                        InfoRow("Same signer", when (d.signatureMatches) { true -> "Yes — an update will install"; false -> "No — different signing key"; null -> "Unknown" })
                    }
                }
            }
            item(key = "signature") {
                Section("Signature", Icons.Filled.Verified, forceOpen = expandAll) {
                    val sig = d.signing
                    if (sig.certs.isEmpty()) StatusLine(false, "No signature found — Android won't install an unsigned APK")
                    else {
                        val c = sig.certs.first()
                        if (c.isDebug) StatusLine(null, "Signed with a debug key (a test build)")
                        if (c.expired()) StatusLine(false, "The certificate has expired")
                        InfoRow("Schemes", sig.schemes.joinToString(", ").ifEmpty { "—" })
                        InfoRow("Signer", readableDn(c.subject))
                        if (c.issuer != c.subject) InfoRow("Issuer", readableDn(c.issuer))
                        InfoRow("Valid", "${certDate(c.notBefore)} → ${certDate(c.notAfter)}")
                        InfoRow("SHA-256", c.sha256, mono = true, shown = splitFingerprint(c.sha256))
                        InfoRow("SHA-1", c.sha1, mono = true, shown = splitFingerprint(c.sha1))
                        InfoRow("Algorithm", "${c.algorithm} · ${c.key}")
                        InfoRow("Serial", c.serial, mono = true)
                        d.signatureMatches?.let { InfoRow("Matches installed app", if (it) "Yes" else "No") }
                        if (sig.certs.size > 1) InfoRow("Signers", "${sig.certs.size} certificates")
                    }
                }
            }
            item(key = "permissions") {
                val perms = m?.permissions.orEmpty()
                val danger = ApkPermissions.dangerousCount(perms)
                Section(
                    "Permissions", Icons.Filled.Shield, forceOpen = expandAll,
                    badge = if (perms.isEmpty()) "None" else "${perms.size}" + if (danger > 0) " · $danger sensitive" else "",
                ) {
                    if (perms.isEmpty()) Text("This app asks for no permissions.", style = MaterialTheme.typography.bodyMedium)
                    for ((group, list) in ApkPermissions.grouped(perms) { d.permissionLabels[it] }) {
                        Text(group, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
                        for (p in list) PermissionRow(p)
                    }
                }
            }
            item(key = "components") {
                Section("Components", Icons.Filled.Widgets, forceOpen = expandAll) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                        CountChip("Activities", m?.activities ?: 0, Modifier.weight(1f))
                        CountChip("Services", m?.services ?: 0, Modifier.weight(1f))
                        CountChip("Receivers", m?.receivers ?: 0, Modifier.weight(1f))
                        CountChip("Providers", m?.providers ?: 0, Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(6.dp))
                    InfoRow("Code files", "${d.scan.dexFiles} dex")
                }
            }
            item(key = "files") {
                Section("Files inside", Icons.Filled.FolderZip, initiallyOpen = true, forceOpen = expandAll, badge = "${d.scan.entries}") {
                    Text(
                        if (d.isBundle) "Browse the APKs inside this bundle, open or extract them."
                        else "Browse the APK like a ZIP. The manifest and res/…xml files open as readable XML; images and text open in Files' viewers.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onBrowseFiles, modifier = Modifier.weight(1f)) { Icon(Icons.Filled.FolderZip, null, Modifier.size(18.dp)); Text("  Browse files") }
                        if (d.scan.manifest != null) OutlinedButton(onClick = onViewManifest, modifier = Modifier.weight(1f)) { Icon(Icons.Filled.Code, null, Modifier.size(18.dp)); Text("  Manifest") }
                    }
                }
            }
            item(key = "file") {
                Section("File", Icons.Filled.Info, forceOpen = expandAll) {
                    InfoRow("Name", d.file.name)
                    InfoRow("Location", d.file.parent ?: "", mono = false)
                    InfoRow("Size", formatSize(d.file.length()))
                    if (d.file.lastModified() > 0) InfoRow("Modified", formatDateTime(d.file.lastModified()))
                }
            }
        }
    }
}

private fun installLine(d: ApkDetails): String = when (d.installState) {
    ApkFiles.InstallState.NOT_INSTALLED -> "Not installed"
    ApkFiles.InstallState.SAME -> "This version is installed"
    ApkFiles.InstallState.OLDER_INSTALLED -> "An older version is installed — this file is an update"
    ApkFiles.InstallState.NEWER_INSTALLED -> "A newer version is installed"
}

private fun certDate(millis: Long): String =
    java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault()).format(java.util.Date(millis))

/** A long fingerprint in lines of 10 bytes, so it never breaks in the middle of a byte. */
internal fun splitFingerprint(fp: String, perLine: Int = 10): String =
    fp.split(':').chunked(perLine).joinToString("\n") { it.joinToString(":") }

/** "CN=Tiny Test,O=CLP Studio,C=IN" -> "Tiny Test · CLP Studio · IN". */
internal fun readableDn(dn: String): String {
    val parts = dn.split(Regex(",(?=\\s*[A-Za-z.0-9]+=)")).map { it.trim() }
    val order = listOf("CN", "OU", "O", "L", "ST", "C")
    val map = parts.mapNotNull { p -> p.indexOf('=').takeIf { it > 0 }?.let { p.substring(0, it).uppercase() to p.substring(it + 1).trim().replace("\\,", ",") } }
    val shown = order.mapNotNull { k -> map.firstOrNull { it.first == k }?.second }.filter { it.isNotBlank() }
    return shown.joinToString(" · ").ifEmpty { dn }
}

@Composable
private fun Header(d: ApkDetails, onInstall: () -> Unit, onOpenApp: () -> Unit, onOtherApp: () -> Unit, onInstallBundle: () -> Unit = {}) {
    val m = d.manifest
    Column(Modifier.fillMaxWidth().glassPanel(RoundedCornerShape(28.dp)).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val icon = d.icon
            Box(Modifier.size(84.dp).clip(RoundedCornerShape(22.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)), contentAlignment = Alignment.Center) {
                if (icon != null) Image(icon.asImageBitmap(), d.label, Modifier.size(76.dp).clip(RoundedCornerShape(18.dp)))
                else Icon(Icons.Filled.Android, null, Modifier.size(52.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                CopyText(d.label, MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold))
                CopyText("Version ${d.versionText}", MaterialTheme.typography.bodyMedium, copyValue = m?.versionName ?: d.versionText)
                Spacer(Modifier.height(4.dp))
                CopyText(m?.packageName ?: "Unknown package", MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Pill(formatSize(d.file.length()))
            m?.minSdk?.let { Pill(AndroidVersions.name(it) + "+") }
            val danger = ApkPermissions.dangerousCount(m?.permissions.orEmpty())
            if (danger > 0) Pill("$danger sensitive permission${if (danger == 1) "" else "s"}", MaterialTheme.colorScheme.error)
        }
        if (d.installed != null) Text(installLine(d), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val inst = d.installed
            when {
                // Item 6: a split bundle installs right here (the parts this phone needs, one system prompt).
                d.isBundle && d.splitPlan?.canInstall == true -> Button(onClick = onInstallBundle, modifier = Modifier.weight(1f).testTag("installBundle")) {
                    Icon(if (inst != null && d.installState == ApkFiles.InstallState.OLDER_INSTALLED) Icons.Filled.SystemUpdate else Icons.Filled.InstallMobile, null, Modifier.size(18.dp))
                    Text(if (inst != null && d.installState == ApkFiles.InstallState.OLDER_INSTALLED) "  Update" else if (inst != null && d.installState == ApkFiles.InstallState.SAME) "  Reinstall" else "  Install")
                }
                d.isBundle || m?.split != null -> OutlinedButton(onClick = onOtherApp, modifier = Modifier.weight(1f)) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, null, Modifier.size(18.dp)); Text("  Open with another app")
                }
                d.installState == ApkFiles.InstallState.NOT_INSTALLED -> Button(onClick = onInstall, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.InstallMobile, null, Modifier.size(18.dp)); Text("  Install")
                }
                d.installState == ApkFiles.InstallState.OLDER_INSTALLED -> Button(onClick = onInstall, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.SystemUpdate, null, Modifier.size(18.dp)); Text("  Update")
                }
                d.installState == ApkFiles.InstallState.SAME -> OutlinedButton(onClick = onInstall, modifier = Modifier.weight(1f)) { Text("Reinstall") }
                else -> Unit
            }
            if (inst != null && inst.launchable && !d.isBundle) Button(onClick = onOpenApp, modifier = Modifier.weight(1f)) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, null, Modifier.size(18.dp)); Text("  Open")
            }
        }
    }
}

@Composable
private fun Pill(text: String, color: Color = MaterialTheme.colorScheme.primary) {
    Text(
        text, style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.12f)).padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
private fun Notice(icon: ImageVector, text: String, color: Color) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(color.copy(alpha = 0.12f)).padding(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun StatusLine(ok: Boolean?, text: String) {
    val color = when (ok) { true -> MaterialTheme.colorScheme.primary; false -> MaterialTheme.colorScheme.error; null -> MaterialTheme.colorScheme.tertiary }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp)) {
        Icon(if (ok == true) Icons.Filled.CheckCircle else Icons.Filled.Warning, null, tint = color, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun Section(
    title: String,
    icon: ImageVector,
    initiallyOpen: Boolean = false,
    forceOpen: Boolean = false,
    badge: String? = null,
    content: @Composable () -> Unit,
) {
    var open by rememberSaveable(title) { mutableStateOf(initiallyOpen) }
    val shown = open || forceOpen
    val turn by animateFloatAsState(if (shown) 180f else 0f, label = "chevron")
    Column(Modifier.fillMaxWidth().glassPanel(RoundedCornerShape(24.dp))) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).clickable { open = !shown }.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (badge != null) Text(badge, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
            Icon(Icons.Filled.ExpandMore, if (shown) "Collapse" else "Expand", Modifier.rotate(turn))
        }
        AnimatedVisibility(shown) {
            Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 14.dp)) {
                HorizontalDivider(Modifier.padding(bottom = 10.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                content()
            }
        }
    }
}

private fun copy(context: Context, label: String, value: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    cm.setPrimaryClip(ClipData.newPlainText(label, value))
    if (Build.VERSION.SDK_INT < 33) Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun InfoRow(label: String, value: String, mono: Boolean = false, shown: String = value) {
    val context = LocalContext.current
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .combinedClickable(onClick = {}, onLongClick = { copy(context, label, value) })
            .padding(vertical = 5.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            shown,
            style = if (mono) MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, lineHeight = 18.sp) else MaterialTheme.typography.bodyLarge,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CopyText(text: String, style: androidx.compose.ui.text.TextStyle, color: Color = Color.Unspecified, copyValue: String = text) {
    val context = LocalContext.current
    Text(text, style = style, color = color, modifier = Modifier.combinedClickable(onClick = {}, onLongClick = { copy(context, "APK", copyValue) }))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PermissionRow(p: ApkPermissions.Info) {
    val context = LocalContext.current
    val color = when (p.level) {
        ApkPermissions.Level.DANGEROUS -> MaterialTheme.colorScheme.error
        ApkPermissions.Level.SPECIAL -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .combinedClickable(onClick = {}, onLongClick = { copy(context, "Permission", p.name) })
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(p.label, style = MaterialTheme.typography.bodyLarge)
            Text(p.name, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        when (p.level) {
            ApkPermissions.Level.DANGEROUS -> Pill("Sensitive", color)
            ApkPermissions.Level.SPECIAL -> Pill("Special", color)
            else -> Unit
        }
    }
}

@Composable
private fun CountChip(label: String, count: Int, modifier: Modifier = Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)).padding(horizontal = 4.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("$count", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
        Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}


/** Item 6: what a bundle install will put on this phone, before the system prompt. */
@Composable
fun SplitInstallSheet(d: ApkDetails, plan: com.clpstudio.filesmanager.data.apk.SplitPlan, onInstall: () -> Unit, onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Layers, null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text("Install ${d.label}?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag("splitSheet")) {
                Text(
                    "${plan.chosen.size} of ${plan.chosen.size + plan.skipped.size} parts for this phone:",
                    style = MaterialTheme.typography.bodyMedium,
                )
                plan.chosen.forEach { c ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.CheckCircle, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(c.part.fileName, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(c.why, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (plan.skipped.isNotEmpty()) Text(
                    "Skipped ${plan.skipped.size} part${if (plan.skipped.size == 1) "" else "s"} for other phones (other processors, screens or languages).",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (plan.obb.isNotEmpty()) Text("Also copies ${plan.obb.size} data file${if (plan.obb.size == 1) "" else "s"} (OBB) to Android/obb.", style = MaterialTheme.typography.bodySmall)
                Text("Android asks to confirm next.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { Button(onClick = onInstall) { Text("Install") } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
