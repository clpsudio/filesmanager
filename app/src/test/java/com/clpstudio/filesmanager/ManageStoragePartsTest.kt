package com.clpstudio.filesmanager

import android.app.Application
import android.database.MatrixCursor
import com.clpstudio.filesmanager.data.AppsSize
import com.clpstudio.filesmanager.data.CursorRows
import com.clpstudio.filesmanager.data.FileItem
import com.clpstudio.filesmanager.data.Loadable
import com.clpstudio.filesmanager.data.ManageStorageData
import com.clpstudio.filesmanager.data.StorageCache
import com.clpstudio.filesmanager.data.StorageCombine
import com.clpstudio.filesmanager.data.StorageEngine
import com.clpstudio.filesmanager.data.StorageKind
import com.clpstudio.filesmanager.data.StorageMath
import com.clpstudio.filesmanager.data.StoragePart
import com.clpstudio.filesmanager.data.StorageSnapshot
import com.clpstudio.filesmanager.data.StorageSource
import com.clpstudio.filesmanager.data.StorageVolume
import com.clpstudio.filesmanager.data.VolumeSnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
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
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** "Manage storage taking long time to load": parts load in parallel, fill in on their own, and the last numbers are cached. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ManageStoragePartsTest {
    @get:Rule val tmp = TemporaryFolder()

    private val root = "/storage/emulated/0"
    private val sdRoot = "/storage/1A2B-3C4D"
    private val gb = 1L shl 30
    private val mb = 1L shl 20
    private val now = 1_793_450_000_000L
    private val internal = StorageVolume(root, "Internal storage", 128 * gb, 40 * gb)
    private val sd = StorageVolume(sdRoot, "SD card", 64 * gb, 50 * gb, removable = true)
    private val scopes = ArrayList<CoroutineScope>()

    @After fun tearDown() { scopes.forEach { it.cancel() } }

    private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes += it }

    private fun f(path: String, size: Long, base: String = root, ageDays: Long = 3) =
        FileItem("$base/$path", path.substringAfterLast('/'), false, size, now - ageDays * StorageMath.DAY)

    private fun files(): List<FileItem> = buildList {
        repeat(10) { add(f("DCIM/IMG_$it.jpg", 3 * mb + it)) }
        repeat(4) { add(f("Movies/clip$it.mp4", 80 * mb + it)) }
        repeat(3) { add(f("Download/copy$it/same.pdf", 2 * mb)) } // same size: duplicate candidates
        add(f("Download/big.zip", 300 * mb))
        repeat(5) { add(f("Music/s$it.mp3", 5 * mb + it, sdRoot)) }
    }

    /** A fake device: each part waits for its own gate (or a delay) so tests control the order. */
    private open inner class Fake(
        val filesGate: CompletableDeferred<Unit>? = null,
        val appsGate: CompletableDeferred<Unit>? = null,
        val binGate: CompletableDeferred<Unit>? = null,
        val unusedGate: CompletableDeferred<Unit>? = null,
        val delayMs: Long = 0,
        val hashDelayMs: Long = 0,
        var access: Boolean = true,
        val list: List<FileItem> = files(),
    ) : StorageSource {
        val hashes = AtomicInteger()
        val scans = AtomicInteger()
        override fun volumes() = listOf(internal, sd)
        override suspend fun scanFiles(volumes: List<StorageVolume>): List<FileItem> { scans.incrementAndGet(); filesGate?.await(); delay(delayMs); return list }
        override suspend fun binBytes(): Long? { binGate?.await(); delay(delayMs); return 100 * mb }
        override fun hasUsageAccess() = access
        override suspend fun appsSize(): AppsSize? { appsGate?.await(); delay(delayMs); return AppsSize(total = 10 * gb, own = 500 * mb) }
        override suspend fun unusedApps(): Int? { unusedGate?.await(); delay(delayMs); return 7 }
        override fun hash(file: FileItem): String? { hashes.incrementAndGet(); if (hashDelayMs > 0) Thread.sleep(hashDelayMs); return file.name }
        override fun now() = now
    }

    private fun <T> await(ms: Long = 5_000, block: suspend () -> T): T = runBlocking { withTimeout(ms) { block() } }

    private fun StorageEngine.waitFor(pred: (Loadable<ManageStorageData>) -> Boolean) = await { state.first(pred) }

    @Test fun volumeBreakdownDoesNotWaitForAppSizesOrUnusedApps() {
        val appsGate = CompletableDeferred<Unit>()
        val unusedGate = CompletableDeferred<Unit>()
        val e = StorageEngine(scope(), Fake(appsGate = appsGate, unusedGate = unusedGate))
        e.ensureLoaded()
        val partial = e.waitFor { it.loaded && it.value?.pending?.contains(StoragePart.BIN) == false }.value!!
        assertTrue(partial.volumes.isNotEmpty())
        assertEquals(setOf(StoragePart.APPS, StoragePart.UNUSED), partial.pending)
        val primary = partial.volumes.first()
        assertEquals((10 * (3 * mb) + 45), primary.breakdown.sizes[StorageKind.IMAGES])
        assertNull(primary.appsBytes) // still loading, shown as "…"
        assertEquals(100 * mb, partial.binBytes)
        assertNull(partial.unusedApps)

        appsGate.complete(Unit)
        val withApps = e.waitFor { it.value?.pending?.contains(StoragePart.APPS) == false }.value!!
        assertEquals(10 * gb - 100 * mb, withApps.volumes.first().appsBytes) // our own Bin is taken out of Apps
        assertEquals(setOf(StoragePart.UNUSED), withApps.pending)

        unusedGate.complete(Unit)
        val all = e.waitFor { it.value?.pending?.isEmpty() == true && !it.loading }.value!!
        assertEquals(7, all.unusedApps)
        assertTrue(all.usageAccess)
    }

    @Test fun binAndUnusedFillInBeforeTheFileScan() {
        val filesGate = CompletableDeferred<Unit>()
        val e = StorageEngine(scope(), Fake(filesGate = filesGate))
        e.ensureLoaded()
        val early = e.waitFor { it.value?.unusedApps != null && it.value?.binBytes != null }
        assertFalse(early.loaded)
        assertEquals(setOf(StoragePart.FILES), early.value!!.pending)
        assertTrue(early.value!!.volumes.isEmpty())
        filesGate.complete(Unit)
        e.waitFor { it.loaded && it.value?.pending?.isEmpty() == true }
    }

    @Test fun partsRunInParallel() {
        val e = StorageEngine(scope(), Fake(delayMs = 400))
        val t0 = System.nanoTime()
        e.ensureLoaded()
        e.waitFor { it.loaded && !it.loading && it.value?.pending?.isEmpty() == true }
        val ms = (System.nanoTime() - t0) / 1_000_000
        // Four parts of 400 ms each: one after the other would be 1.6 s.
        assertTrue("took $ms ms", ms < 1_200)
    }

    @Test fun noUsageAccessShowsDashNotDots() {
        val e = StorageEngine(scope(), Fake(access = false))
        e.ensureLoaded()
        val d = e.waitFor { it.loaded && !it.loading && it.value?.pending?.isEmpty() == true }.value!!
        assertFalse(d.usageAccess)
        assertNull(d.unusedApps)
        assertNull(d.volumes.first().appsBytes)
    }

    @Test fun lastNumbersAreSavedAndShownAtOnceAfterARestart() {
        val file = File(tmp.root, "manage_storage_cache.json")
        val first = StorageEngine(scope(), Fake(), StorageCache(file))
        first.ensureLoaded()
        val fresh = first.waitFor { it.loaded && !it.loading && it.value?.pending?.isEmpty() == true }.value!!
        await { while (!file.isFile) delay(10) }

        // "App restart": a new engine whose scan never finishes still shows the saved numbers at once.
        val never = CompletableDeferred<Unit>()
        val src = Fake(filesGate = never, appsGate = never, binGate = never, unusedGate = never)
        val second = StorageEngine(scope(), src, StorageCache(file))
        val t0 = System.nanoTime()
        second.ensureLoaded()
        val cached = second.waitFor { it.value != null && it.loading }
        val ms = (System.nanoTime() - t0) / 1_000_000
        val d = cached.value!!
        assertTrue("cached numbers took $ms ms", ms < 500)
        assertFalse(cached.loaded) // the refresh is still running in the background
        assertTrue(cached.loading)
        assertTrue(d.pending.isEmpty())
        assertEquals(now, d.savedAt)
        assertEquals(2, d.volumes.size)
        assertTrue(d.volumes.all { it.fromCache })
        assertEquals(fresh.volumes.first().breakdown.sizes, d.volumes.first().breakdown.sizes)
        assertEquals(fresh.volumes.first().largeSize, d.volumes.first().largeSize)
        assertEquals(7, d.unusedApps)
        assertEquals(100 * mb, d.binBytes)
        assertEquals(1, src.scans.get())
    }

    @Test fun cacheRoundTripAndCorruptFile() {
        val file = File(tmp.root, "c.json")
        val c = StorageCache(file)
        assertNull(c.read())
        val s = StorageSnapshot(
            savedAt = 5, usageAccess = false, unused = null, bin = null, apps = null,
            volumes = listOf(VolumeSnapshot(root, mapOf(StorageKind.IMAGES to 10L, StorageKind.VIDEOS to 0L), 3, 4)),
            duplicateBytes = mapOf(root to 99L),
        )
        c.write(s)
        assertEquals(s, c.read())
        val s2 = s.copy(usageAccess = true, unused = 3, bin = 7, apps = AppsSize(100, 10))
        c.write(s2)
        assertEquals(s2, c.read())
        file.writeText("{not json")
        assertNull(c.read())
    }

    @Test fun combineWithNothingKnownIsNull() {
        val l = StorageCombine.combine(listOf(internal), Loadable(), Loadable(), Loadable(), Loadable(), null, true)
        assertNull(l.value)
        assertFalse(l.loaded)
    }

    @Test fun duplicatesWaitForTheScreenThenRememberHashes() {
        val src = Fake()
        val e = StorageEngine(scope(), src)
        e.ensureLoaded()
        e.waitFor { it.loaded && !it.loading }
        Thread.sleep(150)
        assertNull(e.duplicates.value.value) // nobody is looking: no hashing
        assertEquals(0, src.hashes.get())

        e.watch()
        val sets = await { e.duplicates.first { it.loaded } }.value!!
        assertEquals(1, sets[root]!!.size)
        assertEquals(3, sets[root]!!.first().files.size)
        val firstReads = src.hashes.get()
        assertTrue(firstReads >= 3)

        // A refresh with the same files reads nothing again.
        e.refresh()
        await { while (e.state.value.loading || e.duplicates.value.loading || src.scans.get() < 2) delay(10) }
        await { while (e.duplicates.value.loading) delay(10) }
        Thread.sleep(200)
        assertEquals(firstReads, src.hashes.get())
        assertEquals(0, e.lastHashReads)
        e.unwatch()
    }

    @Test fun leavingTheScreenStopsTheDuplicatePassAndTheNextOpenFinishesIt() {
        val many = files() + List(30) { f("Same/f$it.bin", 8 * mb) }
        val src = Fake(list = many, hashDelayMs = 40)
        val e = StorageEngine(scope(), src)
        e.ensureLoaded()
        e.waitFor { it.loaded && !it.loading }
        e.watch()
        await { while (src.hashes.get() < 2) delay(5) }
        e.unwatch()
        Thread.sleep(300)
        val stoppedAt = src.hashes.get()
        assertTrue("stopped at $stoppedAt", stoppedAt < 33)
        assertNull(e.duplicates.value.value)
        assertFalse(e.duplicates.value.loading)
        Thread.sleep(200)
        assertEquals(stoppedAt, src.hashes.get())

        e.watch()
        val sets = await(10_000) { e.duplicates.first { it.loaded } }.value!!
        assertNotNull(sets[root])
        e.unwatch()
    }

    @Test fun duplicatesScreenAsksForThemWithoutAWatcher() {
        val e = StorageEngine(scope(), Fake())
        e.ensureLoaded()
        e.waitFor { it.loaded && !it.loading }
        e.requestDuplicates()
        val sets = await { e.duplicates.first { it.loaded } }.value!!
        assertEquals(1, sets[root]!!.size)
    }

    @Test fun pruneDropsDeletedFilesFromTheParts() {
        val e = StorageEngine(scope(), Fake())
        e.ensureLoaded()
        e.waitFor { it.loaded && !it.loading }
        e.prune { l -> l.filterNot { it.name == "big.zip" } }
        val d = e.waitFor { st -> st.value?.volumes?.first()?.byKind?.get(StorageKind.ARCHIVES).isNullOrEmpty() }.value!!
        assertEquals(0L, d.volumes.first().breakdown.sizes[StorageKind.ARCHIVES])
    }

    // ---------------------------------------------------------------------------------------
    // Timing harness: the old serial report vs. the new parts, with device-like latencies for the
    // system calls (modelled, not measured on a phone) and the real CPU work on 50,000 rows.
    // ---------------------------------------------------------------------------------------
    @Test fun timingHarness50kFiles() {
        val n = 50_000
        val exts = listOf("jpg", "mp4", "mp3", "pdf", "apk", "zip", "bin", "png", "docx", "txt")
        val big = List(n) { i ->
            val base = if (i % 10 == 0) sdRoot else root
            FileItem("$base/dir${i % 400}/file_$i.${exts[i % exts.size]}", "file_$i.${exts[i % exts.size]}", false, 4_096L + (i % 9_000) * 1_000L, now - (i % 700) * StorageMath.DAY)
        }
        val lines = StringBuilder()

        // Real: MediaStore-like cursor of 50k rows read into FileItems.
        val cursor = MatrixCursor(arrayOf("_data", "_display_name", "_size", "date_modified"))
        big.forEach { cursor.addRow(arrayOf<Any>(it.path, it.name, it.size, it.modified / 1000)) }
        var t = System.nanoTime()
        val out = LinkedHashMap<String, FileItem>()
        CursorRows.readFiles(cursor, out)
        val readMs = (System.nanoTime() - t) / 1_000_000
        // Real: per-volume report (kinds, large files, suggestions, same-size candidates).
        val roots = listOf(root, sdRoot)
        t = System.nanoTime()
        val per = out.values.toList().groupBy { StorageMath.volumeOf(it.path, roots) }
        StorageMath.report(internal, true, per[root].orEmpty(), null, null, now)
        StorageMath.report(sd, false, per[sdRoot].orEmpty(), null, null, now)
        val reportMs = (System.nanoTime() - t) / 1_000_000
        lines.appendLine("JVM real: read 50k cursor rows ${readMs} ms; reports for 2 volumes ${reportMs} ms")

        // Modelled phone latencies (per call): MediaStore query 50k rows ~600 ms; queryStatsForPackage
        // ~10 ms x 300 packages; queryStatsForUser ~60 ms; unused apps (labels + package info) ~900 ms; Bin 30 ms.
        val scanMs = 600L; val perPkgMs = 10L; val pkgs = 300; val userStatsMs = 60L; val unusedMs = 900L; val binMs = 30L
        val oldSerial = scanMs + readMs + binMs + perPkgMs * pkgs + unusedMs + reportMs
        lines.appendLine("BEFORE (serial, nothing shown until all done): first numbers = full report = ~${oldSerial} ms (scan $scanMs + Bin $binMs + apps ${perPkgMs * pkgs} + unused $unusedMs + CPU ${readMs + reportMs})")

        val src = object : StorageSource {
            override fun volumes() = listOf(internal, sd)
            override suspend fun scanFiles(volumes: List<StorageVolume>): List<FileItem> { delay(scanMs); return big }
            override suspend fun binBytes(): Long? { delay(binMs); return 1L }
            override fun hasUsageAccess() = true
            override suspend fun appsSize(): AppsSize? { delay(userStatsMs); return AppsSize(gb, mb) }
            override suspend fun unusedApps(): Int? { delay(unusedMs); return 3 }
            override fun hash(file: FileItem): String? = null
            override fun now() = now
        }
        val file = File(tmp.root, "cache.json")
        val e = StorageEngine(scope(), src, StorageCache(file))
        t = System.nanoTime()
        e.ensureLoaded()
        e.waitFor { it.loaded }
        val filesMs = (System.nanoTime() - t) / 1_000_000
        e.waitFor { it.loaded && !it.loading && it.value?.pending?.isEmpty() == true }
        val allMs = (System.nanoTime() - t) / 1_000_000
        lines.appendLine("AFTER (parts in parallel): screen frame at once with \"…\"; Bin ~${binMs} ms, app sizes ~${userStatsMs} ms; volume breakdown ${filesMs} ms; everything ${allMs} ms")
        await { while (!file.isFile) delay(10) }

        val e2 = StorageEngine(scope(), src, StorageCache(file))
        t = System.nanoTime()
        e2.ensureLoaded()
        e2.waitFor { it.value?.volumes?.isNotEmpty() == true }
        val cachedMs = (System.nanoTime() - t) / 1_000_000
        lines.appendLine("AFTER, reopen / app restart: last numbers shown in ${cachedMs} ms (cache ${file.length()} bytes), refresh runs behind")
        println(lines)
        File("build").mkdirs()
        File("build/storage-timings.txt").writeText(lines.toString())
        assertTrue(filesMs < oldSerial)
        assertTrue(cachedMs < 300)
    }
}
