package com.clpstudio.filesmanager

import com.clpstudio.filesmanager.data.apk.AndroidVersions
import com.clpstudio.filesmanager.data.apk.ApkFiles
import com.clpstudio.filesmanager.data.apk.ApkManifest
import com.clpstudio.filesmanager.data.apk.ApkPermissions
import com.clpstudio.filesmanager.data.apk.ApkSigning
import com.clpstudio.filesmanager.data.apk.Axml
import com.clpstudio.filesmanager.viewer.ApkCompat
import com.clpstudio.filesmanager.viewer.DeviceProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Item 46: the APK viewer's offline readers, against a real (tiny) APK built with aapt2 + apksigner:
 * package com.example.tinyapp 2.3.1 (231), minSdk 24, targetSdk 34, 4 permissions, 2 activities, 1 service,
 * 1 receiver, 1 provider, native libs for arm64-v8a + armeabi-v7a; tiny.apk is v2-signed, tiny_v1.apk JAR-signed,
 * both with the same test certificate.
 */
class ApkViewerTest {
    companion object {
        const val SHA256 = "7B:11:3C:A6:1F:60:9E:F7:79:04:00:9B:DD:4D:14:BD:87:36:1B:AF:88:1B:7E:CE:D8:85:BC:FE:44:A6:2E:93"
        const val SHA1 = "05:FF:36:48:2B:7A:FB:CA:61:5C:F4:36:66:22:DE:9C:A3:DD:87:FB"

        fun resource(name: String): File {
            val out = File.createTempFile("apk-test-", "-" + name.substringAfterLast('/'))
            out.deleteOnExit()
            ApkViewerTest::class.java.getResourceAsStream("/apk/$name")!!.use { inp -> out.outputStream().use { inp.copyTo(it) } }
            return out
        }
    }

    private fun manifestBytes(f: File) = ZipFile(f).use { z -> z.getInputStream(z.getEntry("AndroidManifest.xml")).readBytes() }

    @Test fun readsTheBinaryManifest() {
        val bytes = manifestBytes(resource("tiny.apk"))
        assertTrue(Axml.isAxml(bytes))
        val m = ApkManifest.from(Axml.parse(bytes))
        assertEquals("com.example.tinyapp", m.packageName)
        assertEquals(231L, m.versionCode)
        assertEquals("2.3.1", m.versionName)
        assertEquals(24, m.minSdk)
        assertEquals(34, m.targetSdk)
        assertEquals(
            listOf("android.permission.CAMERA", "android.permission.INTERNET", "android.permission.READ_CONTACTS", "android.permission.VIBRATE"),
            m.permissions,
        )
        assertEquals(2, m.activities)
        assertEquals(1, m.services)
        assertEquals(1, m.receivers)
        assertEquals(1, m.providers)
        assertNull("label is a @string reference", m.label)
        assertNull(m.split)
        assertFalse(m.isSplitRequired)
    }

    @Test fun decodesManifestToPrettyXml() {
        val xml = Axml.decodeToString(manifestBytes(resource("tiny.apk")))
        assertTrue(xml, xml.startsWith("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<manifest"))
        assertTrue(xml, xml.contains("xmlns:android=\"http://schemas.android.com/apk/res/android\""))
        assertTrue(xml, xml.contains("android:versionCode=\"231\""))
        assertTrue(xml, xml.contains("android:versionName=\"2.3.1\""))
        assertTrue(xml, xml.contains("package=\"com.example.tinyapp\""))
        assertTrue(xml, xml.contains("    <uses-permission android:name=\"android.permission.CAMERA\" />"))
        assertTrue(xml, xml.contains("<action android:name=\"android.intent.action.MAIN\" />"))
        assertTrue("references shown as ids without a resolver", Regex("android:label=\"@0x7F[0-9A-F]{6}\"").containsMatchIn(xml))
        assertTrue(xml, xml.contains("android:exported=\"true\""))
        assertTrue(xml.trimEnd().endsWith("</manifest>"))
        // Balanced tags.
        assertEquals(Regex("<application\\b").findAll(xml).count(), Regex("</application>").findAll(xml).count())
    }

    @Test fun decodesResXmlAndUsesResolver() {
        val f = resource("tiny.apk")
        val bytes = ZipFile(f).use { z -> z.getInputStream(z.getEntry("res/xml/prefs.xml")).readBytes() }
        val xml = Axml.decodeToString(bytes)
        assertTrue(xml, xml.contains("<PreferenceScreen"))
        assertTrue(xml, xml.contains("android:title=\"Hello &amp; welcome\""))
        assertTrue(xml, xml.contains("android:key=\"hello\""))
        val named = Axml.decodeToString(manifestBytes(f)) { id -> if (id ushr 24 == 0x7f) "@string/app_name" else null }
        assertTrue(named, named.contains("android:label=\"@string/app_name\""))
    }

    @Test fun notAxmlIsRejected() {
        assertFalse(Axml.isAxml("<?xml version=\"1.0\"?><a/>".toByteArray()))
        assertFalse(Axml.isAxml(ByteArray(3)))
    }

    @Test fun readsV2Signature() {
        val s = ApkSigning.read(resource("tiny.apk"))
        assertEquals(listOf("v2", "v3"), s.schemes)
        val c = s.certs.single()
        assertEquals(SHA256, c.sha256)
        assertEquals(SHA1, c.sha1)
        assertTrue(c.subject, c.subject.contains("CN=Tiny Test"))
        assertTrue(c.subject.contains("O=CLP Studio Tests"))
        assertTrue(c.key, c.key.startsWith("RSA 2048"))
        assertTrue(c.notAfter > c.notBefore)
        assertFalse(c.isDebug)
    }

    @Test fun readsV1JarSignature() {
        val s = ApkSigning.read(resource("tiny_v1.apk"))
        assertEquals(listOf("v1"), s.schemes)
        assertEquals(SHA256, s.certs.single().sha256)
    }

    @Test fun zipScanFindsAbisAndIcon() {
        val scan = ApkFiles.scan(resource("tiny.apk"))
        assertEquals(listOf("arm64-v8a", "armeabi-v7a"), scan.abis)
        assertEquals("res/drawable/icon.png", scan.iconGuess)
        assertFalse(scan.isBundle)
        assertTrue(scan.manifest != null)
        assertEquals(0, scan.dexFiles)
    }

    @Test fun detectsSplitBundles() {
        val tiny = resource("tiny.apk")
        val bundle = File.createTempFile("bundle", ".apks").apply { deleteOnExit() }
        ZipOutputStream(bundle.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("config.arm64_v8a.apk")); z.write(tiny.readBytes()); z.closeEntry()
            z.putNextEntry(ZipEntry("base.apk")); z.write(tiny.readBytes()); z.closeEntry()
            z.putNextEntry(ZipEntry("toc.pb")); z.write(byteArrayOf(1, 2)); z.closeEntry()
        }
        val scan = ApkFiles.scan(bundle)
        assertTrue(scan.isBundle)
        assertEquals("base.apk", ApkFiles.baseOf(scan.innerApks))
        assertEquals("app.apk", ApkFiles.baseOf(listOf("split_config.en.apk", "app.apk")))
        assertTrue(ApkFiles.isBundleName("x.xapk") && ApkFiles.isBundleName("y.APKS") && !ApkFiles.isBundleName("z.apk"))
    }

    @Test fun androidVersionNames() {
        assertEquals("Android 8.0", AndroidVersions.name(26))
        assertEquals("Android 7.0+ (API 24)", AndroidVersions.minLabel(24))
        assertEquals("Android 14 (API 34)", AndroidVersions.label(34))
        assertEquals("Android 12L", AndroidVersions.name(32))
        assertEquals("API 99", AndroidVersions.name(99))
    }

    @Test fun permissionLabelsAndGroups() {
        val cam = ApkPermissions.describe("android.permission.CAMERA")
        assertEquals(ApkPermissions.Level.DANGEROUS, cam.level)
        assertEquals("Take pictures and videos", cam.label)
        assertEquals(ApkPermissions.Level.NORMAL, ApkPermissions.describe("android.permission.INTERNET").level)
        assertEquals(ApkPermissions.Level.SPECIAL, ApkPermissions.describe("android.permission.SYSTEM_ALERT_WINDOW").level)
        val custom = ApkPermissions.describe("com.example.tinyapp.permission.C2D_MESSAGE")
        assertEquals(ApkPermissions.Level.CUSTOM, custom.level)
        assertEquals("C2d message", custom.label)
        assertEquals("Foreground service: media playback", ApkPermissions.describe("android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK").label)
        val groups = ApkPermissions.grouped(
            listOf("android.permission.INTERNET", "android.permission.VIBRATE", "android.permission.CAMERA", "android.permission.READ_CONTACTS", "x.y.CUSTOM"),
        ) { if (it == "x.y.CUSTOM") "Custom thing" else null }
        assertEquals(listOf("Camera", "Contacts", "Network", "Other", "Defined by apps"), groups.map { it.first })
        assertEquals("Custom thing", groups.last().second.single().label)
        assertEquals(2, ApkPermissions.dangerousCount(listOf("android.permission.CAMERA", "android.permission.READ_CONTACTS", "android.permission.INTERNET", "android.permission.CAMERA")))
    }

    @Test fun installStateAndCompatibility() {
        assertEquals(ApkFiles.InstallState.NOT_INSTALLED, ApkFiles.installState(231, null))
        assertEquals(ApkFiles.InstallState.SAME, ApkFiles.installState(231, 231))
        assertEquals(ApkFiles.InstallState.OLDER_INSTALLED, ApkFiles.installState(231, 200))
        assertEquals(ApkFiles.InstallState.NEWER_INSTALLED, ApkFiles.installState(231, 300))
        val phone = DeviceProfile(29, listOf("arm64-v8a", "armeabi-v7a", "armeabi"))
        assertTrue(ApkCompat.sdkOk(24, phone))
        assertFalse(ApkCompat.sdkOk(30, phone))
        assertTrue(ApkCompat.abiOk(emptyList(), phone))
        assertTrue(ApkCompat.abiOk(listOf("arm64-v8a"), phone))
        assertFalse(ApkCompat.abiOk(listOf("x86_64"), phone))
    }

    @Test fun fingerprintLines() {
        assertEquals("05:FF:36:48:2B:7A:FB:CA:61:5C\nF4:36:66:22:DE:9C:A3:DD:87:FB", com.clpstudio.filesmanager.viewer.splitFingerprint(SHA1))
    }

    @Test fun readableSignerName() {
        assertEquals("Tiny Test · CLP Studio Tests · IN", com.clpstudio.filesmanager.viewer.readableDn("CN=Tiny Test,O=CLP Studio Tests,C=IN"))
        assertEquals("A, B · Org", com.clpstudio.filesmanager.viewer.readableDn("CN=A\\, B,O=Org"))
    }
}
