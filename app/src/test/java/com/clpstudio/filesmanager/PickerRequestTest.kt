package com.clpstudio.filesmanager

import com.clpstudio.filesmanager.data.FileItem
import com.clpstudio.filesmanager.picker.PickerRequest
import com.clpstudio.filesmanager.picker.PickerSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PickerRequestTest {
    private fun f(name: String) = FileItem("/s/$name", name, false, 1, 1)

    @Test fun anyTypeAcceptsEverything() {
        val r = PickerRequest.from("*/*", null, false)
        assertTrue(r.accepts(f("a.pdf")))
        assertTrue(r.accepts(f("b.unknownext")))
        assertEquals(PickerSection.RECENT, r.startSection)
        assertEquals(PickerSection.entries.toList(), r.sections)
        assertEquals("Choose a file", r.title)
    }

    @Test fun imagesOnly() {
        val r = PickerRequest.from("image/*", null, true)
        assertTrue(r.accepts(f("a.jpg")))
        assertTrue(r.accepts(f("b.PNG")))
        assertFalse(r.accepts(f("c.mp4")))
        assertFalse(r.accepts(f("d.pdf")))
        assertEquals(PickerSection.PHOTOS, r.startSection)
        assertFalse(PickerSection.AUDIO in r.sections)
        assertTrue(PickerSection.BROWSE in r.sections)
        assertTrue(r.multiple)
        assertEquals("Choose photos", r.title)
    }

    @Test fun extraMimeTypesWinOverAWildcardType() {
        val r = PickerRequest.from("*/*", arrayOf("application/pdf", "image/*"), false)
        assertTrue(r.accepts(f("a.pdf")))
        assertTrue(r.accepts(f("b.jpg")))
        assertFalse(r.accepts(f("c.mp3")))
    }

    @Test fun pickOnAMediaStoreTable() {
        assertEquals("image/*", PickerRequest.normalize("vnd.android.cursor.dir/image"))
        assertEquals("video/*", PickerRequest.normalize("vnd.android.cursor.dir/video"))
        assertEquals("audio/*", PickerRequest.normalize("vnd.android.cursor.item/audio"))
        assertEquals("*/*", PickerRequest.normalize("vnd.android.cursor.dir/contact"))
        val r = PickerRequest.from("vnd.android.cursor.dir/video", null, false)
        assertEquals(PickerSection.VIDEOS, r.startSection)
        assertTrue(r.accepts(f("v.mkv")))
    }

    @Test fun missingTypeMeansAnything() {
        assertTrue(PickerRequest.from(null, null, false).acceptsAll)
        assertTrue(PickerRequest.from("", emptyArray(), false).acceptsAll)
    }

    @Test fun foldersAreNeverPicked() {
        val r = PickerRequest.from("*/*", null, false)
        assertFalse(r.accepts(FileItem("/s/DCIM", "DCIM", true, 0, 0)))
    }

    @Test fun exactTypeMatch() {
        assertTrue(PickerRequest.matches("application/pdf", "application/pdf"))
        assertFalse(PickerRequest.matches("application/pdf", "application/zip"))
        assertTrue(PickerRequest.matches("audio/*", "audio/mpeg"))
    }
}
