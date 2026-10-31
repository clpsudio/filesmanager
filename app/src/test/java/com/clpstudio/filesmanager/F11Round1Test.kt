package com.clpstudio.filesmanager

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import com.clpstudio.filesmanager.data.AppSettings
import com.clpstudio.filesmanager.data.FileItem
import com.clpstudio.filesmanager.data.FileKind
import com.clpstudio.filesmanager.data.ThemeMode
import com.clpstudio.filesmanager.data.apk.ApkInspector
import com.clpstudio.filesmanager.data.apk.SplitPart
import com.clpstudio.filesmanager.data.apk.SplitSelect
import com.clpstudio.filesmanager.security.CredentialRules
import com.clpstudio.filesmanager.security.Biometrics as B
import com.clpstudio.filesmanager.security.CredentialType
import com.clpstudio.filesmanager.security.word
import com.clpstudio.filesmanager.ui.security.CredentialSetupForm
import com.clpstudio.filesmanager.ui.security.PatternMath
import com.clpstudio.filesmanager.ui.security.UnlockForm
import com.clpstudio.filesmanager.ui.theme.FilesTheme
import com.clpstudio.filesmanager.vault.SoftwareKeyWrapper
import com.clpstudio.filesmanager.vault.VaultStore
import com.clpstudio.filesmanager.vault.WrongSecretException
import com.clpstudio.filesmanager.viewer.ApkViewerContent
import com.clpstudio.filesmanager.viewer.DeviceProfile
import com.clpstudio.filesmanager.viewer.PagerVideo
import com.clpstudio.filesmanager.viewer.VideoSpeed
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** F11 build 1: 2x hold fix (17), pattern lock (4), vault fingerprint plumbing (5), split bundle install (6), picker block (16). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w393dp-h852dp-xxhdpi")
class F11Round1Test {
    @get:Rule val compose = createComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val shots = File("build/screenshots").apply { mkdirs() }.path
    private val item get() = FileItem(File(app.cacheDir, "clip.mp4").absolutePath, "clip.mp4", false, 1000L, 0L, FileKind.VIDEO)

    @Before fun setUp() { VideoSpeed.set(1f) }

    // ---------- 17: 2x ----------

    @Test fun holdFor2xWorksAgainAfterAnInterruptedHold() {
        val v = PagerVideo(app)
        v.load(item, AppSettings())
        v.startFast()
        assertEquals(2f, v.player.playbackParameters.speed)
        // The hold was cut off (page changed / screen recomposed): endFast never ran, something reset the speed.
        v.player.setPlaybackSpeed(1f)
        assertTrue(v.fast)
        // Before F11 this returned at once ("if (fast) return") and the long press did nothing.
        v.startFast()
        assertEquals(2f, v.player.playbackParameters.speed)
        v.endFast()
        assertEquals(1f, v.player.playbackParameters.speed)
        assertFalse(v.fast)
        v.release()
    }

    @Test fun aHoldNeverCarriesOverToTheNextVideo() {
        val v = PagerVideo(app)
        v.load(item, AppSettings())
        v.startFast()
        v.load(item.copy(path = File(app.cacheDir, "next.mp4").absolutePath, name = "next.mp4"), AppSettings())
        assertFalse(v.fast)
        assertEquals(1f, v.player.playbackParameters.speed)
        v.release()
    }

    @Test fun holdEndsEvenWhenTheGestureIsCancelled() {
        val src = File("src/main/java/com/clpstudio/filesmanager/viewer/PagerVideo.kt").readText()
        assertTrue(src.contains("try { tryAwaitRelease() } finally { video.endFast() }"))
        assertFalse(src.contains("if (fast || path == null) return"))
    }

    // ---------- 16: picker block screenshots, live ----------

    @Test fun pickerAppliesBlockScreenshotsLive() {
        val src = File("src/main/java/com/clpstudio/filesmanager/picker/PickerActivity.kt").readText()
        assertTrue(src.contains("PrivacyGuard.installBlockOnly(this)"))
        assertFalse(src.contains("if (runCatching { Graph.settings.value.blockScreenshots }"))
        val guard = File("src/main/java/com/clpstudio/filesmanager/ui/PrivacyGuard.kt").readText()
        assertTrue(guard.substringAfter("fun installBlockOnly").contains("settingsRepo.settings.map { it.blockScreenshots }.distinctUntilChanged().collect"))
    }

    // ---------- 4: pattern lock ----------

    @Test fun patternRules() {
        assertEquals("Connect at least 4 dots", CredentialRules.error(CredentialType.PATTERN, "012"))
        assertNull(CredentialRules.error(CredentialType.PATTERN, "0125"))
        assertNotNull(CredentialRules.error(CredentialType.PATTERN, "0120")) // a dot twice
        assertNotNull(CredentialRules.error(CredentialType.PATTERN, "019")) // no dot 9
        assertEquals("The patterns don't match", CredentialRules.confirmError(CredentialType.PATTERN, "01258", "01254"))
        assertNull(CredentialRules.confirmError(CredentialType.PATTERN, "01258", "01258"))
        assertEquals("pattern", CredentialType.PATTERN.word())
    }

    @Test fun patternPadMath() {
        assertEquals(1, PatternMath.between(0, 2))
        assertEquals(4, PatternMath.between(0, 8))
        assertEquals(4, PatternMath.between(2, 6))
        assertEquals(-1, PatternMath.between(0, 5)) // knight move: nothing in between
        assertEquals(-1, PatternMath.between(0, 1))
        // 0 → 2 picks up 1 on the way; a used middle dot is not added twice.
        assertEquals(listOf(0, 1, 2), PatternMath.extend(listOf(0), 2))
        assertEquals(listOf(1, 0, 2), PatternMath.extend(listOf(1, 0), 2))
        assertEquals(listOf(0), PatternMath.extend(listOf(0), 0))
        val size = 300f
        assertEquals(4, PatternMath.hit(Offset(150f, 150f), size))
        assertEquals(0, PatternMath.hit(Offset(50f, 50f), size))
        assertEquals(-1, PatternMath.hit(Offset(100f, 100f), size)) // between dots
    }

    @Test fun vaultWithPatternOpensOnlyWithThatPattern() {
        val dir = File(app.cacheDir, "vault_pattern").apply { deleteRecursively() }
        val s = VaultStore(dir, SoftwareKeyWrapper(), iterations = 2000, chunk = 4096)
        s.setup(CredentialType.PATTERN, "03678")
        assertEquals(CredentialType.PATTERN, s.credentialType)
        s.lock()
        assertTrue(runCatching { s.unlock("0367") }.exceptionOrNull() is WrongSecretException)
        s.unlock("03678")
        assertTrue(s.isUnlocked)
    }

    @Test fun drawingThePatternUnlocks() {
        var unlocked = false
        var tried: String? = null
        compose.setContent {
            FilesTheme(ThemeMode.LIGHT, liquidGlass = false) {
                Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                    UnlockForm(
                        type = CredentialType.PATTERN, title = "Vault is locked", subtitle = "Draw your pattern",
                        check = { p -> tried = p; if (p == "0124") null else "Wrong pattern" }, onUnlocked = { unlocked = true }, remainingMs = { 0L },
                    )
                }
            }
        }
        // Draw 0 → 1 → 2 → 4 on the pad (top row, then the centre).
        compose.onNodeWithTag("patternPad").performTouchInput {
            val s = width.toFloat()
            fun c(i: Int) = Offset(s / 3f * (i % 3) + s / 6f, s / 3f * (i / 3) + s / 6f)
            down(c(0)); moveTo(c(1)); moveTo(c(2)); moveTo(c(4)); up()
        }
        compose.waitForIdle()
        assertEquals("0124", tried)
        assertTrue(unlocked)
    }

    @Test fun patternUnlock_render() {
        compose.setContent {
            FilesTheme(ThemeMode.LIGHT, liquidGlass = false) {
                Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                    UnlockForm(
                        type = CredentialType.PATTERN, title = "Files is locked", subtitle = "Draw your pattern",
                        check = { "Wrong pattern · 4 tries left" }, onUnlocked = {}, remainingMs = { 0L },
                        extra = { androidx.compose.material3.OutlinedButton(onClick = {}) { androidx.compose.material3.Text("Use fingerprint") } },
                    )
                }
            }
        }
        compose.onNodeWithTag("patternPad").performTouchInput {
            val s = width.toFloat()
            fun c(i: Int) = Offset(s / 3f * (i % 3) + s / 6f, s / 3f * (i / 3) + s / 6f)
            down(c(6)); moveTo(c(3)); moveTo(c(0)); moveTo(c(4)); moveTo(c(8)); up()
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("$shots/files_f11_pattern_unlock.png")
    }

    @Test fun patternSetup_render() {
        compose.setContent {
            FilesTheme(ThemeMode.LIGHT, liquidGlass = true) {
                Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
                    CredentialSetupForm(confirmLabel = "Turn on", busy = false, onSubmit = { _, _ -> }, initialType = CredentialType.PATTERN)
                }
            }
        }
        compose.onNodeWithText("Pattern").assertExists()
        compose.onNodeWithText("Draw a new pattern").assertExists()
        compose.onRoot().captureRoboImage("$shots/files_f11_pattern_setup.png")
    }

    // ---------- 5: vault fingerprint plumbing ----------

    @Test fun masterKeyCopyReopensTheVaultAndSurvivesASecretChange() {
        val dir = File(app.cacheDir, "vault_bio").apply { deleteRecursively() }
        val s = VaultStore(dir, SoftwareKeyWrapper(), iterations = 2000, chunk = 4096)
        s.setup(CredentialType.PIN, "2468")
        s.importFile(File(app.cacheDir, "pic.jpg").apply { writeBytes(ByteArray(5000) { 3 }) })
        val mk = s.masterKeyCopy()
        assertTrue(s.keyFile.checkMasterKey(mk))
        assertFalse(s.keyFile.checkMasterKey(ByteArray(mk.size)))
        // The wrapped copy (sealed by the fingerprint key on a phone) is kept when the PIN changes.
        s.keyFile.saveBiometric(byteArrayOf(1, 2, 3), byteArrayOf(9, 9))
        s.changeSecret("2468", CredentialType.PATTERN, "01478")
        assertNotNull(s.keyFile.biometric())
        assertEquals(listOf<Byte>(9, 9), s.keyFile.biometric()!!.second.toList())
        s.lock()
        assertFalse(s.isUnlocked)
        s.unlockWithKey(mk)
        assertTrue(s.isUnlocked)
        assertEquals(1, s.list().size)
        s.keyFile.clearBiometric()
        assertNull(s.keyFile.biometric())
    }

    @Test fun fingerprintNeverReplacesTheVaultEncryption() {
        val src = File("src/main/java/com/clpstudio/filesmanager/vault/VaultBiometric.kt").readText()
        assertTrue(src.contains("setUserAuthenticationRequired(true)"))
        assertTrue(src.contains("setInvalidatedByBiometricEnrollment(true)"))
        assertTrue(src.contains("checkMasterKey(mk)"))
        assertTrue(src.contains("Vault.remainingMs() > 0")) // off during a wrong-try lockout
    }

    // ---------- 6: split bundle install ----------

    private val sampleNames = listOf(
        "base.apk", "split_config.arm64_v8a.apk", "split_config.armeabi_v7a.apk", "split_config.x86.apk",
        "split_config.mdpi.apk", "split_config.xhdpi.apk", "split_config.xxhdpi.apk", "split_config.xxxhdpi.apk",
        "split_config.en.apk", "split_config.hi.apk", "split_config.fr.apk", "split_maps.apk", "split_maps.config.arm64_v8a.apk", "split_maps.config.armeabi_v7a.apk",
    )

    @Test fun classifiesBundleParts() {
        assertEquals(SplitPart.Kind.BASE, SplitSelect.classify("base.apk", "base.apk").kind)
        assertEquals(SplitPart.Kind.ABI, SplitSelect.classify("config.arm64_v8a.apk", "base.apk").kind)
        assertEquals(SplitPart.Kind.DENSITY, SplitSelect.classify("split_config.xxhdpi.apk", "base.apk").kind)
        assertEquals(SplitPart.Kind.LANGUAGE, SplitSelect.classify("split_config.hi.apk", "base.apk").kind)
        assertEquals(SplitPart.Kind.FEATURE, SplitSelect.classify("split_maps.apk", "base.apk").kind)
        assertEquals("maps", SplitSelect.classify("split_maps.config.arm64_v8a.apk", "base.apk").group)
    }

    @Test fun picksOnlyWhatThisPhoneNeeds() {
        val plan = SplitSelect.select(sampleNames, listOf("arm64-v8a", "armeabi-v7a", "armeabi"), 420, listOf("hi", "en"))
        assertTrue(plan.canInstall)
        assertEquals(
            setOf("base.apk", "split_maps.apk", "split_config.arm64_v8a.apk", "split_maps.config.arm64_v8a.apk", "split_config.xxhdpi.apk", "split_config.hi.apk", "split_config.en.apk"),
            plan.chosenEntries.toSet(),
        )
        assertTrue(plan.skipped.any { it.entry == "split_config.x86.apk" })
        assertTrue(plan.skipped.any { it.entry == "split_config.fr.apk" })
        // A 32-bit phone gets the v7a part; a huge-screen phone the biggest density.
        val old = SplitSelect.select(sampleNames, listOf("armeabi-v7a", "armeabi"), 700, listOf("fr"))
        assertTrue("split_config.armeabi_v7a.apk" in old.chosenEntries)
        assertTrue("split_config.xxxhdpi.apk" in old.chosenEntries)
        assertTrue("split_config.fr.apk" in old.chosenEntries)
        assertTrue(old.canInstall)
        assertTrue("split_maps.config.armeabi_v7a.apk" in old.chosenEntries)
        // Built only for x86: says so instead of failing in the installer.
        val wrongCpu = SplitSelect.select(listOf("base.apk", "split_config.x86.apk"), listOf("arm64-v8a"), 420, listOf("en"))
        assertFalse(wrongCpu.canInstall)
        assertTrue(wrongCpu.problem!!.contains("x86"))
    }

    private fun bundle(name: String, entries: Map<String, ByteArray>): File {
        val f = File(app.cacheDir, name)
        ZipOutputStream(f.outputStream()).use { z -> entries.forEach { (n, b) -> z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }
        return f
    }

    @Test fun xapkPlanCarriesObbAndPackage() {
        val manifest = """{"package_name":"com.example.game","split_apks":[{"file":"com.example.game.apk","id":"base"},{"file":"config.arm64_v8a.apk","id":"config.arm64_v8a"}],
            "expansions":[{"file":"Android/obb/com.example.game/main.7.com.example.game.obb","install_location":"EXTERNAL_STORAGE","install_path":"Android/obb/com.example.game/main.7.com.example.game.obb"}]}"""
        val f = bundle("game.xapk", mapOf(
            "manifest.json" to manifest.toByteArray(), "com.example.game.apk" to ByteArray(10), "config.arm64_v8a.apk" to ByteArray(10),
            "config.x86_64.apk" to ByteArray(10), "Android/obb/com.example.game/main.7.com.example.game.obb" to ByteArray(20),
        ))
        val plan = SplitSelect.plan(f, listOf("arm64-v8a"), 480, listOf("en"))
        assertTrue(plan.canInstall)
        assertEquals("com.example.game", plan.packageName)
        assertEquals(setOf("com.example.game.apk", "config.arm64_v8a.apk"), plan.chosenEntries.toSet())
        assertEquals(1, plan.obb.size)
        assertEquals("Android/obb/com.example.game/main.7.com.example.game.obb", plan.obb[0].second)
    }

    @Test fun lockedApkmSaysSo() {
        val f = File(app.cacheDir, "locked.apkm").apply { writeBytes(ByteArray(300) { (it * 7).toByte() }) }
        val plan = SplitSelect.plan(f, listOf("arm64-v8a"), 480, listOf("en"))
        assertFalse(plan.canInstall)
        assertTrue(plan.problem!!.contains("APKMirror"))
    }

    @Test fun installerStreamsAllChosenPartsIntoOneSession() {
        val src = File("src/main/java/com/clpstudio/filesmanager/install/ApkInstaller.kt").readText()
        val bundleFn = src.substringAfter("private fun commitBundle")
        assertTrue(bundleFn.contains("installer.createSession(params)"))
        assertTrue(bundleFn.substringBefore("session.commit").contains("entries.forEachIndexed"))
    }

    @Test @Config(qualifiers = "w393dp-h1000dp-xxhdpi")
    fun splitInstallSheet_render() {
        val tiny = ApkViewerTest.resource("tiny.apk").readBytes()
        val f = bundle("Tiny App 2.3.1.apks", sampleNames.associateWith { if (it == "base.apk") tiny else ByteArray(64) })
        val d = ApkInspector.load(app, f)
        assertNotNull(d.splitPlan)
        assertTrue(d.splitPlan.toString() + " abis=" + android.os.Build.SUPPORTED_ABIS.toList(), d.splitPlan!!.canInstall)
        assertTrue(d.isBundle)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            FilesTheme(ThemeMode.LIGHT, liquidGlass = true) {
                ApkViewerContent(d, DeviceProfile(35, listOf("arm64-v8a")), onBack = {}, showSplitSheet = true)
            }
        }
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithTag("splitSheet").assertExists()
        compose.onRoot().captureRoboImage("$shots/files_f11_split_install.png")
    }

    // ---------- app lock / vault fingerprint fix (Charan 9:15 PM: "App lock with biometric not working") ----------

    @Test fun authenticatorChoice() {
        assertEquals(android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_WEAK, com.clpstudio.filesmanager.security.Biometrics.authenticators(com.clpstudio.filesmanager.security.Biometrics.Purpose.APP_LOCK))
        assertEquals(android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG, com.clpstudio.filesmanager.security.Biometrics.authenticators(com.clpstudio.filesmanager.security.Biometrics.Purpose.VAULT_KEY))
    }

    @Test fun availabilityStatesAreNotSilent() {
        assertEquals(B.Status.READY, B.status(0))
        assertEquals(B.Status.NOT_ENROLLED, B.status(11))
        assertEquals(B.Status.NO_HARDWARE, B.status(12))
        assertEquals(B.Status.BUSY, B.status(1))
        assertTrue(B.usable(B.Status.BUSY)) // used to hide the button
        assertFalse(B.usable(B.Status.NOT_ENROLLED))
        assertTrue(B.statusText(B.Status.NOT_ENROLLED, B.Purpose.APP_LOCK).contains("Settings"))
    }

    @Test fun promptErrorsAreHandled() {
        assertEquals(B.Outcome.Silent, B.onError(10, "Cancelled", 4000, false)) // user cancelled
        assertEquals(B.Outcome.Silent, B.onError(13, "Use PIN", 4000, false)) // "Use PIN"
        assertEquals(B.Outcome.Retry, B.onError(5, "Canceled", 300, false)) // system cancelled right after showing
        assertEquals(B.Outcome.Silent, B.onError(5, "Canceled", 300, true)) // only once
        assertEquals(B.Outcome.Silent, B.onError(5, "Canceled", 5000, false))
        assertTrue(B.onError(7, null, 2000, false) is B.Outcome.Show)
        assertTrue(B.onError(1, null, 100, false) is B.Outcome.Show)
        assertTrue(B.onError(11, null, 100, false) is B.Outcome.Show)
        assertEquals(B.Outcome.Show("Odd error"), B.onError(99, "Odd error", 100, false))
    }

    @Test fun fingerprintSuccessDoesNotRelockButLeavingStillDoes() {
        val t = com.clpstudio.filesmanager.security.LockTimer()
        val now = com.clpstudio.filesmanager.security.AskAfter.IMMEDIATELY
        t.onAppStart(true)
        // Prompt is an activity on this phone: the app "stops" while it shows, then the fingerprint opens the lock.
        t.onBackground(true, now, 1_000L)
        t.onUnlocked(1_500L)
        t.onForeground(true, now, 1_700L)
        assertFalse("back from the prompt: stays open", t.locked)
        // Really leaving later still locks.
        t.onBackground(true, now, 10_000L)
        t.onForeground(true, now, 20_000L)
        assertTrue(t.locked)
        // Background while the prompt is open (promptOpen) isn't counted.
        val t2 = com.clpstudio.filesmanager.security.LockTimer()
        t2.onAppStart(true); t2.onUnlocked(100L)
        t2.onBackground(true, now, 1_000L, promptOpen = true)
        t2.onForeground(true, now, 9_000L)
        assertFalse(t2.locked)
    }

    @Test fun lockoutAfterRebootNeitherStuckNorSkipped() {
        val L = com.clpstudio.filesmanager.security.AttemptLimiter
        val lock = com.clpstudio.filesmanager.security.AttemptState(5, 300_000L, 1_000_000L, bootId = 3)
        val reboot = com.clpstudio.filesmanager.security.LockClock(5_000L, 4) // elapsed restarts at 0 after a reboot
        assertEquals(300_000L, L.remaining(lock, reboot))
        val anchored = L.checkpoint(lock, reboot)
        assertEquals(240_000L, L.remaining(anchored, com.clpstudio.filesmanager.security.LockClock(65_000L, 4)))
        assertEquals(0L, L.remaining(anchored, com.clpstudio.filesmanager.security.LockClock(400_000L, 4))) // ends
        assertEquals(0L, L.remaining(lock, com.clpstudio.filesmanager.security.LockClock(1_400_000L, 3)))
    }

    @Test fun invalidatedVaultKeyIsRecognised() {
        val e = android.security.keystore.KeyPermanentlyInvalidatedException("new fingerprint")
        assertTrue(com.clpstudio.filesmanager.vault.VaultBiometric.invalidated(e))
        assertTrue(com.clpstudio.filesmanager.vault.VaultBiometric.invalidated(java.security.InvalidKeyException("wrapped", e)))
        assertFalse(com.clpstudio.filesmanager.vault.VaultBiometric.invalidated(IllegalStateException("x")))
        assertTrue(com.clpstudio.filesmanager.vault.VaultBiometric.unlockErrorMessage(e).contains("new fingerprint was added"))
    }

    @Test fun promptWaitsForFocusAndKeyboardStaysShut() {
        val ui = File("src/main/java/com/clpstudio/filesmanager/ui/security/SecurityUi.kt").readText()
        val screen = ui.substringAfter("fun AppLockScreen(")
        assertTrue(screen.substringBefore("ask()\n").contains("Biometrics.awaitFocus(activity)"))
        assertTrue(ui.contains("autoFocus = !bio"))
        assertTrue(screen.contains("signal?.cancel()"))
        val vault = File("src/main/java/com/clpstudio/filesmanager/ui/vault/VaultScreens.kt").readText()
        assertTrue(vault.contains("Biometrics.awaitFocus(activity)"))
        assertTrue(vault.contains("autoFocus = !bio"))
        val bio = File("src/main/java/com/clpstudio/filesmanager/security/Biometrics.kt").readText()
        assertTrue(bio.contains("override fun onAuthenticationError"))
    }

    @Test fun appLockWithFingerprint_render() {
        compose.setContent {
            FilesTheme(ThemeMode.LIGHT, liquidGlass = false) {
                androidx.compose.material3.Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                        com.clpstudio.filesmanager.ui.security.AppLockForm(
                            bio = true, bioMsg = "The fingerprint sensor is busy. Tap \"Use fingerprint\" to try again.",
                            onFingerprint = {}, type = CredentialType.PIN, remainingMs = { 0L },
                        )
                    }
                }
            }
        }
        compose.onNodeWithTag("useFingerprint").assertExists()
        compose.onRoot().captureRoboImage("$shots/files_f11_applock_fingerprint.png")
    }
}
