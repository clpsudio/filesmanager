package com.clpstudio.filesmanager

import com.clpstudio.filesmanager.data.FileItem
import com.clpstudio.filesmanager.ui.components.DragSelectLogic
import com.clpstudio.filesmanager.ui.components.SelectionState
import com.clpstudio.filesmanager.ui.screens.ApkSelectionLogic
import com.clpstudio.filesmanager.ui.screens.ApkSelectionLogic.TapResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApkSelectionTest {
    private val apks = listOf("WhatsApp.apk", "Files_1.0.3.apk", "Game.APK", "notes.apk").mapIndexed { i, n ->
        FileItem("/storage/emulated/0/Download/$n", n, false, 1_000_000L * (i + 1), 1_000L * i)
    }
    private val paths = apks.map { it.path }

    @Test fun tapInstallsUntilSelecting() {
        assertEquals(TapResult.Open(paths[0]), ApkSelectionLogic.onTap(false, paths[0]))
        assertEquals(TapResult.Toggle(paths[0]), ApkSelectionLogic.onTap(true, paths[0]))
    }

    @Test fun longPressStartsSelectionThenTapsToggle() {
        val sel = SelectionState()
        assertFalse(sel.active)
        // Long-press on item 1 (dragToSelect: anchor = current = 1).
        sel.selectAll(DragSelectLogic.apply(paths, sel.paths.toSet(), 1, 1, select = true).toList())
        assertTrue(sel.active)
        assertEquals(listOf(paths[1]), sel.paths.toList())
        // Taps now toggle.
        (ApkSelectionLogic.onTap(sel.active, paths[3]) as TapResult.Toggle).let { sel.toggle(it.path) }
        (ApkSelectionLogic.onTap(sel.active, paths[1]) as TapResult.Toggle).let { sel.toggle(it.path) }
        assertEquals(listOf(paths[3]), sel.paths.toList())
        // Untoggling the last one leaves selection mode.
        sel.toggle(paths[3])
        assertFalse(sel.active)
    }

    @Test fun dragSelectsARange() {
        val r = DragSelectLogic.apply(paths, emptySet(), anchor = 0, current = 2, select = true)
        assertEquals(paths.subList(0, 3).toSet(), r)
    }

    @Test fun selectAllTogglesAndKeepsListOrder() {
        assertEquals(paths, ApkSelectionLogic.toggleAll(apks, listOf(paths[2])))
        assertEquals(emptyList<String>(), ApkSelectionLogic.toggleAll(apks, paths.reversed()))
        assertEquals(emptyList<String>(), ApkSelectionLogic.toggleAll(emptyList(), emptyList()))
        assertEquals(listOf(apks[0], apks[3]), ApkSelectionLogic.selectedItems(apks, listOf(paths[3], paths[0])))
    }

    @Test fun pruneDropsDeletedOrMovedFiles() {
        val after = apks.filter { it.path != paths[1] }
        assertEquals(listOf(paths[0], paths[2]), ApkSelectionLogic.prune(listOf(paths[0], paths[1], paths[2]), after))
        assertEquals(listOf(apks[2]), ApkSelectionLogic.selectedItems(after, listOf(paths[1], paths[2])))
    }

    @Test fun installOnlyForApks() {
        assertTrue(ApkSelectionLogic.allApks(apks))
        assertFalse(ApkSelectionLogic.allApks(emptyList()))
        assertFalse(ApkSelectionLogic.allApks(apks + FileItem("/x/readme.txt", "readme.txt", false, 1, 1)))
    }
}
