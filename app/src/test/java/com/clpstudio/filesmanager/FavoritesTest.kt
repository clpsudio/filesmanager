package com.clpstudio.filesmanager

import com.clpstudio.filesmanager.data.FavoritePaths
import com.clpstudio.filesmanager.data.Favorites
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class FavoritesTest {
    @Test fun remapFile() {
        val set = setOf("/s/DCIM/a.jpg", "/s/DCIM/b.jpg")
        assertEquals(setOf("/s/DCIM/c.jpg", "/s/DCIM/b.jpg"), FavoritePaths.remap(set, "/s/DCIM/a.jpg", "/s/DCIM/c.jpg"))
    }

    @Test fun remapFolderMovesChildren() {
        val set = setOf("/s/Trip", "/s/Trip/day1/a.jpg", "/s/Trip/b.mp4", "/s/Other/x.pdf")
        assertEquals(
            setOf("/s/Old/Trip 2025", "/s/Old/Trip 2025/day1/a.jpg", "/s/Old/Trip 2025/b.mp4", "/s/Other/x.pdf"),
            FavoritePaths.remap(set, "/s/Trip/", "/s/Old/Trip 2025"),
        )
    }

    @Test fun remapLeavesSimilarPrefixAlone() {
        val set = setOf("/a/b/x.jpg", "/a/bc/y.jpg", "/a/bc")
        assertEquals(setOf("/a/z/x.jpg", "/a/bc/y.jpg", "/a/bc"), FavoritePaths.remap(set, "/a/b", "/a/z"))
        assertTrue(FavoritePaths.isUnder("/a/b/c", "/a/b"))
        assertFalse(FavoritePaths.isUnder("/a/bc", "/a/b"))
    }

    @Test fun deleteRemovesFolderContentsOnly() {
        val set = setOf("/a/b", "/a/b/c.jpg", "/a/bc/d.jpg", "/e.txt")
        assertEquals(setOf("/a/bc/d.jpg", "/e.txt"), FavoritePaths.removeUnder(set, listOf("/a/b")))
        assertEquals(set, FavoritePaths.removeUnder(set, listOf("/nothing")))
    }

    @Test fun encodeDecodeRoundTrip() {
        val set = linkedSetOf("/s/a b.jpg", "/s/ünï.pdf", "/s/folder")
        assertEquals(set, FavoritePaths.decode(FavoritePaths.encode(set) + "\n\njunk line\n/s/folder/"))
    }

    @Test fun storeTogglesAddsRemovesAndPersists() {
        val dir = Files.createTempDirectory("fav").toFile()
        val store = File(dir, "favorites.txt")
        store.writeText("/s/kept.jpg\n")
        Favorites.init(store)
        Favorites.toggle("/s/a.jpg") // may run before the file is read: must survive the load
        Favorites.flush()
        assertTrue(Favorites.isFavorite("/s/a.jpg"))
        assertTrue(Favorites.isFavorite("/s/kept.jpg"))

        Favorites.toggle("/s/a.jpg")
        assertFalse(Favorites.isFavorite("/s/a.jpg"))
        Favorites.add(listOf("/s/Trip/1.jpg", "/s/Trip/2.jpg", "/s/x.pdf"))
        Favorites.remove(listOf("/s/x.pdf"))
        Favorites.onMoved("/s/Trip", "/s/Trips/2026")
        Favorites.onDeleted(listOf("/s/kept.jpg"))
        Favorites.flush()
        val expected = setOf("/s/Trips/2026/1.jpg", "/s/Trips/2026/2.jpg")
        assertEquals(expected, Favorites.paths.value)
        assertEquals(expected, FavoritePaths.decode(store.readText()))

        // A fresh store on the same file reads the saved set back.
        val other = File(dir, "other.txt")
        Favorites.init(other)
        Favorites.flush()
        assertEquals(emptySet<String>(), Favorites.paths.value)
        Favorites.init(store)
        Favorites.flush()
        assertEquals(expected, Favorites.paths.value)
        dir.deleteRecursively()
    }

    @Test fun hiddenTargetGoesIntoDotHiddenWithUniqueName() {
        val dir = Files.createTempDirectory("hide").toFile()
        val photo = File(dir, "IMG_1.jpg").apply { writeText("x") }
        File(dir, ".hidden").mkdirs()
        File(dir, ".hidden/IMG_1.jpg").writeText("old")
        val target = com.clpstudio.filesmanager.viewer.PhotoActions.hiddenTarget(photo)
        assertEquals(File(dir, ".hidden/IMG_1 (1).jpg"), target)
        dir.deleteRecursively()
    }
}
