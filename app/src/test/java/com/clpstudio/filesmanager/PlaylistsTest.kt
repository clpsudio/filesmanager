package com.clpstudio.filesmanager

import android.app.Application
import com.clpstudio.filesmanager.data.FileItem
import com.clpstudio.filesmanager.data.FileKind
import com.clpstudio.filesmanager.data.Playlist
import com.clpstudio.filesmanager.data.PlaylistRules
import com.clpstudio.filesmanager.data.PlaylistStore
import com.clpstudio.filesmanager.data.PlaylistType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.random.Random

/** Saved playlists: CRUD, names, kinds, missing files, order, shuffle, the JSON file. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PlaylistsTest {
    private val dir = File("build/playlist-test").apply { deleteRecursively(); mkdirs() }
    private lateinit var file: File
    private val vault = "/data/user/0/com.clpstudio.filesmanager/no_backup/vault"
    private val bin = "/storage/emulated/0/Android/data/com.clpstudio.filesmanager/files/.bin"

    private fun store() = PlaylistStore(file, privateRoots = { listOf(vault, bin) }, sync = true)
    private fun f(name: String) = FileItem("/storage/emulated/0/Music/$name", name, false, 100, 0)

    @Before fun setUp() { file = File(dir, "p${System.nanoTime()}.json") }

    @Test fun createRenameDeleteAndPersist() {
        val s = store()
        val p = s.create(PlaylistType.AUDIO, "  Road   trip ").getOrThrow()
        assertEquals("Road trip", p.name)
        assertEquals(PlaylistType.AUDIO, p.type)
        assertEquals("Long drive", s.rename(p.id, "Long drive").getOrThrow().name)
        // Unlimited: many playlists of one kind.
        repeat(120) { s.create(PlaylistType.AUDIO, "List $it").getOrThrow() }
        assertEquals(121, s.ofType(PlaylistType.AUDIO).size)
        // Reload from disk.
        val again = store()
        assertEquals(121, again.all().size)
        assertEquals("Long drive", again.get(p.id)!!.name)
        again.delete(p.id)
        assertNull(store().get(p.id))
    }

    @Test fun namesUniquePerTypeIgnoringCase() {
        val s = store()
        s.create(PlaylistType.VIDEO, "Favourites").getOrThrow()
        assertTrue(s.create(PlaylistType.VIDEO, "favourites").isFailure)
        assertTrue(s.create(PlaylistType.VIDEO, "   ").isFailure)
        // The same name in another kind is fine.
        assertTrue(s.create(PlaylistType.AUDIO, "Favourites").isSuccess)
        assertTrue(s.create(PlaylistType.GALLERY, "Favourites").isSuccess)
        val other = s.create(PlaylistType.VIDEO, "Other").getOrThrow()
        assertTrue(s.rename(other.id, "FAVOURITES").isFailure)
        assertTrue(s.rename(other.id, "other").isSuccess) // renaming to its own name in other case
        assertEquals("Enter a name", PlaylistRules.nameError("", PlaylistType.PHOTO, emptyList()))
    }

    @Test fun addToPlaylistOffersOnlyMatchingKinds() {
        val song = f("a.mp3"); val video = f("b.mp4"); val photo = f("c.jpg"); val doc = f("d.pdf")
        val dirItem = FileItem("/storage/emulated/0/Music", "Music", true, 0, 0)
        assertEquals(FileKind.AUDIO, song.kind)
        assertEquals(setOf(PlaylistType.AUDIO), PlaylistRules.typesFor(listOf(song)))
        assertEquals(setOf(PlaylistType.VIDEO, PlaylistType.GALLERY), PlaylistRules.typesFor(listOf(video)))
        assertEquals(setOf(PlaylistType.PHOTO, PlaylistType.GALLERY), PlaylistRules.typesFor(listOf(photo)))
        assertEquals(setOf(PlaylistType.GALLERY), PlaylistRules.typesFor(listOf(photo, video)))
        assertTrue(PlaylistRules.typesFor(listOf(song, photo)).isEmpty())
        assertTrue(PlaylistRules.typesFor(listOf(doc)).isEmpty())
        assertTrue(PlaylistRules.typesFor(listOf(song, dirItem)).isEmpty())
        assertTrue(PlaylistRules.typesFor(emptyList()).isEmpty())
        val all = listOf(
            Playlist("1", PlaylistType.AUDIO, "A"), Playlist("2", PlaylistType.VIDEO, "V"),
            Playlist("3", PlaylistType.PHOTO, "P"), Playlist("4", PlaylistType.GALLERY, "G"),
        )
        assertEquals(listOf("2", "4"), PlaylistRules.matching(listOf(video), all).map { it.id })
        assertEquals(listOf("4"), PlaylistRules.matching(listOf(video, photo), all).map { it.id })
        assertEquals(listOf("1"), PlaylistRules.matching(listOf(song), all).map { it.id })
    }

    @Test fun addSkipsWrongKindsDuplicatesAndVault() {
        val s = store()
        val g = s.create(PlaylistType.GALLERY, "Trip").getOrThrow()
        val n = s.add(g.id, listOf(f("1.jpg"), f("2.mp4"), f("3.mp3"), f("1.jpg")))
        assertEquals(2, n)
        assertEquals(0, s.add(g.id, listOf(f("1.jpg"))))
        assertEquals(0, s.addPaths(g.id, listOf("$vault/files/x.jpg", "$bin/y.jpg")))
        assertEquals(listOf(f("1.jpg").path, f("2.mp4").path), s.get(g.id)!!.items)
        assertFalse(PlaylistRules.eligible("$vault/a.jpg", listOf(vault)))
        assertTrue(PlaylistRules.eligible("/storage/1234-ABCD/DCIM/a.jpg", listOf(vault))) // SD card files are fine
        // A vault file can't be a cover either.
        s.setCover(g.id, "$vault/c.jpg")
        assertNull(s.get(g.id)!!.cover)
        s.setCover(g.id, "/storage/emulated/0/DCIM/cover.jpg")
        assertEquals("/storage/emulated/0/DCIM/cover.jpg", s.get(g.id)!!.cover)
    }

    @Test fun missingFilesAreSplitOffAndRemovable() {
        val real = File(dir, "here.mp3").apply { writeText("x") }
        val gone = File(dir, "gone.mp3")
        val s = store()
        val p = s.create(PlaylistType.AUDIO, "Mix").getOrThrow()
        s.addPaths(p.id, listOf(real.absolutePath, gone.absolutePath))
        val (here, missing) = PlaylistRules.split(s.get(p.id)!!.items) { File(it).exists() }
        assertEquals(listOf(real.absolutePath), here)
        assertEquals(listOf(gone.absolutePath), missing)
        s.remove(p.id, missing)
        assertEquals(listOf(real.absolutePath), s.get(p.id)!!.items)
    }

    @Test fun reorderKeepsEverythingAndMissingGoesLast() {
        assertEquals(listOf("b", "c", "a", "d"), PlaylistRules.move(listOf("a", "b", "c", "d"), 0, 2))
        assertEquals(listOf("d", "a", "b", "c"), PlaylistRules.move(listOf("a", "b", "c", "d"), 3, 0))
        assertEquals(listOf("a", "b"), PlaylistRules.move(listOf("a", "b"), 5, 0))
        val s = store()
        val p = s.create(PlaylistType.PHOTO, "Album").getOrThrow()
        s.add(p.id, listOf(f("1.jpg"), f("2.jpg"), f("3.jpg"), f("4.jpg")))
        s.move(p.id, 0, 3)
        assertEquals(listOf("2", "3", "4", "1"), s.get(p.id)!!.items.map { it.substringAfterLast('/').substringBefore('.') })
        // The screen saves the order of the files it shows; a missing one (3) stays, after them.
        s.reorder(p.id, listOf(f("1.jpg").path, f("4.jpg").path, f("2.jpg").path))
        assertEquals(listOf("1", "4", "2", "3"), s.get(p.id)!!.items.map { it.substringAfterLast('/').substringBefore('.') })
        assertEquals(4, store().get(p.id)!!.items.size)
    }

    @Test fun shuffleKeepsAllItemsOnce() {
        val list = (1..50).map { "song$it.mp3" }
        repeat(20) { seed ->
            val sh = PlaylistRules.shuffled(list, Random(seed))
            assertEquals(list.size, sh.size)
            assertEquals(list.toSet(), sh.toSet())
        }
        assertTrue((0 until 5).any { PlaylistRules.shuffled(list, Random(it)) != list })
        assertEquals(listOf("c", "d", "a", "b"), PlaylistRules.from(listOf("a", "b", "c", "d"), 2))
        assertEquals(listOf("a", "b"), PlaylistRules.from(listOf("a", "b"), 0))
    }

    @Test fun jsonRoundTripAndBadFile() {
        val list = listOf(Playlist("x", PlaylistType.GALLERY, "Goa \"2026\"", listOf("/a.jpg", "content://media/1"), "/c.jpg", 1, 2))
        assertEquals(list, PlaylistStore.decode(PlaylistStore.encode(list)))
        file.writeText("not json")
        assertTrue(store().all().isEmpty())
        file.writeText("""{"playlists":[{"id":"1","type":"NOPE","name":"x"},{"id":"2","type":"AUDIO","name":"ok"}]}""")
        assertEquals(listOf("ok"), store().all().map { it.name })
        assertNotNull(store().get("2"))
    }

    @Test fun renameOrMoveInsideTheAppKeepsTheEntry() {
        val s = store()
        val p = s.create(PlaylistType.AUDIO, "Mix", items = listOf("/storage/emulated/0/Music/a.mp3", "/storage/emulated/0/Music/Old/b.mp3")).getOrThrow()
        s.pathChanged("/storage/emulated/0/Music/a.mp3", "/storage/emulated/0/Music/A song.mp3")
        s.pathChanged("/storage/emulated/0/Music/Old", "/storage/1234-5678/Music/New") // a folder moved to the SD card
        assertEquals(listOf("/storage/emulated/0/Music/A song.mp3", "/storage/1234-5678/Music/New/b.mp3"), store().get(p.id)!!.items)
    }
}
