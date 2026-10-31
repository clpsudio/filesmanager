package com.clpstudio.filesmanager

import com.clpstudio.filesmanager.data.FileKind
import com.clpstudio.filesmanager.data.LockState
import com.clpstudio.filesmanager.data.PasswordEntry
import com.clpstudio.filesmanager.data.PasswordMenu
import com.clpstudio.filesmanager.data.PdfSecurity
import com.clpstudio.filesmanager.ui.components.SwipeTabsLogic
import org.junit.Assert.assertEquals
import org.junit.Test

class Round5MenusTest {
    @Test fun unprotectedPdfShowsOnlyAdd() {
        assertEquals(listOf(PasswordEntry.ADD), PasswordMenu.entries(FileKind.PDF, LockState.NONE))
    }

    @Test fun protectedPdfShowsOnlyRemove() {
        assertEquals(listOf(PasswordEntry.REMOVE), PasswordMenu.entries(FileKind.PDF, LockState.PROTECTED))
        assertEquals(LockState.PROTECTED, PasswordMenu.fromPdf(PdfSecurity.Lock.USER_PASSWORD))
        assertEquals(LockState.PROTECTED, PasswordMenu.fromPdf(PdfSecurity.Lock.OWNER_ONLY))
        assertEquals(LockState.NONE, PasswordMenu.fromPdf(PdfSecurity.Lock.NONE))
    }

    @Test fun nothingWhileUnknownOrForOtherTypes() {
        assertEquals(emptyList<PasswordEntry>(), PasswordMenu.entries(FileKind.PDF, LockState.UNKNOWN))
        for (k in listOf(FileKind.IMAGE, FileKind.VIDEO, FileKind.ARCHIVE, FileKind.TEXT, FileKind.DOCUMENT)) {
            for (s in LockState.entries) assertEquals("$k $s", emptyList<PasswordEntry>(), PasswordMenu.entries(k, s))
        }
    }

    @Test fun swipeLeftIsNextRightIsPrevious() {
        assertEquals(1, SwipeTabsLogic.target(0, 3, -100f, 64f))
        assertEquals(0, SwipeTabsLogic.target(1, 3, 100f, 64f))
        assertEquals(1, SwipeTabsLogic.target(1, 3, 30f, 64f)) // too short: stays
        assertEquals(3, SwipeTabsLogic.target(2, 3, -100f, 64f)) // past the end
        assertEquals(-1, SwipeTabsLogic.target(0, 3, 100f, 64f)) // past the start
        assertEquals(2, SwipeTabsLogic.clamp(3, 3))
    }
}
