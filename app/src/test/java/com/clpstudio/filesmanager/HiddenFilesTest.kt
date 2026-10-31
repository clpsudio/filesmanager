package com.clpstudio.filesmanager

import com.clpstudio.filesmanager.data.HiddenNames
import com.clpstudio.filesmanager.data.HideChoice
import com.clpstudio.filesmanager.data.HideMenu
import com.clpstudio.filesmanager.ui.screens.HiddenChip
import com.clpstudio.filesmanager.data.FileItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HiddenFilesTest {
    private val root = "/storage/emulated/0"

    @Test fun whatCountsAsHidden() {
        assertTrue(HiddenNames.isHidden("$root/.secret"))
        assertTrue(HiddenNames.isHidden("$root/DCIM/.hidden/IMG_1.jpg"))
        assertFalse(HiddenNames.isHidden("$root/DCIM/IMG_1.jpg"))
        assertTrue(HiddenNames.isHidden("$root/DCIM/.hidden")) // a dot-folder too
    }

    @Test fun menuShowsDeletePermanentlyForHiddenAndHideForNormal_neverUnhide() {
        assertEquals(HideChoice(hide = false, deletePermanently = true), HideMenu.decide(listOf("$root/.a", "$root/X/.hidden/b.jpg")))
        assertEquals(HideChoice(hide = true, deletePermanently = false), HideMenu.decide(listOf("$root/a.jpg")))
        assertEquals(HideChoice(hide = true, deletePermanently = true), HideMenu.decide(listOf("$root/a.jpg", "$root/.b")))
        assertEquals(HideChoice(hide = false, deletePermanently = false), HideMenu.decide(emptyList()))
        // HideChoice has no Unhide field any more.
        assertFalse(HideChoice::class.java.declaredFields.any { it.name.contains("unhide", ignoreCase = true) })
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
