package com.clpstudio.filesmanager

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.clpstudio.filesmanager.data.AppSettings
import com.clpstudio.filesmanager.data.BinRepository
import com.clpstudio.filesmanager.data.CardBin
import com.clpstudio.filesmanager.data.HiddenFilter
import com.clpstudio.filesmanager.data.LocationFilter
import com.clpstudio.filesmanager.data.MountedVolume
import com.clpstudio.filesmanager.data.StorageBox
import com.clpstudio.filesmanager.data.StorageLocation
import com.clpstudio.filesmanager.data.TransferRules
import com.clpstudio.filesmanager.data.VolumeRules
import com.clpstudio.filesmanager.ui.screens.HomeContent
import com.clpstudio.filesmanager.ui.screens.HomeState
import com.clpstudio.filesmanager.ui.screens.HomeTile
import com.clpstudio.filesmanager.ui.theme.FilesTheme
import com.clpstudio.filesmanager.data.ThemeMode
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w393dp-h852dp-xxhdpi")
class SdCardTest {
    @get:Rule val tmp = TemporaryFolder()
    @get:Rule val compose = createComposeRule()

    private val phone = MountedVolume("/storage/emulated/0", null, removable = false, primary = true)
    private val card = MountedVolume("/storage/1A2B-3C4D", "SanDisk SD card", removable = true, primary = false)

    @Test fun showSdCard_isOnByDefault() {
        assertTrue(AppSettings().showSdCard)
    }

    @Test fun location_phoneOrCard() {
        val roots = listOf(card.path)
        assertEquals(StorageLocation.SD_CARD, VolumeRules.locationOf("/storage/1A2B-3C4D/DCIM/a.jpg", roots))
        assertEquals(StorageLocation.PHONE, VolumeRules.locationOf("/storage/emulated/0/DCIM/a.jpg", roots))
        // A sibling folder with the same prefix is not on the card.
        assertEquals(StorageLocation.PHONE, VolumeRules.locationOf("/storage/1A2B-3C4D-x/a.jpg", roots))
        assertEquals("Device", StorageLocation.PHONE.label)
        assertEquals("SD card", StorageLocation.SD_CARD.label)
    }

    @Test fun merge_primaryFirst_noDuplicates() {
        val merged = VolumeRules.merge(listOf(card, phone), listOf(card.copy(path = card.path + "/", description = null)), phone.path)
        assertEquals(listOf(phone.path, card.path), merged.map { it.path })
        assertEquals("SD card", merged[1].label)
        assertEquals("Internal storage", merged[0].label)
    }

    @Test fun setting_hidesCardEverywhere() {
        val all = listOf(phone, card)
        assertEquals(2, VolumeRules.visible(all, true).size)
        assertEquals(listOf(phone), VolumeRules.visible(all, false))
        assertEquals(emptyList<String>(), VolumeRules.hiddenRoots(all, true))
        assertEquals(listOf(card.path), VolumeRules.hiddenRoots(all, false))
        assertTrue(VolumeRules.showLocationChips(all, true))
        assertFalse(VolumeRules.showLocationChips(all, false))
        assertFalse(VolumeRules.showLocationChips(listOf(phone), true))
        val f = HiddenFilter(showHidden = true, hiddenRoots = listOf(card.path))
        assertFalse(f.visiblePath("/storage/1A2B-3C4D/DCIM/a.jpg"))
        assertTrue(f.visiblePath("/storage/emulated/0/DCIM/a.jpg"))
    }

    @Test fun categoryFilter_deviceAndCard() {
        val items = listOf("/storage/emulated/0/a.jpg", "/storage/1A2B-3C4D/b.jpg", "/storage/emulated/0/c.jpg")
        val roots = listOf(card.path)
        assertEquals(3, VolumeRules.filterByLocation(items, LocationFilter.ALL, roots) { it }.size)
        assertEquals(listOf(items[0], items[2]), VolumeRules.filterByLocation(items, LocationFilter.PHONE, roots) { it })
        assertEquals(listOf(items[1]), VolumeRules.filterByLocation(items, LocationFilter.SD_CARD, roots) { it })
    }

    @Test fun transfer_checksAndPlainErrors() {
        assertNull(TransferRules.problem(10, 100, true, StorageLocation.SD_CARD, false))
        assertTrue(TransferRules.problem(200, 100, true, StorageLocation.SD_CARD, false)!!.startsWith("Not enough space on the SD card"))
        // A move inside one volume is a rename: no space needed.
        assertNull(TransferRules.problem(200, 100, true, StorageLocation.SD_CARD, true))
        assertTrue(TransferRules.problem(1, 100, false, StorageLocation.SD_CARD, false)!!.contains("read-only"))
        assertTrue(TransferRules.problem(200, 100, true, StorageLocation.PHONE, false)!!.contains("device storage"))
        assertEquals("Not enough space on the SD card", TransferRules.friendlyError(IOException("write failed: ENOSPC (No space left on device)"), StorageLocation.SD_CARD))
        assertEquals("The SD card is read-only", TransferRules.friendlyError(IOException("EROFS (Read-only file system)"), StorageLocation.SD_CARD))
        assertEquals("The SD card was removed or can't be read", TransferRules.friendlyError(IOException("EIO (I/O error)"), StorageLocation.SD_CARD))
        assertEquals("/storage/1A2B-3C4D", TransferRules.volumeRootOf("/storage/1A2B-3C4D/x", listOf(phone.path, card.path)))
    }

    /** Deleting a card file puts it in the card's own Bin (renamed, never copied to the phone). */
    @Test fun bin_cardFileStaysOnCard_andRestores() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        val phoneBin = tmp.newFolder("phone", "bin")
        val cardRoot = tmp.newFolder("card")
        val cardBin = File(cardRoot, "Android/data/com.clpstudio.filesmanager/files/bin")
        val bin = BinRepository(ctx, phoneBin) { listOf(CardBin(cardRoot.absolutePath, cardBin)) }

        val onCard = File(cardRoot, "DCIM/holiday.jpg").apply { parentFile!!.mkdirs(); writeText("card photo") }
        val phoneDir = tmp.newFolder("internal", "Download")
        val onPhone = File(phoneDir, "notes.txt").apply { writeText("a longer phone note") }

        assertEquals(cardBin, bin.binFor(onCard))
        assertEquals(phoneBin, bin.binFor(onPhone))
        assertEquals(2, bin.moveToBin(listOf(onCard, onPhone)))
        assertFalse(onCard.exists())
        assertFalse(onPhone.exists())

        val entries = bin.list()
        assertEquals(2, entries.size)
        val cardEntry = entries.first { it.name == "holiday.jpg" }
        assertEquals(cardBin, cardEntry.dataFile.parentFile)
        assertEquals(phoneBin, entries.first { it.name == "notes.txt" }.dataFile.parentFile)
        // Nothing of the card file landed in the phone's Bin.
        assertTrue(phoneBin.listFiles().orEmpty().none { it.isFile && it.length() == "card photo".length.toLong() })

        assertEquals(1, bin.restore(listOf(cardEntry)))
        assertEquals("card photo", onCard.readText())
        assertFalse(File(cardBin, cardEntry.id + ".json").exists())

        val phoneEntry = bin.list().single()
        assertEquals(1, bin.deleteForever(listOf(phoneEntry)))
        assertTrue(bin.list().isEmpty())
    }

    /** Card taken out: its Bin items wait on the card; the phone's Bin still works. */
    @Test fun bin_cardRemoved_phoneBinUnaffected() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        val phoneBin = tmp.newFolder("pb")
        val f = File(tmp.newFolder("p"), "a.txt").apply { writeText("x") }
        val bin = BinRepository(ctx, phoneBin) { emptyList() }
        assertEquals(1, bin.moveToBin(listOf(f)))
        assertEquals(1, bin.list().size)
    }

    private fun homeState(cards: Boolean) = HomeState(
        used = 71_400_000_000, total = 128_000_000_000,
        counts = mapOf(
            HomeTile.RECENT to "18 items", HomeTile.PHOTOS to "2,431 items", HomeTile.VIDEOS to "186 items",
            HomeTile.AUDIO to "412 items", HomeTile.DOCUMENTS to "97 items", HomeTile.DOWNLOADS to "64 items",
        ),
        sdCards = if (cards) listOf(StorageBox("/storage/1A2B-3C4D", "SD card", 21_300_000_000, 63_800_000_000, true)) else emptyList(),
    )

    private fun shot(name: String, content: @Composable () -> Unit) {
        compose.setContent { CompositionLocalProvider(LocalInspectionMode provides true) { content() } }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1500)
        compose.onRoot().captureRoboImage(File("build/screenshots", "$name.png").path)
    }

    @Test fun home_withSdCard_dark() = shot("files_p2_sd_home_dark") {
        FilesTheme(ThemeMode.DARK, liquidGlass = true) { HomeContent(homeState(true), {}, {}, {}, {}, { _, _ -> }) }
    }

    @Test fun home_withSdCard_light() = shot("files_p2_sd_home_light") {
        FilesTheme(ThemeMode.LIGHT, liquidGlass = true) { HomeContent(homeState(true), {}, {}, {}, {}, { _, _ -> }) }
    }
}
