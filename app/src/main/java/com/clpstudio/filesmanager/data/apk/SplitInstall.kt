package com.clpstudio.filesmanager.data.apk

import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.zip.ZipFile

/**
 * Item 6 (F11): installing split bundles (.apks from SAI / bundletool, .xapk from APKPure, .apkm from APKMirror) from
 * the APK viewer. Picks the parts this phone needs — the base, every feature split, and of the configuration splits
 * only the matching CPU (ABI), screen density and languages — and streams them into ONE PackageInstaller session
 * (the system's own "Install?" prompt). XAPK expansion files (OBB) are copied to Android/obb/<package>/.
 */
data class SplitPart(val entry: String, val kind: Kind, val group: String, val token: String) {
    enum class Kind { BASE, FEATURE, ABI, DENSITY, LANGUAGE, OTHER }
    val fileName: String get() = entry.substringAfterLast('/')
}

data class SplitChoice(val part: SplitPart, val why: String)

data class SplitPlan(
    val chosen: List<SplitChoice>,
    val skipped: List<SplitPart>,
    /** XAPK expansion files: entry in the bundle → path under shared storage. */
    val obb: List<Pair<String, String>> = emptyList(),
    val packageName: String? = null,
    /** Set when the bundle can't be installed (encrypted .apkm, no base, no part for this CPU). */
    val problem: String? = null,
) {
    val canInstall: Boolean get() = problem == null && chosen.any { it.part.kind == SplitPart.Kind.BASE }
    val chosenEntries: List<String> get() = chosen.map { it.part.entry }
}

object SplitSelect {
    private val ABIS = mapOf(
        "arm64_v8a" to "arm64-v8a", "armeabi_v7a" to "armeabi-v7a", "armeabi" to "armeabi",
        "x86_64" to "x86_64", "x86" to "x86", "mips64" to "mips64", "mips" to "mips",
    )
    val DENSITIES = linkedMapOf("ldpi" to 120, "mdpi" to 160, "tvdpi" to 213, "hdpi" to 240, "xhdpi" to 320, "xxhdpi" to 480, "xxxhdpi" to 640)

    /** What one inner APK is, from its name (bundletool / SAI / APKPure / APKMirror naming). */
    fun classify(entry: String, base: String?): SplitPart {
        val file = entry.substringAfterLast('/')
        val stem = file.removeSuffix(".apk").removeSuffix(".APK")
        if (entry == base) return SplitPart(entry, SplitPart.Kind.BASE, "", "")
        // "config.arm64_v8a", "split_config.xxhdpi", "base-xxhdpi", "feature.config.en", "split_feature.config.hi"
        val s = stem.removePrefix("split_")
        val (group, token) = when {
            s.startsWith("config.") -> "" to s.removePrefix("config.")
            s.contains(".config.") -> s.substringBefore(".config.") to s.substringAfter(".config.")
            s.startsWith("base-") -> "" to s.removePrefix("base-")
            else -> return SplitPart(entry, SplitPart.Kind.FEATURE, s, "")
        }
        val t = token.lowercase(Locale.ROOT).replace('-', '_')
        val kind = when {
            t in ABIS -> SplitPart.Kind.ABI
            t in DENSITIES -> SplitPart.Kind.DENSITY
            t.length in 2..3 && t.all { it.isLetter() } -> SplitPart.Kind.LANGUAGE
            t.matches(Regex("[a-z]{2,3}_r?[a-z]{2}")) -> SplitPart.Kind.LANGUAGE
            else -> SplitPart.Kind.OTHER
        }
        return SplitPart(entry, kind, group, t)
    }

    fun baseOf(entries: List<String>): String? = ApkFiles.baseOf(entries)

    /**
     * The parts to install on a phone with [deviceAbis] (best first, like Build.SUPPORTED_ABIS), [densityDpi] and
     * [languages] (ISO codes, the phone's language list).
     */
    fun select(entries: List<String>, deviceAbis: List<String>, densityDpi: Int, languages: List<String>, base: String? = baseOf(entries)): SplitPlan {
        val parts = entries.map { classify(it, base) }
        val chosen = ArrayList<SplitChoice>()
        val skipped = ArrayList<SplitPart>()
        parts.filter { it.kind == SplitPart.Kind.BASE }.forEach { chosen += SplitChoice(it, "the app itself") }
        parts.filter { it.kind == SplitPart.Kind.FEATURE }.forEach { chosen += SplitChoice(it, "part of the app") }
        val langs = languages.map { it.lowercase(Locale.ROOT).substringBefore('_').substringBefore('-') }.toSet()
        for ((group, inGroup) in parts.filter { it.kind != SplitPart.Kind.BASE && it.kind != SplitPart.Kind.FEATURE }.groupBy { it.group }) {
            // CPU: the phone's best ABI that has a split.
            val abiParts = inGroup.filter { it.kind == SplitPart.Kind.ABI }
            val abi = deviceAbis.firstNotNullOfOrNull { want -> abiParts.firstOrNull { ABIS[it.token] == want } }
            abiParts.forEach { if (it == abi) chosen += SplitChoice(it, "this phone's processor (${ABIS[it.token]})") else skipped += it }
            // Screen: the closest density at or above the phone's, else the biggest there is.
            val dParts = inGroup.filter { it.kind == SplitPart.Kind.DENSITY }
            val d = dParts.filter { DENSITIES.getValue(it.token) >= densityDpi }.minByOrNull { DENSITIES.getValue(it.token) }
                ?: dParts.maxByOrNull { DENSITIES.getValue(it.token) }
            dParts.forEach { if (it == d) chosen += SplitChoice(it, "this screen (${it.token})") else skipped += it }
            // Languages: the phone's languages, plus English so text never goes missing.
            val lParts = inGroup.filter { it.kind == SplitPart.Kind.LANGUAGE }
            val wanted = lParts.filter { it.token.substringBefore('_') in langs }.ifEmpty { lParts.filter { it.token == "en" } }
            lParts.forEach { if (it in wanted || it.token == "en") chosen += SplitChoice(it, "language (${it.token})") else skipped += it }
            // Anything else (unknown config): installing it is harmless.
            inGroup.filter { it.kind == SplitPart.Kind.OTHER }.forEach { chosen += SplitChoice(it, "extra part") }
            if (abiParts.isNotEmpty() && abi == null) {
                return SplitPlan(chosen, skipped, problem = "This app is only built for ${abiParts.joinToString { ABIS[it.token] ?: it.token }}, not this phone's processor.")
            }
        }
        val problem = if (chosen.none { it.part.kind == SplitPart.Kind.BASE }) "The bundle has no base APK." else null
        return SplitPlan(chosen, skipped, problem = problem)
    }

    /** XAPK manifest.json: the package, its split list (when given) and OBB files. */
    data class Xapk(val packageName: String?, val splits: List<String>, val obb: List<Pair<String, String>>)

    fun parseXapk(json: String): Xapk? = runCatching {
        val o = JSONObject(json)
        val pkg = o.optString("package_name").ifBlank { null }
        val splits = o.optJSONArray("split_apks")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("file")?.ifBlank { null } } }.orEmpty()
        val obb = o.optJSONArray("expansions")?.let { a ->
            (0 until a.length()).mapNotNull { i ->
                val e = a.optJSONObject(i) ?: return@mapNotNull null
                val f = e.optString("file").ifBlank { null } ?: return@mapNotNull null
                val path = e.optString("install_path").ifBlank { f }
                if (path.contains("..")) null else f to path
            }
        }.orEmpty()
        Xapk(pkg, splits, obb)
    }.getOrNull()

    /** Reads [bundle] and plans the install for this phone. */
    fun plan(bundle: File, deviceAbis: List<String>, densityDpi: Int, languages: List<String>): SplitPlan {
        val zip = runCatching { ZipFile(bundle) }.getOrNull()
            ?: return SplitPlan(emptyList(), emptyList(), problem = encryptedMessage(bundle))
        zip.use { z ->
            val apks = z.entries().asSequence().filter { !it.isDirectory && it.name.endsWith(".apk", true) && !it.name.contains("..") }.map { it.name }.toList()
            if (apks.isEmpty()) return SplitPlan(emptyList(), emptyList(), problem = encryptedMessage(bundle))
            val xapk = z.getEntry("manifest.json")?.let { e -> z.getInputStream(e).use { parseXapk(it.readBytes().toString(Charsets.UTF_8)) } }
            val plan = select(apks, deviceAbis, densityDpi, languages)
            val obb = xapk?.obb.orEmpty().filter { z.getEntry(it.first) != null }
            return plan.copy(obb = obb, packageName = xapk?.packageName)
        }
    }

    private fun encryptedMessage(f: File): String =
        if (f.name.endsWith(".apkm", true)) "This .apkm is locked by APKMirror. Open it with APKMirror Installer." else "This bundle couldn't be read (damaged or locked)."
}
