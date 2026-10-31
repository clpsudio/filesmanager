package com.clpstudio.filesmanager

import com.clpstudio.filesmanager.data.Category
import com.clpstudio.filesmanager.data.ChipConfig
import com.clpstudio.filesmanager.data.FileItem
import com.clpstudio.filesmanager.data.TypeFilters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderFiltersTest {
    private fun ids(c: Category, cfg: ChipConfig?) = TypeFilters.customized(c, cfg).map { it.id }

    @Test fun defaultIsUnchangedAndAllFirst() {
        assertEquals(TypeFilters.chipsFor(Category.VIDEOS).map { it.id }, ids(Category.VIDEOS, null))
        assertEquals(TypeFilters.ALL, ids(Category.VIDEOS, null).first())
        assertTrue("APP_WHATSAPP" in ids(Category.PHOTOS, null))
    }

    @Test fun hiddenChipsGoAwayButAllStays() {
        val cfg = ChipConfig(hidden = setOf("APP_WHATSAPP", TypeFilters.ALL, "MP4"))
        val got = ids(Category.VIDEOS, cfg)
        assertFalse("APP_WHATSAPP" in got); assertFalse("MP4" in got)
        assertEquals(TypeFilters.ALL, got.first())
    }

    @Test fun userOrderFirstUnknownKeepDefaultPlace() {
        val got = ids(Category.VIDEOS, ChipConfig(order = listOf("APP_TELEGRAM", "MKV")))
        assertEquals(listOf(TypeFilters.ALL, "APP_TELEGRAM", "MKV", "MP4"), got.take(4))
        assertEquals(TypeFilters.chipsFor(Category.VIDEOS).size, got.size)
    }

    @Test fun moveAndRoundTrip() {
        assertEquals(listOf("b", "a", "c"), ChipConfig.move(listOf("a", "b", "c"), "b", -1))
        assertEquals(listOf("a", "b", "c"), ChipConfig.move(listOf("a", "b", "c"), "a", -1))
        val m = mapOf("PHOTOS" to ChipConfig(listOf("PNG", "JPG"), setOf("GIF")), "VIDEOS" to ChipConfig(emptyList(), setOf("MKV")))
        assertEquals(m, ChipConfig.decode(ChipConfig.encode(m)))
        assertEquals(emptyMap<String, ChipConfig>(), ChipConfig.decode(""))
    }

    @Test fun appFolderChipsMatchTheirFolders() {
        val wa = FileItem("/storage/emulated/0/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/IMG-1.jpg", "IMG-1.jpg", false, 1, 1)
        val tg = FileItem("/storage/emulated/0/Pictures/Telegram/a.jpg", "a.jpg", false, 1, 1)
        val counted = TypeFilters.counted(Category.PHOTOS, listOf(wa, tg), TypeFilters.ALL).associate { it.first.id to it.second }
        assertEquals(1, counted["APP_WHATSAPP"]); assertEquals(1, counted["APP_TELEGRAM"])
        val hidden = TypeFilters.counted(Category.PHOTOS, listOf(wa, tg), TypeFilters.ALL, ChipConfig(hidden = setOf("APP_WHATSAPP"))).map { it.first.id }
        assertFalse("APP_WHATSAPP" in hidden)
    }
}
