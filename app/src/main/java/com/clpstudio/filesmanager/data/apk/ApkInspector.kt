package com.clpstudio.filesmanager.data.apk

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

/** The app installed on this phone with the same package name. */
data class InstalledApp(
    val versionName: String?,
    val versionCode: Long,
    val certSha256: List<String>,
    val firstInstall: Long,
    val lastUpdate: Long,
    val launchable: Boolean,
)

/** Everything the APK page shows, read once in the background. */
data class ApkDetails(
    /** The file that was tapped (an .apk, or an .apks / .xapk / .apkm bundle). */
    val file: File,
    /** The APK that was read: [file] itself, or the base APK pulled out of a bundle. */
    val apk: File,
    val label: String,
    val icon: Bitmap?,
    val manifest: ApkManifest?,
    val scan: ApkZipScan,
    val signing: SigningFacts,
    val installed: InstalledApp?,
    /** Labels PackageManager knows for app-defined permissions. */
    val permissionLabels: Map<String, String> = emptyMap(),
    /** The inner APKs when [file] is a split bundle. */
    val bundleParts: List<String> = emptyList(),
    val error: String? = null,
) {
    val isBundle: Boolean get() = bundleParts.isNotEmpty()
    val installState: ApkFiles.InstallState get() = ApkFiles.installState(manifest?.versionCode, installed?.versionCode)

    /** True / false when the app is installed and both certificates are known; null otherwise. */
    val signatureMatches: Boolean?
        get() {
            val inst = installed?.certSha256?.takeIf { it.isNotEmpty() } ?: return null
            val mine = signing.certs.map { it.sha256 }.takeIf { it.isNotEmpty() } ?: return null
            return mine.any { it in inst }
        }

    val versionText: String
        get() = listOfNotNull(manifest?.versionName?.takeIf { it.isNotBlank() }, manifest?.versionCode?.let { "($it)" }).joinToString(" ").ifEmpty { "Unknown version" }
}

/**
 * Reads an APK for the preview page: PackageManager.getPackageArchiveInfo (label, icon, permissions, signatures,
 * with applicationInfo.sourceDir set so resources load), plus the app's own offline readers for the binary
 * manifest ([Axml]), the zip ([ApkFiles]) and the signing block ([ApkSigning]). No network.
 */
object ApkInspector {
    @Suppress("DEPRECATION")
    private val sigFlag: Int get() = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

    fun load(context: Context, file: File): ApkDetails {
        val outer = ApkFiles.scan(file)
        var apk = file
        var scan = outer
        var parts = emptyList<String>()
        if (outer.isBundle) {
            parts = outer.innerApks
            val base = ApkFiles.baseOf(outer.innerApks)
            if (base != null) {
                val dir = File(context.cacheDir, "apk-bundle/" + key(file)).apply { mkdirs() }
                val out = File(dir, base.substringAfterLast('/').ifEmpty { "base.apk" })
                if (!out.exists() || out.length() == 0L) ZipFile(file).use { z ->
                    z.getEntry(base)?.let { e -> z.getInputStream(e).use { inp -> out.outputStream().use { inp.copyTo(it) } } }
                }
                if (out.length() > 0) { apk = out; scan = ApkFiles.scan(out) }
            }
        }
        val manifest = scan.manifest?.let { runCatching { ApkManifest.from(Axml.parse(it)) }.getOrNull() }
        val pm = context.packageManager
        val pi: PackageInfo? = runCatching { archiveInfo(pm, apk.absolutePath, PackageManager.GET_PERMISSIONS or sigFlag) }.getOrNull()
        val ai = pi?.applicationInfo?.also { it.sourceDir = apk.absolutePath; it.publicSourceDir = apk.absolutePath }
        val pmLabel = ai?.let { runCatching { it.loadLabel(pm).toString() }.getOrNull() }
            ?.takeIf { it.isNotBlank() && it != pi?.packageName }
        val label = pmLabel ?: manifest?.label?.takeIf { it.isNotBlank() } ?: file.nameWithoutExtension
        val icon = ai?.let { a -> runCatching { toBitmap(a.loadIcon(pm), 192) }.getOrNull() }
            ?: scan.iconGuess?.let { name -> runCatching { zipBitmap(apk, name) }.getOrNull() }
        var signing = runCatching { ApkSigning.read(apk) }.getOrDefault(SigningFacts(emptyList(), emptyList()))
        if (signing.certs.isEmpty() && pi != null) {
            val certs = certBytes(pi).mapNotNull { runCatching { ApkSigning.describe(ApkSigning.fromDer(it)) }.getOrNull() }
            if (certs.isNotEmpty()) signing = signing.copy(certs = certs)
        }
        val pkg = manifest?.packageName?.takeIf { it.isNotBlank() } ?: pi?.packageName
        val installed = pkg?.let { installedApp(context, it) }
        val perms = manifest?.permissions ?: pi?.requestedPermissions?.toList().orEmpty()
        val customLabels = perms.filter { ApkPermissions.describe(it).level == ApkPermissions.Level.CUSTOM }.mapNotNull { n ->
            runCatching { pm.getPermissionInfo(n, 0).loadLabel(pm).toString() }.getOrNull()?.takeIf { it.isNotBlank() && it != n }?.let { n to it }
        }.toMap()
        val error = when {
            scan.manifest == null && parts.isEmpty() -> "This file has no AndroidManifest.xml, so it isn't a valid APK."
            manifest == null && pi == null -> "The app details in this APK couldn't be read (damaged or unusual file)."
            else -> null
        }
        val finalManifest = manifest ?: pi?.let { fromPackageInfo(it) }
        return ApkDetails(file, apk, label, icon, finalManifest, scan, signing, installed, customLabels, parts, error)
    }

    @Suppress("DEPRECATION")
    private fun fromPackageInfo(pi: PackageInfo): ApkManifest = ApkManifest(
        packageName = pi.packageName, versionCode = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong(),
        versionName = pi.versionName, minSdk = pi.applicationInfo?.minSdkVersion, minSdkCodename = null,
        targetSdk = pi.applicationInfo?.targetSdkVersion, compileSdk = null,
        permissions = pi.requestedPermissions?.toList().orEmpty(), activities = pi.activities?.size ?: 0, services = pi.services?.size ?: 0,
        receivers = pi.receivers?.size ?: 0, providers = pi.providers?.size ?: 0, label = null, split = null,
        isFeatureSplit = false, isSplitRequired = false, debuggable = (pi.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_DEBUGGABLE != 0,
    )

    @Suppress("DEPRECATION")
    private fun archiveInfo(pm: PackageManager, path: String, flags: Int): PackageInfo? =
        if (Build.VERSION.SDK_INT >= 33) pm.getPackageArchiveInfo(path, PackageManager.PackageInfoFlags.of(flags.toLong()))
        else pm.getPackageArchiveInfo(path, flags)

    @SuppressLint("QueryPermissionsNeeded")
    @Suppress("DEPRECATION")
    fun installedApp(context: Context, pkg: String): InstalledApp? {
        val pm = context.packageManager
        val pi = runCatching {
            if (Build.VERSION.SDK_INT >= 33) pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(sigFlag.toLong()))
            else pm.getPackageInfo(pkg, sigFlag)
        }.getOrNull() ?: return null
        val certs = certBytes(pi).map { ApkSigning.fingerprint(it, "SHA-256") }
        return InstalledApp(
            versionName = pi.versionName,
            versionCode = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong(),
            certSha256 = certs,
            firstInstall = pi.firstInstallTime,
            lastUpdate = pi.lastUpdateTime,
            launchable = runCatching { pm.getLaunchIntentForPackage(pkg) != null }.getOrDefault(false),
        )
    }

    @Suppress("DEPRECATION")
    private fun certBytes(pi: PackageInfo): List<ByteArray> {
        if (Build.VERSION.SDK_INT >= 28) {
            val si = pi.signingInfo
            if (si != null) {
                val sigs = if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
                if (sigs != null) return sigs.map { it.toByteArray() }
            }
        }
        return pi.signatures?.map { it.toByteArray() }.orEmpty()
    }

    /** Turns resource ids into "@string/app_name" names using the APK's own resources. */
    fun resolver(context: Context, apk: File): Axml.Resolver? = runCatching {
        val pm = context.packageManager
        val pi = archiveInfo(pm, apk.absolutePath, 0) ?: return null
        val ai = pi.applicationInfo ?: return null
        ai.sourceDir = apk.absolutePath; ai.publicSourceDir = apk.absolutePath
        val res = pm.getResourcesForApplication(ai)
        val own = pi.packageName
        Axml.Resolver { id ->
            runCatching {
                val pkg = res.getResourcePackageName(id)
                val type = res.getResourceTypeName(id)
                val entry = res.getResourceEntryName(id)
                if (pkg == own || pkg.isNullOrEmpty()) "@$type/$entry" else "@$pkg:$type/$entry"
            }.getOrNull()
        }
    }.getOrNull()

    /** AndroidManifest.xml as pretty, readable text in the cache (opened in the text viewer). */
    fun manifestXml(context: Context, details: ApkDetails): File? {
        val bytes = details.scan.manifest ?: return null
        val text = runCatching { Axml.decodeToString(bytes, resolver(context, details.apk)) }.getOrNull() ?: return null
        val dir = File(context.cacheDir, "apk-xml/" + key(details.file)).apply { mkdirs() }
        return File(dir, "AndroidManifest.xml").apply { writeText(text) }
    }

    /**
     * After a file is pulled out of an APK by the archive browser: a binary XML file (the manifest, res/ … .xml) is
     * rewritten as readable XML so the text viewer shows it; anything else is returned untouched.
     */
    fun readable(context: Context, archive: File, extracted: File): File {
        if (!extracted.name.endsWith(".xml", true) || extracted.length() > 16L * 1024 * 1024) return extracted
        val bytes = runCatching { extracted.readBytes() }.getOrNull() ?: return extracted
        if (!Axml.isAxml(bytes)) return extracted
        val text = runCatching { Axml.decodeToString(bytes, resolver(context, archive)) }.getOrNull() ?: return extracted
        runCatching { extracted.writeText(text) }
        return extracted
    }

    private fun key(file: File): String = MessageDigest.getInstance("SHA-1")
        .digest("${file.absolutePath}|${file.length()}|${file.lastModified()}".toByteArray())
        .take(8).joinToString("") { "%02x".format(it) }

    private fun zipBitmap(apk: File, name: String): Bitmap? = ZipFile(apk).use { z ->
        z.getEntry(name)?.let { e -> z.getInputStream(e).use { BitmapFactory.decodeStream(it) } }
    }

    fun toBitmap(d: Drawable, px: Int): Bitmap {
        if (d is BitmapDrawable && d.bitmap != null) return d.bitmap
        val w = if (d.intrinsicWidth > 0) d.intrinsicWidth.coerceAtMost(px) else px
        val h = if (d.intrinsicHeight > 0) d.intrinsicHeight.coerceAtMost(px) else px
        val bmp = Bitmap.createBitmap(maxOf(w, 1), maxOf(h, 1), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        d.setBounds(0, 0, c.width, c.height)
        d.draw(c)
        return bmp
    }
}
