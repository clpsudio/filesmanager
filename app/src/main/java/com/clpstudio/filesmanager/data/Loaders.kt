package com.clpstudio.filesmanager.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A value that loads in the background and never goes blank once it has been seen.
 *  - [value]: the last good value (or a cached seed), null only before anything is known.
 *  - [loading]: a load is running right now.
 *  - [loaded]: at least one load actually finished (a seed alone doesn't count).
 *  - [error]: the last load failed even after its retry; [value] is still the last good one.
 */
data class Loadable<T>(
    val value: T? = null,
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val error: Throwable? = null,
) {
    /** Never "0"/blank until a load finished: true while there's nothing real to show yet. */
    val pending: Boolean get() = value == null && error == null
}

/**
 * Loads [load] in [scope] (the app scope, so leaving a screen doesn't cancel it) and keeps the last
 * good value while a refresh runs. Refreshes arriving during a load are coalesced into one more
 * load after it, so an auto-refresh racing the first load can neither cancel nor wipe it. A failed
 * load is retried once after [retryDelayMs]; if it fails again, the last good value stays.
 */
class KeepLastGoodLoader<T>(
    private val scope: CoroutineScope,
    seed: T? = null,
    private val retries: Int = 1,
    private val retryDelayMs: Long = 400,
    private val onLoaded: (T) -> Unit = {},
    private val load: suspend () -> T,
) {
    private val _state = MutableStateFlow(Loadable(value = seed))
    val state: StateFlow<Loadable<T>> = _state.asStateFlow()

    private val running = AtomicBoolean(false)
    private val again = AtomicBoolean(false)

    /** Starts a load, or schedules one more after the running one. Safe from any thread. */
    fun refresh() {
        again.set(true)
        if (!running.compareAndSet(false, true)) return
        scope.launch {
            try {
                while (again.getAndSet(false)) runOnce()
            } finally {
                running.set(false)
                // A refresh() that landed between the loop's last check and the flag reset.
                if (again.get()) refresh()
            }
        }
    }

    /** A cached value to show until the first load lands (ignored once anything is known). */
    fun seed(v: T) {
        _state.update { if (it.value == null && !it.loaded) it.copy(value = v) else it }
    }

    /** Changes the shown value in place (e.g. drop deleted files) without a reload. */
    fun prune(transform: (T) -> T) {
        _state.update { s -> s.value?.let { v -> val n = transform(v); if (n === v) s else s.copy(value = n) } ?: s }
    }

    /** Loads now if nothing was ever loaded and nothing is running (first screen show). */
    fun ensureLoaded() {
        if (!_state.value.loaded && !running.get()) refresh()
    }

    /** Suspends until a load has finished (or failed), then returns the value. */
    suspend fun await(): T? {
        ensureLoaded()
        return state.first { it.loaded || (it.error != null && !it.loading) }.value
    }

    private suspend fun runOnce() {
        _state.update { it.copy(loading = true) }
        var attempt = 0
        while (true) {
            try {
                val v = load()
                _state.value = Loadable(value = v, loading = false, loaded = true, error = null)
                runCatching { onLoaded(v) }
                return
            } catch (e: CancellationException) {
                _state.update { it.copy(loading = false) }
                throw e
            } catch (e: Throwable) {
                if (attempt++ < retries) {
                    Diagnostics.log("load", "load failed, retrying", e)
                    delay(retryDelayMs)
                    continue
                }
                Diagnostics.log("load", "load failed twice, keeping the last value", e)
                _state.update { it.copy(loading = false, error = e) }
                return
            }
        }
    }
}
