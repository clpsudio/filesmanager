package com.clpstudio.filesmanager

import com.clpstudio.filesmanager.data.HiddenNames
import com.clpstudio.filesmanager.data.HideChoice
import com.clpstudio.filesmanager.data.HideMenu
import com.clpstudio.filesmanager.ui.screens.HiddenChip
import com.clpstudio.filesmanager.data.FileItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HiddenFilesTest {
    private val root = "/storage/emulated/0"

    @Test fun whatCountsAsHidden() {
        assertTrue(HiddenNames.isHidden("$root/.secret"))
        assertTrue(HiddenNames.isHidden("$root/DCIM/.hidden/IMG_1.jpg"))
        assertFalse(HiddenNames.isHidden("$root/DCIM/IMG_1.jpg"))
        assertTrue(HiddenNames.isHidden("$root/DCIM/.hidden")) // a dot-folder too
    }

    @Test fun unhideOutOfOurHiddenFolder() {
        val f = File("$root/DCIM/.hidden/IMG_1.jpg")
        assertEquals(File("$root/DCIM/IMG_1.jpg"), HiddenNames.unhideTarget(f, false) { false })
    }

    @Test fun unhideDropsTheLeadingDots() {
        assertEquals(File("$root/Docs/notes.txt"), HiddenNames.unhideTarget(File("$root/Docs/.notes.txt"), false) { false })
        assertEquals(File("$root/Projects"), HiddenNames.unhideTarget(File("$root/..Projects"), true) { false })
        assertNull(HiddenNames.unhideTarget(File("$root/Docs/plain.txt"), false) { false })
        assertNull(HiddenNames.unhideTarget(File("$root/Docs/..."), false) { false })
    }

    @Test fun nameClashesGetANumber() {
        val taken = setOf("$root/DCIM/IMG_1.jpg", "$root/DCIM/IMG_1 (1).jpg")
        assertEquals(File("$root/DCIM/IMG_1 (2).jpg"), HiddenNames.unhideTarget(File("$root/DCIM/.hidden/IMG_1.jpg"), false) { it.path in taken })
        val dirs = setOf("$root/My.Folder")
        assertEquals(File("$root/My.Folder (1)"), HiddenNames.unhideTarget(File("$root/.My.Folder"), true) { it.path in dirs })
    }

    @Test fun menuShowsUnhideForHiddenAndHideForNormal() {
        assertEquals(HideChoice(hide = false, unhide = true), HideMenu.decide(listOf("$root/.a", "$root/X/.hidden/b.jpg")))
        assertEquals(HideChoice(hide = true, unhide = false), HideMenu.decide(listOf("$root/a.jpg")))
        assertEquals(HideChoice(hide = true, unhide = true), HideMenu.decide(listOf("$root/a.jpg", "$root/.b")))
        assertEquals(HideChoice(hide = false, unhide = false), HideMenu.decide(emptyList()))
    }

    @Test fun chipsSortByType() {
        val dir = FileItem("$root/.cfg", ".cfg", true, 0, 1)
        val img = FileItem("$root/.hidden/a.jpg", "a.jpg", false, 10, 1)
        val zip = FileItem("$root/.b.zip", ".b.zip", false, 10, 1)
        assertTrue(HiddenChip.FOLDERS.matches(dir)); assertFalse(HiddenChip.FOLDERS.matches(img))
        assertTrue(HiddenChip.IMAGES.matches(img)); assertTrue(HiddenChip.OTHER.matches(zip))
        assertTrue(HiddenChip.ALL.matches(zip))
    }
}
