package com.clpstudio.filesmanager.data.apk

import java.io.File
import java.util.Locale
import java.util.zip.ZipFile

/** What AndroidManifest.xml says about an app (read offline from the binary manifest). */
data class ApkManifest(
    val packageName: String,
    val versionCode: Long?,
    val versionName: String?,
    val minSdk: Int?,
    val minSdkCodename: String?,
    val targetSdk: Int?,
    val compileSdk: Int?,
    val permissions: List<String>,
    val activities: Int,
    val services: Int,
    val receivers: Int,
    val providers: Int,
    /** A literal label (null when the label is a resource reference: then PackageManager gives the name). */
    val label: String?,
    /** The split name of a split APK ("config.arm64_v8a"), null for a normal/base APK. */
    val split: String?,
    val isFeatureSplit: Boolean,
    /** The app needs split APKs installed with it (Play "bundle" apps). */
    val isSplitRequired: Boolean,
    val debuggable: Boolean,
) {
    companion object {
        fun from(doc: Axml.Document): ApkManifest {
            val m = doc.root
            val sdk = m.child("uses-sdk")
            val app = m.child("application")
            val minAttr = sdk?.attr("minSdkVersion", AndroidAttrs.MIN_SDK)
            val min = minAttr?.int()
            val codename = if (min == null) minAttr?.raw?.takeIf { it.isNotBlank() } else null
            val target = sdk?.attr("targetSdkVersion", AndroidAttrs.TARGET_SDK)?.int() ?: min
            val perms = (m.all("uses-permission") + m.all("uses-permission-sdk-23") + m.all("uses-permission-sdk-m"))
                .mapNotNull { it.attr("name", AndroidAttrs.NAME)?.raw?.trim()?.takeIf { n -> n.isNotEmpty() } }
                .distinct()
            fun count(vararg tags: String) = app?.children?.count { it.name in tags } ?: 0
            val labelAttr = app?.attr("label", AndroidAttrs.LABEL)
            return ApkManifest(
                packageName = m.attr("package")?.raw.orEmpty(),
                versionCode = m.attr("versionCode", AndroidAttrs.VERSION_CODE)?.int()?.toLong()?.let { it and 0xffffffffL },
                versionName = m.attr("versionName", AndroidAttrs.VERSION_NAME)?.let { if (it.isReference) null else it.text() },
                minSdk = min ?: if (codename == null) 1 else null,
                minSdkCodename = codename,
                targetSdk = target,
                compileSdk = m.attr("compileSdkVersion", AndroidAttrs.COMPILE_SDK)?.int(),
                permissions = perms,
                activities = count("activity", "activity-alias"),
                services = count("service"),
                receivers = count("receiver"),
                providers = count("provider"),
                label = labelAttr?.takeIf { !it.isReference }?.text(),
                split = m.attr("split")?.raw?.takeIf { it.isNotBlank() },
                isFeatureSplit = m.attr("isFeatureSplit", AndroidAttrs.IS_FEATURE_SPLIT)?.bool() == true,
                isSplitRequired = (m.attr("isSplitRequired", AndroidAttrs.IS_SPLIT_REQUIRED)?.bool() == true) ||
                    (app?.attr("isSplitRequired", AndroidAttrs.IS_SPLIT_REQUIRED)?.bool() == true) ||
                    (m.attr("requiredSplitTypes") != null),
                debuggable = app?.attr("debuggable", 0x0101000f)?.bool() == true,
            )
        }
    }
}

/** What the zip itself shows: the manifest bytes, native ABIs, file count, and inner APKs of a bundle. */
data class ApkZipScan(
    val manifest: ByteArray?,
    val abis: List<String>,
    val entries: Int,
    val dexFiles: Int,
    /** APKs inside the file: an .apks / .xapk / .apkm split bundle. */
    val innerApks: List<String>,
    /** Biggest launcher-like PNG/WebP inside (icon fallback when PackageManager can't load it). */
    val iconGuess: String?,
) {
    val isBundle: Boolean get() = manifest == null && innerApks.isNotEmpty()
}

object ApkFiles {
    /** Bundles in the wild: .apks (SAI / bundletool), .xapk (APKPure), .apkm (APKMirror). */
    fun isBundleName(name: String): Boolean = name.substringAfterLast('.', "").lowercase(Locale.ROOT) in setOf("apks", "xapk", "apkm")

    fun scan(file: File): ApkZipScan = ZipFile(file).use { zip ->
        var manifest: ByteArray? = null
        val abis = sortedSetOf<String>()
        val inner = mutableListOf<String>()
        var count = 0
        var dex = 0
        var icon: Pair<String, Long>? = null
        for (e in zip.entries()) {
            count++
            val n = e.name
            if (e.isDirectory) continue
            when {
                n == "AndroidManifest.xml" -> manifest = zip.getInputStream(e).use { it.readBytes() }
                n.startsWith("lib/") && n.count { it == '/' } >= 2 -> abis += n.split('/')[1]
                n.endsWith(".dex") && !n.contains('/') -> dex++
                n.endsWith(".apk", ignoreCase = true) -> inner += n
            }
            val lower = n.lowercase(Locale.ROOT)
            if ((lower.endsWith(".png") || lower.endsWith(".webp")) && (lower.startsWith("res/") || lower == "icon.png") &&
                (lower.contains("ic_launcher") || lower.contains("icon") || lower.contains("app_icon")) && !lower.contains("foreground") && !lower.contains("background")
            ) {
                if (icon == null || e.size > icon!!.second) icon = n to e.size
            }
        }
        ApkZipScan(manifest, abis.toList(), count, dex, inner, icon?.first)
    }

    /** The base APK of a split bundle: base.apk, else the one that isn't a config/split part, else the biggest name. */
    fun baseOf(inner: List<String>): String? =
        inner.firstOrNull { it.substringAfterLast('/').equals("base.apk", true) }
            ?: inner.firstOrNull { val n = it.substringAfterLast('/').lowercase(Locale.ROOT); !n.startsWith("config.") && !n.startsWith("split_") }
            ?: inner.firstOrNull()

    /** The installed-vs-this-file state shown on the page. */
    enum class InstallState { NOT_INSTALLED, SAME, OLDER_INSTALLED, NEWER_INSTALLED }

    fun installState(fileCode: Long?, installedCode: Long?): InstallState = when {
        installedCode == null -> InstallState.NOT_INSTALLED
        fileCode == null || fileCode == installedCode -> InstallState.SAME
        installedCode < fileCode -> InstallState.OLDER_INSTALLED
        else -> InstallState.NEWER_INSTALLED
    }
}

/** API levels as people know them: 26 -> "Android 8.0". */
object AndroidVersions {
    private val names = mapOf(
        1 to "1.0", 2 to "1.1", 3 to "1.5", 4 to "1.6", 5 to "2.0", 6 to "2.0.1", 7 to "2.1", 8 to "2.2", 9 to "2.3", 10 to "2.3.3",
        11 to "3.0", 12 to "3.1", 13 to "3.2", 14 to "4.0", 15 to "4.0.3", 16 to "4.1", 17 to "4.2", 18 to "4.3", 19 to "4.4",
        20 to "4.4W", 21 to "5.0", 22 to "5.1", 23 to "6.0", 24 to "7.0", 25 to "7.1", 26 to "8.0", 27 to "8.1", 28 to "9",
        29 to "10", 30 to "11", 31 to "12", 32 to "12L", 33 to "13", 34 to "14", 35 to "15", 36 to "16", 37 to "17",
    )

    /** "Android 8.0" (or "API 40" for a level this table doesn't know yet). */
    fun name(api: Int): String = names[api]?.let { "Android $it" } ?: "API $api"

    /** "Android 8.0+ (API 26)". */
    fun minLabel(api: Int): String = "${name(api)}+ (API $api)"

    fun label(api: Int): String = "${name(api)} (API $api)"
}

/** Human labels for permissions, grouped, with the risky ones marked. */
object ApkPermissions {
    enum class Level { DANGEROUS, SPECIAL, NORMAL, CUSTOM }

    data class Info(val name: String, val label: String, val group: String, val level: Level) {
        val shortName: String get() = name.substringAfterLast('.')
    }

    private const val CAMERA = "Camera"
    private const val LOCATION = "Location"
    private const val MIC = "Microphone"
    private const val CONTACTS = "Contacts"
    private const val PHONE = "Phone & calls"
    private const val SMS = "SMS"
    private const val STORAGE = "Photos, media & files"
    private const val CALENDAR = "Calendar"
    private const val SENSORS = "Body sensors & activity"
    private const val NEARBY = "Nearby devices"
    private const val NOTIFY = "Notifications"
    private const val NETWORK = "Network"
    private const val SYSTEM = "System & special access"
    private const val OTHER = "Other"
    private const val CUSTOM = "Defined by apps"

    private val D = Level.DANGEROUS
    private val S = Level.SPECIAL
    private val N = Level.NORMAL

    private val table: Map<String, Triple<String, String, Level>> = mapOf(
        "CAMERA" to Triple("Take pictures and videos", CAMERA, D),
        "RECORD_AUDIO" to Triple("Record audio", MIC, D),
        "ACCESS_FINE_LOCATION" to Triple("Precise location (GPS)", LOCATION, D),
        "ACCESS_COARSE_LOCATION" to Triple("Approximate location", LOCATION, D),
        "ACCESS_BACKGROUND_LOCATION" to Triple("Location in the background", LOCATION, D),
        "ACCESS_MEDIA_LOCATION" to Triple("Read locations from your photos", LOCATION, D),
        "READ_CONTACTS" to Triple("Read your contacts", CONTACTS, D),
        "WRITE_CONTACTS" to Triple("Modify your contacts", CONTACTS, D),
        "GET_ACCOUNTS" to Triple("Find accounts on the device", CONTACTS, D),
        "READ_PHONE_STATE" to Triple("Read phone status and identity", PHONE, D),
        "READ_PHONE_NUMBERS" to Triple("Read phone numbers", PHONE, D),
        "CALL_PHONE" to Triple("Directly call phone numbers", PHONE, D),
        "ANSWER_PHONE_CALLS" to Triple("Answer phone calls", PHONE, D),
        "READ_CALL_LOG" to Triple("Read call log", PHONE, D),
        "WRITE_CALL_LOG" to Triple("Modify call log", PHONE, D),
        "ADD_VOICEMAIL" to Triple("Add voicemail", PHONE, D),
        "USE_SIP" to Triple("Make and receive SIP calls", PHONE, D),
        "PROCESS_OUTGOING_CALLS" to Triple("Reroute outgoing calls", PHONE, D),
        "SEND_SMS" to Triple("Send SMS messages", SMS, D),
        "RECEIVE_SMS" to Triple("Receive SMS messages", SMS, D),
        "READ_SMS" to Triple("Read your SMS messages", SMS, D),
        "RECEIVE_MMS" to Triple("Receive MMS messages", SMS, D),
        "RECEIVE_WAP_PUSH" to Triple("Receive WAP push messages", SMS, D),
        "READ_EXTERNAL_STORAGE" to Triple("Read files on shared storage", STORAGE, D),
        "WRITE_EXTERNAL_STORAGE" to Triple("Modify or delete files on shared storage", STORAGE, D),
        "READ_MEDIA_IMAGES" to Triple("Read photos", STORAGE, D),
        "READ_MEDIA_VIDEO" to Triple("Read videos", STORAGE, D),
        "READ_MEDIA_AUDIO" to Triple("Read music and audio", STORAGE, D),
        "READ_MEDIA_VISUAL_USER_SELECTED" to Triple("Read photos and videos you select", STORAGE, D),
        "READ_CALENDAR" to Triple("Read calendar events", CALENDAR, D),
        "WRITE_CALENDAR" to Triple("Add or modify calendar events", CALENDAR, D),
        "BODY_SENSORS" to Triple("Body sensors (heart rate…)", SENSORS, D),
        "BODY_SENSORS_BACKGROUND" to Triple("Body sensors in the background", SENSORS, D),
        "ACTIVITY_RECOGNITION" to Triple("Recognise physical activity", SENSORS, D),
        "BLUETOOTH_CONNECT" to Triple("Connect to paired Bluetooth devices", NEARBY, D),
        "BLUETOOTH_SCAN" to Triple("Find nearby Bluetooth devices", NEARBY, D),
        "BLUETOOTH_ADVERTISE" to Triple("Advertise to nearby Bluetooth devices", NEARBY, D),
        "NEARBY_WIFI_DEVICES" to Triple("Find nearby Wi-Fi devices", NEARBY, D),
        "UWB_RANGING" to Triple("Measure distance to nearby devices", NEARBY, D),
        "POST_NOTIFICATIONS" to Triple("Show notifications", NOTIFY, D),
        "MANAGE_EXTERNAL_STORAGE" to Triple("Manage all files", STORAGE, S),
        "SYSTEM_ALERT_WINDOW" to Triple("Display over other apps", SYSTEM, S),
        "REQUEST_INSTALL_PACKAGES" to Triple("Install other apps", SYSTEM, S),
        "REQUEST_DELETE_PACKAGES" to Triple("Ask to uninstall apps", SYSTEM, N),
        "PACKAGE_USAGE_STATS" to Triple("Read app usage", SYSTEM, S),
        "WRITE_SETTINGS" to Triple("Modify system settings", SYSTEM, S),
        "BIND_ACCESSIBILITY_SERVICE" to Triple("Accessibility service", SYSTEM, S),
        "BIND_NOTIFICATION_LISTENER_SERVICE" to Triple("Read all notifications", SYSTEM, S),
        "BIND_DEVICE_ADMIN" to Triple("Device admin", SYSTEM, S),
        "BIND_VPN_SERVICE" to Triple("VPN service", SYSTEM, S),
        "QUERY_ALL_PACKAGES" to Triple("See all installed apps", SYSTEM, S),
        "SCHEDULE_EXACT_ALARM" to Triple("Set exact alarms", SYSTEM, S),
        "USE_FULL_SCREEN_INTENT" to Triple("Full-screen notifications", NOTIFY, S),
        "READ_LOGS" to Triple("Read system logs", SYSTEM, S),
        "INTERNET" to Triple("Full network access", NETWORK, N),
        "ACCESS_NETWORK_STATE" to Triple("View network connections", NETWORK, N),
        "ACCESS_WIFI_STATE" to Triple("View Wi-Fi connections", NETWORK, N),
        "CHANGE_WIFI_STATE" to Triple("Connect and disconnect from Wi-Fi", NETWORK, N),
        "CHANGE_NETWORK_STATE" to Triple("Change network connectivity", NETWORK, N),
        "NFC" to Triple("Use NFC", NEARBY, N),
        "BLUETOOTH" to Triple("Pair with Bluetooth devices", NEARBY, N),
        "BLUETOOTH_ADMIN" to Triple("Bluetooth settings", NEARBY, N),
        "VIBRATE" to Triple("Control vibration", OTHER, N),
        "WAKE_LOCK" to Triple("Prevent the phone from sleeping", OTHER, N),
        "RECEIVE_BOOT_COMPLETED" to Triple("Run at startup", OTHER, N),
        "FOREGROUND_SERVICE" to Triple("Run a foreground service", OTHER, N),
        "USE_BIOMETRIC" to Triple("Use fingerprint / face unlock", OTHER, N),
        "USE_FINGERPRINT" to Triple("Use fingerprint", OTHER, N),
        "SET_WALLPAPER" to Triple("Set wallpaper", OTHER, N),
        "MODIFY_AUDIO_SETTINGS" to Triple("Change audio settings", OTHER, N),
        "REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" to Triple("Ask to ignore battery optimisation", OTHER, N),
        "READ_SYNC_SETTINGS" to Triple("Read sync settings", OTHER, N),
        "WRITE_SYNC_SETTINGS" to Triple("Turn sync on and off", OTHER, N),
        "EXPAND_STATUS_BAR" to Triple("Expand the status bar", OTHER, N),
        "KILL_BACKGROUND_PROCESSES" to Triple("Close other apps", OTHER, N),
        "ACCESS_NOTIFICATION_POLICY" to Triple("Do Not Disturb access", NOTIFY, N),
        "USE_EXACT_ALARM" to Triple("Set exact alarms", OTHER, N),
        "CHANGE_WIFI_MULTICAST_STATE" to Triple("Receive Wi-Fi multicast", NETWORK, N),
        "TRANSMIT_IR" to Triple("Use the infrared blaster", OTHER, N),
        "FLASHLIGHT" to Triple("Control the flashlight", OTHER, N),
    )

    private val groupOrder = listOf(CAMERA, MIC, LOCATION, CONTACTS, PHONE, SMS, STORAGE, CALENDAR, SENSORS, NEARBY, NOTIFY, SYSTEM, NETWORK, OTHER, CUSTOM)

    fun describe(name: String): Info {
        val isFramework = name.startsWith("android.permission.")
        val short = name.substringAfterLast('.')
        table[short]?.takeIf { isFramework }?.let { (label, group, level) -> return Info(name, label, group, level) }
        if (isFramework && short.startsWith("FOREGROUND_SERVICE_")) {
            return Info(name, "Foreground service: " + humanize(short.removePrefix("FOREGROUND_SERVICE_")).lowercase(Locale.ROOT), OTHER, N)
        }
        return if (isFramework || name.startsWith("com.android.") || name.startsWith("android."))
            Info(name, humanize(short), OTHER, N)
        else Info(name, humanize(short), CUSTOM, Level.CUSTOM)
    }

    /** READ_PHONE_STATE -> "Read phone state". */
    fun humanize(s: String): String =
        s.replace('_', ' ').replace('-', ' ').trim().lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) }

    /** Groups in a fixed order (risky first), each list with dangerous/special ones first, then by label. */
    fun grouped(names: List<String>, override: (String) -> String? = { null }): List<Pair<String, List<Info>>> =
        names.distinct().map { n -> describe(n).let { i -> override(n)?.takeIf { i.level == Level.CUSTOM }?.let { i.copy(label = it) } ?: i } }
            .groupBy { it.group }
            .toList()
            .sortedBy { groupOrder.indexOf(it.first).let { i -> if (i < 0) 99 else i } }
            .map { (g, l) -> g to l.sortedWith(compareBy({ it.level.ordinal }, { it.label })) }

    fun dangerousCount(names: List<String>): Int = names.distinct().count { describe(it).level == Level.DANGEROUS }
}
