package com.clpstudio.filesmanager

import com.clpstudio.filesmanager.data.HomeRepository
import com.clpstudio.filesmanager.data.KeepLastGoodLoader
import com.clpstudio.filesmanager.data.Loadable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class KeepLastGoodLoaderTest {

    @Test fun pendingUntilTheFirstLoadFinishes() = runTest {
        val gate = CompletableDeferred<Int>()
        val loader = KeepLastGoodLoader(this) { gate.await() }
        assertTrue(loader.state.value.pending)
        loader.refresh()
        advanceUntilIdle()
        assertTrue(loader.state.value.loading)
        assertTrue(loader.state.value.pending) // still "…", never 0
        gate.complete(42)
        advanceUntilIdle()
        assertEquals(Loadable(42, loading = false, loaded = true), loader.state.value)
    }

    @Test fun failedRefreshKeepsTheLastGoodValue() = runTest {
        var fail = false
        var n = 0
        val loader = KeepLastGoodLoader(this, retryDelayMs = 10) { n++; if (fail) error("storage busy") else 7 }
        loader.refresh(); advanceUntilIdle()
        assertEquals(7, loader.state.value.value)
        fail = true
        loader.refresh(); advanceUntilIdle()
        assertEquals(7, loader.state.value.value) // not wiped
        assertNotNull(loader.state.value.error)
        assertFalse(loader.state.value.loading)
        assertEquals(3, n) // first load + failed load + its one retry
    }

    @Test fun retriesOnceThenSucceeds() = runTest {
        var calls = 0
        val loader = KeepLastGoodLoader(this, retryDelayMs = 10) { if (++calls == 1) error("cursor closed") else 5 }
        loader.refresh(); advanceUntilIdle()
        assertEquals(2, calls)
        assertEquals(5, loader.state.value.value)
        assertNull(loader.state.value.error)
    }

    @Test fun refreshDuringALoadIsCoalescedNotCancelled() = runTest {
        val gates = ArrayDeque<CompletableDeferred<Int>>()
        var started = 0
        val loader = KeepLastGoodLoader(this) {
            started++
            CompletableDeferred<Int>().also { gates.addLast(it) }.await()
        }
        loader.refresh(); advanceUntilIdle()
        // Auto-refresh fires three times while the first load is still running.
        loader.refresh(); loader.refresh(); loader.refresh(); advanceUntilIdle()
        assertEquals(1, started)
        gates.removeFirst().complete(100); advanceUntilIdle()
        assertEquals(100, loader.state.value.value) // the first load's result landed (not cancelled)
        assertEquals(2, started) // exactly one more load for the three refreshes
        assertTrue(loader.state.value.loading)
        gates.removeFirst().complete(101); advanceUntilIdle()
        assertEquals(101, loader.state.value.value)
        assertFalse(loader.state.value.loading)
        assertEquals(2, started)
    }

    @Test fun seedShowsUntilTheLoadLands() = runTest {
        val gate = CompletableDeferred<List<String>>()
        val loader = KeepLastGoodLoader(this) { gate.await() }
        loader.seed(listOf("cached"))
        assertEquals(listOf("cached"), loader.state.value.value)
        assertFalse(loader.state.value.loaded)
        loader.refresh(); advanceUntilIdle()
        assertEquals(listOf("cached"), loader.state.value.value)
        gate.complete(listOf("fresh", "list")); advanceUntilIdle()
        assertEquals(listOf("fresh", "list"), loader.state.value.value)
        loader.seed(listOf("stale"))
        assertEquals(listOf("fresh", "list"), loader.state.value.value) // a seed never overrides a real load
    }

    @Test fun countLabels() {
        assertEquals("…", HomeRepository.countLabel(null))
        assertEquals("…", HomeRepository.countLabel(Loadable(loading = true)))
        assertEquals("—", HomeRepository.countLabel(Loadable(error = IllegalStateException())))
        assertEquals("1 item", HomeRepository.countLabel(Loadable(1, loaded = true)))
        assertEquals("0 items", HomeRepository.countLabel(Loadable(0, loaded = true)))
        // Last good value wins over a later error.
        assertEquals("12 items", HomeRepository.countLabel(Loadable(12, error = RuntimeException())))
    }

    @Test fun mediaIsPhotosPlusVideosOnlyWhenBothKnown() {
        assertTrue(HomeRepository.mediaCount(Loadable(3, loaded = true), Loadable(loading = true)).pending)
        assertEquals(5, HomeRepository.mediaCount(Loadable(3, loaded = true), Loadable(2, loaded = true)).value)
    }
}
