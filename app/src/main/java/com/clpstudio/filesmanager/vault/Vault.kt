package com.clpstudio.filesmanager.vault

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.LruCache
import com.clpstudio.filesmanager.Graph
import com.clpstudio.filesmanager.data.FileOps
import com.clpstudio.filesmanager.data.FileEvents
import com.clpstudio.filesmanager.data.OpProgress
import com.clpstudio.filesmanager.data.StoragePaths
import com.clpstudio.filesmanager.security.AttemptLimiter
import com.clpstudio.filesmanager.security.AttemptState
import com.clpstudio.filesmanager.security.AttemptStore
import com.clpstudio.filesmanager.security.LockClock
import com.clpstudio.filesmanager.security.CredentialType
import com.clpstudio.filesmanager.security.KeystoreKeyWrapper
import com.clpstudio.filesmanager.ui.AppState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

enum class VaultState { NOT_SET_UP, LOCKED, UNLOCKED }

/** Vault settings → Auto-lock. */
enum class VaultAutoLock(val label: String, val backgroundMs: Long, val onLeave: Boolean) {
    LEAVE_VAULT("When you leave the vault", 0L, true),
    LEAVE_APP("When you leave the app", 0L, false),
    MIN_1("1 minute after leaving the app", 60_000L, false),
    MIN_5("5 minutes after leaving the app", 5 * 60_000L, false),
}

/** The app's vault: state for the screens, auto-lock, and move in / move out / delete with progress. */
object Vault {
    private lateinit var appContext: Context
    private lateinit var prefs: SharedPreferences
    lateinit var store: VaultStore
        private set

    private val _state = MutableStateFlow(VaultState.NOT_SET_UP)
    val state: StateFlow<VaultState> = _state.asStateFlow()
    private val _items = MutableStateFlow<List<VaultItem>>(emptyList())
    val items: StateFlow<List<VaultItem>> = _items.asStateFlow()
    private val _count = MutableStateFlow(0)
    /** Number of files (known even while locked, used on the Home tile). */
    val count: StateFlow<Int> = _count.asStateFlow()

    /** Small decrypted previews, kept in memory only and dropped when the vault locks. */
    val thumbs = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    private var screens = 0
    private var leaveJob: Job? = null
    private var backgroundAt: Long? = null

    fun init(context: Context) {
        if (::store.isInitialized) return
        appContext = context.applicationContext
        prefs = appContext.getSharedPreferences("vault", Context.MODE_PRIVATE)
        store = VaultStore(StoragePaths.vaultDir(appContext), KeystoreKeyWrapper())
        refreshState()
    }

    val isReady: Boolean get() = ::store.isInitialized

    private fun refreshState() {
        _state.value = when {
            !store.isSetUp -> VaultState.NOT_SET_UP
            store.isUnlocked -> VaultState.UNLOCKED
            else -> VaultState.LOCKED
        }
        _count.value = store.count()
    }

    var autoLock: VaultAutoLock
        get() = prefs.getString("auto_lock", null)?.let { runCatching { VaultAutoLock.valueOf(it) }.getOrNull() } ?: VaultAutoLock.LEAVE_VAULT
        set(v) { prefs.edit().putString("auto_lock", v.name).apply() }

    // --- wrong tries: kept on disk so closing the app doesn't reset the wait ---
    private val attemptStore = AttemptStore { if (::prefs.isInitialized) prefs else null }
    private fun clock() = LockClock.now(if (::appContext.isInitialized) appContext else null)
    var attempts: AttemptState
        get() = attemptStore.load(clock())
        private set(v) { attemptStore.save(v) }

    /** Time left in a wrong-try lockout (0 = can try now). */
    fun remainingMs(): Long = attemptStore.remainingNow(clock())

    fun hardwareKind(): String = store.keyFile.hardware() ?: "Android Keystore"

    suspend fun setup(type: CredentialType, secret: String) = withContext(Dispatchers.Default) {
        store.setup(type, secret)
        attempts = AttemptState()
        reload()
    }

    sealed interface UnlockResult {
        data object Ok : UnlockResult
        data class Wrong(val message: String) : UnlockResult
        data class Lost(val message: String) : UnlockResult
    }

    suspend fun unlock(secret: String): UnlockResult = withContext(Dispatchers.Default) {
        val now = clock()
        val allowed = AttemptLimiter.allowedNow()
        val a = attempts
        if (AttemptLimiter.remaining(a, now) > 0) return@withContext UnlockResult.Wrong(AttemptLimiter.message(a, now, allowed)!!)
        try {
            store.unlock(secret)
            attempts = AttemptLimiter.onSuccess()
            reload()
            UnlockResult.Ok
        } catch (e: WrongSecretException) {
            val n = AttemptLimiter.onFailure(a, now, allowed)
            attempts = n
            UnlockResult.Wrong(AttemptLimiter.message(n, now, allowed) ?: "Wrong")
        } catch (e: VaultKeyLostException) {
            UnlockResult.Lost(e.message ?: "The vault can't be opened on this phone")
        }
    }

    /** Fingerprint unlock (item 5): [mk] already matched this vault's check value. A success resets wrong tries. */
    fun unlockWithMasterKey(mk: ByteArray) {
        store.unlockWithKey(mk)
        attempts = AttemptLimiter.onSuccess()
        com.clpstudio.filesmanager.Graph.appScope.launch { reload() }
    }

    /** Renames a vault item; returns an error message or null. Same name rules as files (no leading dot). */
    suspend fun rename(item: VaultItem, typed: String): String? = withContext(Dispatchers.IO) {
        val name = typed.trim()
        com.clpstudio.filesmanager.data.InfoEdits.nameError(name, item.name) { false }?.let { return@withContext it }
        if (name == item.name) return@withContext null
        try { store.rename(item, name); reload(); null } catch (e: Exception) { e.message ?: "Couldn't rename it" }
    }

    /**
     * "Forgot PIN/password? Reset vault" (item 58): deletes every vault file, its keys/salt/metadata and the
     * thumbnails, and the wrong-try lockout, so the vault is back to "Set up vault". Works during a lockout and
     * without the PIN (nothing can be recovered: the files are encrypted with a key that is deleted too).
     * Normal files and the app lock are not touched.
     */
    suspend fun resetForgotten() = withContext(Dispatchers.IO) {
        if (!::store.isInitialized) return@withContext
        VaultBiometric.disable()
        store.deleteVault()
        thumbs.evictAll()
        attempts = AttemptState()
        _items.value = emptyList()
        withContext(Dispatchers.Main) { refreshState() }
    }

    fun lock() {
        if (!::store.isInitialized) return
        store.lock()
        thumbs.evictAll()
        _items.value = emptyList()
        refreshState()
    }

    suspend fun changeSecret(old: String, type: CredentialType, new: String): Boolean = withContext(Dispatchers.Default) {
        try { store.changeSecret(old, type, new); true } catch (e: WrongSecretException) { false }
    }

    suspend fun checkSecret(secret: String): Boolean = withContext(Dispatchers.Default) { store.checkSecret(secret) }

    suspend fun deleteVault() = withContext(Dispatchers.IO) {
        VaultBiometric.disable()
        store.deleteVault()
        thumbs.evictAll()
        attempts = AttemptState()
        _items.value = emptyList()
        refreshState()
    }

    suspend fun reload() = withContext(Dispatchers.IO) {
        _items.value = if (store.isUnlocked) runCatching { store.list() }.getOrDefault(emptyList()) else emptyList()
        refreshState()
    }

    // --- auto-lock ---

    /** A vault screen (list, viewer, or a PDF/text opened from it) is showing. */
    fun screenShown() {
        screens++
        leaveJob?.cancel()
    }

    fun screenGone() {
        screens = (screens - 1).coerceAtLeast(0)
        if (screens == 0 && autoLock.onLeave && store.isUnlocked) {
            leaveJob?.cancel()
            // A short grace so moving between the list and a viewer doesn't lock in between.
            leaveJob = Graph.appScope.launch(Dispatchers.Main) {
                delay(1500)
                if (screens == 0) lock()
            }
        }
    }

    fun onAppBackground() {
        if (!::store.isInitialized || !store.isUnlocked) return
        val mode = autoLock
        if (mode.backgroundMs == 0L) lock() else backgroundAt = SystemClock.elapsedRealtime()
    }

    fun onAppForeground() {
        val since = backgroundAt ?: return
        backgroundAt = null
        if (SystemClock.elapsedRealtime() - since >= autoLock.backgroundMs) lock()
    }

    // --- moving files ---

    /** Files under [roots] (folders: every file inside), the order kept. */
    internal fun expand(roots: List<File>): List<File> = roots.flatMap { r ->
        if (r.isDirectory) r.walkTopDown().filter { it.isFile }.toList() else if (r.isFile) listOf(r) else emptyList()
    }

    /** Moves files (and whole folders) into the vault: encrypt, check the copy, then delete the original. */
    fun moveIn(context: Context, roots: List<File>, onDone: () -> Unit = {}) {
        AppState.runOperation(context, "Moving to vault") { report ->
            if (!store.isUnlocked) throw VaultLockedException()
            val files = expand(roots)
            val total = files.sumOf { it.length() }
            val free = StoragePaths.vaultDir(context).let { it.mkdirs(); it.usableSpace }
            if (VaultFormat.encryptedSize(total) + 50L * 1024 * 1024 > free) {
                throw IOException("Not enough free space on the phone to move these into the vault")
            }
            var doneBytes = 0L
            val gone = ArrayList<String>()
            var failed = 0
            val job = kotlin.coroutines.coroutineContext[Job]
            files.forEachIndexed { i, f ->
                val base = doneBytes
                val size = f.length()
                report(OpProgress("Moving to vault", f.name, base, total, i, files.size))
                val item = try {
                    store.importFile(f, onProgress = { report(OpProgress("Moving to vault", f.name, base + it, total, i, files.size)) }, cancelled = { job?.isActive == false })
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    com.clpstudio.filesmanager.data.Diagnostics.log("vault", "couldn't move ${f.name} in", e)
                    failed++
                    doneBytes = base + size
                    return@forEachIndexed
                }
                if (f.delete() || !f.exists()) {
                    gone += f.absolutePath
                } else {
                    // The original couldn't be removed (read-only place): don't keep two copies.
                    store.remove(item)
                    failed++
                }
                doneBytes = base + size
            }
            // Folders that are now empty go too.
            roots.filter { it.isDirectory }.forEach { r ->
                r.walkBottomUp().filter { it.isDirectory && (it.list()?.isEmpty() == true) }.forEach { d -> if (d.delete()) gone += d.absolutePath }
            }
            afterRemoved(context, gone)
            reload()
            // Opened just for this move (from a file list): lock again when "lock when you leave the vault" is on.
            if (screens == 0 && autoLock.onLeave) withContext(Dispatchers.Main) { lock() }
            withContext(Dispatchers.Main) { onDone() }
            val moved = files.size - failed
            val msg = when {
                failed == 0 -> "$moved file${if (moved == 1) "" else "s"} moved to the vault"
                moved == 0 -> "Couldn't move to the vault"
                else -> "$moved moved to the vault, $failed couldn't be moved"
            }
            msg to emptyList()
        }
    }

    /** The original location's folder, or Download/Vault when that place can't be written any more. */
    fun restoreDir(item: VaultItem): File {
        val parent = File(item.originalPath).parentFile
        if (parent != null && (parent.isDirectory || parent.mkdirs()) && parent.canWrite()) return parent
        return File(StoragePaths.downloads(), "Vault")
    }

    /** Decrypts back to the original folder ([dest] = null) or into [dest], then removes them from the vault. */
    fun moveOut(context: Context, items: List<VaultItem>, dest: File?, onDone: () -> Unit = {}) {
        AppState.runOperation(context, "Moving out of the vault") { report ->
            if (!store.isUnlocked) throw VaultLockedException()
            val total = items.sumOf { it.size }
            val job = kotlin.coroutines.coroutineContext[Job]
            var done = 0L
            val created = ArrayList<File>()
            items.forEachIndexed { i, item ->
                val base = done
                val dir = dest ?: restoreDir(item)
                if (dir.usableSpace in 1 until item.size + 10L * 1024 * 1024) throw IOException("Not enough free space for ${item.name}")
                val out = store.export(item, dir, onProgress = { report(OpProgress("Moving out of the vault", item.name, base + it, total, i, items.size)) }, cancelled = { job?.isActive == false })
                store.remove(item)
                thumbs.remove(item.id)
                created += out
                done += item.size
            }
            FileEvents.added(created.map { it.absolutePath })
            reload()
            withContext(Dispatchers.Main) { onDone() }
            val where = dest?.name ?: if (items.size == 1) restoreDirName(created.firstOrNull()) else "their folders"
            "${items.size} file${if (items.size == 1) "" else "s"} moved out to $where" to created
        }
    }

    private fun restoreDirName(f: File?) = f?.parentFile?.name ?: "its folder"

    fun deleteForever(context: Context, items: List<VaultItem>, onDone: () -> Unit = {}) {
        AppState.runOperation(context, "Deleting") { _ ->
            items.forEach { store.remove(it); thumbs.remove(it.id) }
            reload()
            withContext(Dispatchers.Main) { onDone() }
            "${items.size} file${if (items.size == 1) "" else "s"} deleted for good" to emptyList()
        }
    }

    private fun afterRemoved(context: Context, paths: List<String>) {
        if (paths.isEmpty()) return
        FileEvents.removed(paths)
        com.clpstudio.filesmanager.data.Favorites.onDeleted(paths)
        runCatching { FileOps.scan(context, paths) }
        // Drop in-memory thumbnails of the originals, so nothing of them stays on screen.
        runCatching {
            val cache = coil3.SingletonImageLoader.get(context).memoryCache ?: return@runCatching
            cache.keys.filter { k -> paths.any { p -> k.key.contains(p) } }.forEach { cache.remove(it) }
        }
    }
}
