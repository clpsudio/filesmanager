package com.clpstudio.filesmanager

import com.clpstudio.filesmanager.data.DuplicateSet
import com.clpstudio.filesmanager.data.FileItem
import com.clpstudio.filesmanager.data.StorageKind
import com.clpstudio.filesmanager.data.StorageMath
import com.clpstudio.filesmanager.data.StorageVolume
import com.clpstudio.filesmanager.data.SuggestionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ManageStorageLogicTest {
    @get:Rule val tmp = TemporaryFolder()

    private val root = "/storage/emulated/0"
    private val now = 1_800_000_000_000L
    private val day = StorageMath.DAY
    private val mb = 1024L * 1024
    private fun f(path: String, size: Long, ageDays: Long = 1) =
        FileItem("$root/$path", path.substringAfterLast('/'), false, size, now - ageDays * day)

    @Test fun percentRoundsAndClamps() {
        assertEquals(36, StorageMath.percentUsed(128, 81)) // 47/128 = 36.7, truncated
        assertEquals(0, StorageMath.percentUsed(0, 0))
        assertEquals(100, StorageMath.percentUsed(10, -5))
        assertEquals(0, StorageMath.percentUsed(10, 20))
    }

    @Test fun kindsByExtension() {
        assertEquals(StorageKind.VIDEOS, StorageMath.kindOf("a.MP4"))
        assertEquals(StorageKind.IMAGES, StorageMath.kindOf("a.heic"))
        assertEquals(StorageKind.AUDIO, StorageMath.kindOf("a.opus"))
        assertEquals(StorageKind.APKS, StorageMath.kindOf("a.apk"))
        assertEquals(StorageKind.ARCHIVES, StorageMath.kindOf("a.zip"))
        assertEquals(StorageKind.DOCUMENTS, StorageMath.kindOf("a.pdf"))
        assertNull(StorageMath.kindOf("a.bin"))
        assertNull(StorageMath.kindOf("noext"))
    }

    @Test fun bucketSumsOther() {
        val (m, other) = StorageMath.bucket(listOf(f("a.mp4", 10), f("b.jpg", 5), f("c.db", 7), f("d.dat", 3), f("zero.mp4", 0)))
        assertEquals(1, m.getValue(StorageKind.VIDEOS).size)
        assertEquals(1, m.getValue(StorageKind.IMAGES).size)
        assertEquals(10L, other)
    }

    @Test fun systemIsUsedMinusEverythingElseNeverNegative() {
        val b = StorageMath.breakdown(
            total = 1000, free = 400, kindBytes = mapOf(StorageKind.VIDEOS to 200L, StorageKind.IMAGES to 100L),
            otherBytes = 50, appsBytes = 120, binBytes = 30,
        )
        assertEquals(600L, b.used)
        assertEquals(100L, b.sizes[StorageKind.SYSTEM])
        assertEquals(50L, b.sizes[StorageKind.OTHER])
        assertEquals(120L, b.sizes[StorageKind.APPS])
        // Apps unknown: shown as unknown, and not subtracted.
        val u = StorageMath.breakdown(1000, 400, mapOf(StorageKind.VIDEOS to 200L), 0, null, 0)
        assertNull(u.sizes[StorageKind.APPS])
        assertEquals(400L, u.sizes[StorageKind.SYSTEM])
        assertEquals(0L, StorageMath.systemBytes(100, 500, 10, 10))
    }

    @Test fun sdCardHasNoSystemOrApps() {
        val b = StorageMath.breakdown(1000, 500, mapOf(StorageKind.VIDEOS to 300L), 50, 99, 9, isPrimary = false)
        assertEquals(200L, b.sizes[StorageKind.OTHER]) // 50 indexed + 150 unaccounted
        assertTrue(StorageKind.SYSTEM !in b.sizes && StorageKind.APPS !in b.sizes && StorageKind.BIN !in b.sizes)
    }

    @Test fun largeFilesThresholdAndGroups() {
        val files = listOf(f("v.mp4", 30 * mb), f("s.mp3", 24 * mb), f("big.zip", 100 * mb), f("p.png", 25 * mb))
        val large = StorageMath.largeFiles(files)
        assertEquals(listOf("big.zip", "v.mp4", "p.png"), large.map { it.name })
        assertEquals(listOf("big.zip"), StorageMath.filterLarge(large, StorageMath.LargeGroup.OTHER).map { it.name })
        assertEquals(listOf("v.mp4"), StorageMath.filterLarge(large, StorageMath.LargeGroup.VIDEOS).map { it.name })
    }

    @Test fun suggestionRules() {
        val files = listOf(
            f("Download/old.pdf", 5 * mb, ageDays = 91),
            f("Download/new.pdf", 5 * mb, ageDays = 89),
            f("DCIM/Screenshots/s1.png", 2 * mb, ageDays = 31),
            f("Pictures/Screenshots/s2.png", 2 * mb, ageDays = 3),
            f("Download/app.apk", 40 * mb, ageDays = 2),
            f("Movies/old.mp4", 60 * mb, ageDays = 200),
            f("Movies/oldsmall.mp4", 10 * mb, ageDays = 400),
            f("Movies/newbig.mp4", 60 * mb, ageDays = 10),
            f("Music/old.mp3", 4 * mb, ageDays = 366),
            f("Music/new.mp3", 4 * mb, ageDays = 100),
        )
        val s = StorageMath.suggestions(files, root, now).associateBy { it.type }
        assertEquals(listOf("old.pdf"), s.getValue(SuggestionType.OLD_DOWNLOADS).files.map { it.name })
        assertEquals(listOf("s1.png"), s.getValue(SuggestionType.OLD_SCREENSHOTS).files.map { it.name })
        assertEquals(listOf("app.apk"), s.getValue(SuggestionType.APKS).files.map { it.name })
        assertEquals(listOf("old.mp4"), s.getValue(SuggestionType.OLD_LARGE_VIDEOS).files.map { it.name })
        assertEquals(listOf("old.mp3"), s.getValue(SuggestionType.OLD_AUDIO).files.map { it.name })
        // Empty cards are left out.
        assertTrue(StorageMath.suggestions(listOf(f("Music/new.mp3", 1, 1)), root, now).isEmpty())
        // Honest wording: never "haven't opened".
        s.values.forEach { assertTrue(!StorageMath.suggestionText(it, "1 MB").contains("opened")) }
    }

    @Test fun duplicatesSameSizeThenHash() {
        val a = f("a/x.jpg", 10_000, 5); val b = f("b/x.jpg", 10_000, 3); val c = f("c/y.jpg", 10_000, 1)
        val d = f("d/z.jpg", 20_000); val tiny1 = f("t1", 100); val tiny2 = f("t2", 100)
        val hashes = mapOf(a.path to "H1", b.path to "H1", c.path to "H2", d.path to "H3", tiny1.path to "T", tiny2.path to "T")
        val sets = StorageMath.groupDuplicates(listOf(a, b, c, d, tiny1, tiny2), hash = { hashes[it.path] })
        assertEquals(1, sets.size)
        assertEquals(listOf(a.path, b.path), sets[0].files.map { it.path }) // oldest first
        assertEquals(10_000L, sets[0].reclaimable)
        assertEquals(listOf(b.path), StorageMath.allButOne(sets))
    }

    @Test fun duplicatesAreBounded() {
        val many = (0 until 50).map { f("d/$it.bin", 8_192) }
        val sets = StorageMath.groupDuplicates(many, hash = { "same" }, maxHashed = 10)
        assertTrue(sets.isEmpty()) // the one size group (50) is bigger than the bound: skipped, not partially read
        assertEquals(50, StorageMath.sameSizeCandidates(many).size)
        assertTrue(StorageMath.sameSizeCandidates(listOf(f("only.bin", 9_000))).isEmpty())
    }

    @Test fun allButOneKeepsOnePerSet() {
        val s1 = DuplicateSet(listOf(f("1", 5000), f("2", 5000), f("3", 5000)))
        val s2 = DuplicateSet(listOf(f("4", 6000), f("5", 6000)))
        assertEquals(3, StorageMath.allButOne(listOf(s1, s2)).size)
        assertEquals(16_000L, s1.reclaimable + s2.reclaimable)
    }

    @Test fun quickHashMatchesSameContentOnly() {
        val x = tmp.newFile("x.bin").apply { writeBytes(ByteArray(200_000) { (it % 251).toByte() }) }
        val y = tmp.newFile("y.bin").apply { writeBytes(ByteArray(200_000) { (it % 251).toByte() }) }
        val z = tmp.newFile("z.bin").apply { writeBytes(ByteArray(200_000) { (it % 251).toByte() }.also { it[199_999] = 7 }) }
        val small = tmp.newFile("s.bin").apply { writeBytes(ByteArray(10) { 1 }) }
        assertEquals(StorageMath.quickHash(x), StorageMath.quickHash(y))
        assertTrue(StorageMath.quickHash(x) != StorageMath.quickHash(z))
        assertTrue(StorageMath.quickHash(small) != null)
        assertNull(StorageMath.quickHash(java.io.File(tmp.root, "missing")))
    }

    @Test fun reportAndPrune() {
        val vol = StorageVolume(root, "Internal storage", 1000 * mb, 400 * mb)
        val files = listOf(f("Movies/a.mp4", 30 * mb), f("Download/b.apk", 2 * mb), f("x.dat", 1 * mb))
        val r = StorageMath.report(vol, true, files, appsBytes = null, binBytes = 0, now = now)
        assertEquals(30 * mb, r.breakdown.sizes[StorageKind.VIDEOS])
        assertEquals(1 * mb, r.otherBytes)
        assertEquals(1, r.large.size)
        val gone = files[0].path
        val p = r.pruned { l -> l.filterNot { it.path == gone } }
        assertEquals(0L, p.breakdown.sizes[StorageKind.VIDEOS])
        assertTrue(p.large.isEmpty())
        assertEquals("/storage/1A", StorageMath.volumeOf("/storage/1A/x", listOf(root, "/storage/1A")))
        assertNull(StorageMath.volumeOf("/storage/1AB/x", listOf(root, "/storage/1A")))
    }

    @Test fun titles() {
        assertEquals("132 videos (6.6 GB)", StorageMath.listTitle(StorageKind.VIDEOS, 132, "6.6 GB"))
        assertEquals("1 image (2 MB)", StorageMath.listTitle(StorageKind.IMAGES, 1, "2 MB"))
        assertEquals("14 items (12.6 MB)", StorageMath.itemsTitle(14, "12.6 MB"))
    }
}
