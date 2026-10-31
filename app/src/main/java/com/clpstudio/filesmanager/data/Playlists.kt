package com.clpstudio.filesmanager.data

import android.content.Context
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlin.random.Random

/** The four kinds of saved playlist; each kind has its own list of playlists. */
enum class PlaylistType(val title: String) {
    AUDIO("Audio"), VIDEO("Video"), PHOTO("Photo"), GALLERY("Gallery");

    /** "Audio playlists", "Gallery playlists"... */
    val listTitle: String get() = "$title playlists"

    /** Which files a playlist of this kind holds (Gallery = photos and videos mixed). */
    fun accepts(kind: FileKind): Boolean = when (this) {
        AUDIO -> kind == FileKind.AUDIO
        VIDEO -> kind == FileKind.VIDEO
        PHOTO -> kind == FileKind.IMAGE
        GALLERY -> kind == FileKind.IMAGE || kind == FileKind.VIDEO
    }

    /** The category the in-app "Add items" picker lists. */
    val category: Category get() = when (this) {
        AUDIO -> Category.AUDIO
        VIDEO -> Category.VIDEOS
        PHOTO -> Category.PHOTOS
        GALLERY -> Category.MEDIA
    }
}

/** A saved, named playlist. [items] are file paths (or content URIs) in play order. */
@Immutable
data class Playlist(
    val id: String,
    val type: PlaylistType,
    val name: String,
    val items: List<String> = emptyList(),
    /** A photo picked as the cover; null = the first item's thumbnail. */
    val cover: String? = null,
    val created: Long = 0,
    val modified: Long = 0,
)

/** Pure rules (unit tested). */
object PlaylistRules {
    const val MAX_NAME = 60

    fun cleanName(name: String): String = name.trim().replace(Regex("\\s+"), " ")

    /** Null when the name is fine, else the message to show. Names are unique per type, ignoring case. */
    fun nameError(name: String, type: PlaylistType, existing: List<Playlist>, exceptId: String? = null): String? {
        val n = cleanName(name)
        if (n.isEmpty()) return "Enter a name"
        if (n.length > MAX_NAME) return "Name is too long"
        if (existing.any { it.type == type && it.id != exceptId && it.name.equals(n, ignoreCase = true) }) {
            return "A ${type.title.lowercase()} playlist with this name already exists"
        }
        return null
    }

    /**
     * The playlist kinds a selection can go into: every selected file must fit the kind. Folders or other
     * files (documents, APKs...) in the selection = none (no "Add to playlist" in the menu).
     */
    fun typesFor(items: List<FileItem>): Set<PlaylistType> {
        if (items.isEmpty() || items.any { it.isDir }) return emptySet()
        return PlaylistType.entries.filter { t -> items.all { t.accepts(it.kind) } }.toSet()
    }

    /** "Add to playlist" offers only playlists whose kind takes every selected file. */
    fun matching(items: List<FileItem>, all: List<Playlist>): List<Playlist> {
        val types = typesFor(items)
        return all.filter { it.type in types }
    }

    /** Vault, Bin and other app-private files never go into playlists. */
    fun eligible(path: String, privateRoots: List<String>): Boolean {
        if (path.isBlank()) return false
        if (path.startsWith("content://")) return true
        if (privateRoots.any { path == it || path.startsWith("$it/") }) return false
        if (path.startsWith("/data/")) return false
        return true
    }

    /** Items whose file still exists (in order) and the ones that are gone (deleted / moved). */
    fun split(items: List<String>, exists: (String) -> Boolean): Pair<List<String>, List<String>> = items.partition(exists)

    /** Moves one item [from] -> [to] (both clamped); every other item keeps its order. */
    fun <T> move(list: List<T>, from: Int, to: Int): List<T> {
        if (from !in list.indices) return list
        val t = to.coerceIn(0, list.lastIndex)
        if (t == from) return list
        val m = list.toMutableList()
        val x = m.removeAt(from)
        m.add(t, x)
        return m
    }

    /** Shuffled play order: the same items, each exactly once. */
    fun <T> shuffled(list: List<T>, random: Random = Random.Default): List<T> = list.shuffled(random)

    /** The order for "play from this item": that item first, then the rest, wrapping to the start. */
    fun <T> from(list: List<T>, index: Int): List<T> =
        if (index <= 0 || index >= list.size) list else list.drop(index) + list.take(index)

    fun countLabel(n: Int, word: String = "item"): String = "$n $word" + if (n == 1) "" else "s"
}

/**
 * Saved playlists, kept in one small JSON file in app-private storage (no new libraries).
 * Changes update [playlists] at once and are written in the background ([sync] = write now, for tests).
 */
class PlaylistStore(
    private val file: File,
    private val privateRoots: () -> List<String> = { emptyList() },
    private val sync: Boolean = false,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val lock = Any()
    private val _playlists = MutableStateFlow(load())
    val playlists: StateFlow<List<Playlist>> = _playlists
    private val writer = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    fun all(): List<Playlist> = _playlists.value
    fun ofType(type: PlaylistType): List<Playlist> = all().filter { it.type == type }
    fun get(id: String): Playlist? = all().firstOrNull { it.id == id }

    /** Creates a playlist; Result.failure(message) when the name is empty or taken for this kind. */
    fun create(type: PlaylistType, name: String, cover: String? = null, items: List<String> = emptyList()): Result<Playlist> = synchronized(lock) {
        PlaylistRules.nameError(name, type, all())?.let { return Result.failure(IllegalArgumentException(it)) }
        val now = clock()
        val p = Playlist(UUID.randomUUID().toString(), type, PlaylistRules.cleanName(name), emptyList(), cover?.takeIf { PlaylistRules.eligible(it, privateRoots()) }, now, now)
        commit(all() + p)
        if (items.isNotEmpty()) addPaths(p.id, items)
        Result.success(get(p.id)!!)
    }

    fun rename(id: String, name: String): Result<Playlist> = synchronized(lock) {
        val p = get(id) ?: return Result.failure(IllegalArgumentException("Playlist not found"))
        PlaylistRules.nameError(name, p.type, all(), exceptId = id)?.let { return Result.failure(IllegalArgumentException(it)) }
        Result.success(update(id) { it.copy(name = PlaylistRules.cleanName(name)) }!!)
    }

    fun setCover(id: String, cover: String?) {
        synchronized(lock) { update(id) { it.copy(cover = cover?.takeIf { c -> PlaylistRules.eligible(c, privateRoots()) }) } }
    }

    fun delete(id: String) {
        synchronized(lock) { commit(all().filterNot { it.id == id }) }
    }

    /** Adds files (kinds the playlist doesn't take and duplicates are skipped). Returns how many were added. */
    fun add(id: String, files: List<FileItem>): Int {
        val p = get(id) ?: return 0
        return addPaths(id, files.filter { !it.isDir && p.type.accepts(it.kind) }.map { it.path })
    }

    fun addPaths(id: String, paths: List<String>): Int = synchronized(lock) {
        val p = get(id) ?: return 0
        val roots = privateRoots()
        val have = p.items.toHashSet()
        val fresh = paths.filter { PlaylistRules.eligible(it, roots) && p.type.accepts(FileTypes.kindOf(it.substringAfterLast('/'), false)) }
            .distinct().filter { it !in have }
        if (fresh.isNotEmpty()) update(id) { it.copy(items = it.items + fresh) }
        fresh.size
    }

    fun remove(id: String, paths: Collection<String>) {
        val set = paths.toHashSet()
        synchronized(lock) { update(id) { it.copy(items = it.items.filterNot { p -> p in set }, cover = it.cover) } }
    }

    fun move(id: String, from: Int, to: Int) {
        synchronized(lock) { update(id) { it.copy(items = PlaylistRules.move(it.items, from, to)) } }
    }

    /** Saves a new play order: [order] first (as given), any other entries (missing files) after it. */
    fun reorder(id: String, order: List<String>) {
        synchronized(lock) {
            update(id) { p ->
                val keep = order.filter { it in p.items }.distinct()
                p.copy(items = keep + p.items.filterNot { it in keep.toHashSet() })
            }
        }
    }

    /** A file renamed or moved inside the app keeps its place in every playlist. */
    fun pathChanged(old: String, new: String) = synchronized(lock) {
        fun map(path: String): String = when {
            path == old -> new
            path.startsWith("$old/") -> new + path.substring(old.length) // a moved/renamed folder
            else -> path
        }
        fun hit(path: String?) = path != null && (path == old || path.startsWith("$old/"))
        if (all().none { p -> hit(p.cover) || p.items.any(::hit) }) return@synchronized
        commit(all().map { p ->
            p.copy(items = p.items.map(::map).distinct(), cover = p.cover?.let(::map))
        })
    }

    private fun update(id: String, f: (Playlist) -> Playlist): Playlist? {
        var out: Playlist? = null
        commit(all().map { if (it.id == id) f(it).copy(modified = clock()).also { n -> out = n } else it })
        return out
    }

    private fun commit(list: List<Playlist>) {
        _playlists.value = list
        val json = encode(list)
        if (sync) write(json) else writer.launch { write(json) }
    }

    private fun write(json: String) = runCatching {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(json)
        if (!tmp.renameTo(file)) { file.writeText(json); tmp.delete() }
    }.onFailure { Diagnostics.log("playlists", "save failed", it) }

    private fun load(): List<Playlist> = runCatching { if (file.exists()) decode(file.readText()) else emptyList() }
        .onFailure { Diagnostics.log("playlists", "load failed", it) }
        .getOrDefault(emptyList())

    companion object {
        fun encode(list: List<Playlist>): String {
            val arr = JSONArray()
            list.forEach { p ->
                arr.put(JSONObject().apply {
                    put("id", p.id); put("type", p.type.name); put("name", p.name)
                    put("items", JSONArray(p.items))
                    if (p.cover != null) put("cover", p.cover)
                    put("created", p.created); put("modified", p.modified)
                })
            }
            return JSONObject().put("version", 1).put("playlists", arr).toString()
        }

        fun decode(text: String): List<Playlist> {
            val arr = JSONObject(text).optJSONArray("playlists") ?: return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val type = runCatching { PlaylistType.valueOf(o.getString("type")) }.getOrNull() ?: return@mapNotNull null
                val items = o.optJSONArray("items")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
                Playlist(
                    id = o.optString("id").ifEmpty { UUID.randomUUID().toString() }, type = type, name = o.optString("name"),
                    items = items, cover = if (o.has("cover")) o.getString("cover") else null,
                    created = o.optLong("created"), modified = o.optLong("modified"),
                )
            }
        }
    }
}

/** The app's playlist store (app-private `files/playlists.json`). */
object Playlists {
    @Volatile private var store: PlaylistStore? = null

    fun get(context: Context): PlaylistStore = store ?: synchronized(this) {
        store ?: run {
            val app = context.applicationContext
            PlaylistStore(File(app.filesDir, "playlists.json"), privateRoots = { StoragePaths.privateRoots(app) }).also { store = it }
        }
    }

    /** Whether a playlist entry's file is still there. */
    fun exists(path: String): Boolean = path.startsWith("content://") || File(path).exists()

    /** A playlist entry as a file item (for thumbnails and the players). */
    fun itemOf(path: String): FileItem = FileItem.of(File(path))

    /** For tests: use this store. */
    fun install(s: PlaylistStore?) { store = s }
}
