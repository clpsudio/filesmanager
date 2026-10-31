package com.clpstudio.filesmanager

import com.clpstudio.filesmanager.data.AppSettings
import com.clpstudio.filesmanager.data.Category
import com.clpstudio.filesmanager.data.DoubleTapSkip
import com.clpstudio.filesmanager.data.FileItem
import com.clpstudio.filesmanager.data.StorageVolume
import com.clpstudio.filesmanager.ui.PlayRoute
import com.clpstudio.filesmanager.ui.screens.StorageBreakdown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BugRound5Test {
    private fun f(name: String, size: Long = 1, dir: String = "/s") = FileItem("$dir/$name", name, false, size, 1)

    @Test fun audioOnlySelectionGoesToTheMusicPlaylist() {
        assertEquals(PlayRoute.MUSIC, PlayRoute.of(listOf(f("a.mp3"), f("b.m4a"))))
        assertEquals(PlayRoute.VIDEO, PlayRoute.of(listOf(f("a.mp4"), f("b.mkv"))))
        assertEquals(PlayRoute.SLIDESHOW, PlayRoute.of(listOf(f("a.jpg"), f("b.mp4"))))
        assertEquals(PlayRoute.SLIDESHOW, PlayRoute.of(listOf(f("a.mp3"), f("b.mp4"))))
        assertEquals(PlayRoute.NONE, PlayRoute.of(listOf(f("a.pdf"))))
    }

    @Test fun pictureTimeOnlyWithPhotos() {
        assertFalse(PlayRoute.hasPhotos(listOf(f("a.mp4"), f("b.mp3"))))
        assertTrue(PlayRoute.hasPhotos(listOf(f("a.mp4"), f("b.jpg"))))
        assertFalse(PlayRoute.hasPhotos(emptyList()))
    }

    @Test fun doubleTapSkipDefaultsAndLimits() {
        assertEquals(10, AppSettings().doubleTapSkipSeconds)
        assertEquals(listOf(5, 10, 15, 20, 30), DoubleTapSkip.PRESETS)
        assertEquals(1, DoubleTapSkip.clamp(0))
        assertEquals(60, DoubleTapSkip.clamp(99))
        assertEquals(15_000L, DoubleTapSkip.ms(15))
        assertEquals("15 s", DoubleTapSkip.label(15))
        assertEquals(45, DoubleTapSkip.parse("45"))
        assertEquals(7, DoubleTapSkip.parse(" 7 s"))
        assertNull(DoubleTapSkip.parse("0"))
        assertNull(DoubleTapSkip.parse("61"))
        assertNull(DoubleTapSkip.parse("abc"))
        assertTrue(DoubleTapSkip.isPreset(20))
        assertFalse(DoubleTapSkip.isPreset(25))
    }

    @Test fun storageBreakdownCountsOnlyThatVolumeAndNeverExceedsUsed() {
        val vol = StorageVolume("/storage/emulated/0", "Internal storage", total = 100, free = 40)
        val lists = mapOf(
            Category.PHOTOS to listOf(f("a.jpg", 20, "/storage/emulated/0/DCIM"), f("sd.jpg", 500, "/storage/1234-ABCD/DCIM")),
            Category.VIDEOS to listOf(f("v.mp4", 30, "/storage/emulated/0/Movies")),
            Category.AUDIO to null,
            Category.DOCUMENTS to listOf(f("d.pdf", 50, "/storage/emulated/0/Download")),
        )
        val slices = StorageBreakdown.slices(vol, lists)
        assertEquals(listOf("Photos", "Videos", "Audios", "Documents", "Other"), slices.map { it.label })
        assertEquals(listOf(20L, 30L, 0L, 10L, 0L), slices.map { it.bytes }) // capped at the 60 used
        assertEquals(60L, slices.sumOf { it.bytes })
    }
}
