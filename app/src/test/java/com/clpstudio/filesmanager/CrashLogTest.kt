package com.clpstudio.filesmanager

import android.app.Application
import android.content.Intent
import com.clpstudio.filesmanager.crash.CrashLog
import com.clpstudio.filesmanager.crash.CrashReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CrashLogTest {
    @get:Rule val tmp = TemporaryFolder()
    private val t0 = 1_790_000_000_000L

    @Test fun reportHasDeviceAndStackTrace() {
        val e = IllegalStateException("boom", IllegalArgumentException("root cause"))
        val text = CrashLog.buildReport("main", e, t0, listOf("App: Files 1.0.3 (3)", "Device: samsung SM-X210"))
        assertTrue(text.contains("App: Files 1.0.3 (3)"))
        assertTrue(text.contains("Device: samsung SM-X210"))
        assertTrue(text.contains("Thread: main"))
        assertTrue(text.contains("java.lang.IllegalStateException: boom"))
        assertTrue(text.contains("Caused by: java.lang.IllegalArgumentException: root cause"))
        assertEquals("java.lang.IllegalStateException: boom", CrashLog.titleOf(text))
    }

    @Test fun keepsOnlyTheLastTen() {
        val dir = tmp.newFolder("crash")
        for (i in 0 until 15) CrashLog.write(dir, "report $i\n\nE$i", t0 + i * 1000L)
        val list = CrashLog.list(dir)
        assertEquals(10, list.size)
        // Newest first, the five oldest gone.
        assertEquals("E14", list.first().title)
        assertEquals("E5", list.last().title)
    }

    @Test fun sameMillisecondGetsItsOwnFile() {
        val dir = tmp.newFolder("crash")
        val a = CrashLog.write(dir, "a\n\nA", t0)
        val b = CrashLog.write(dir, "b\n\nB", t0)
        assertTrue(a.name != b.name)
        assertEquals(2, CrashLog.list(dir).size)
    }

    @Test fun noticeShowsOnceAfterACrash() {
        val dir = tmp.newFolder("crash")
        assertFalse(CrashLog.consumePendingNotice(dir))
        CrashLog.write(dir, "x\n\nX", t0)
        assertTrue(CrashLog.consumePendingNotice(dir))
        assertFalse(CrashLog.consumePendingNotice(dir))
        // The marker isn't counted as a report.
        assertEquals(1, CrashLog.list(dir).size)
    }

    @Test fun shareIsPlainTextSend() {
        val dir = tmp.newFolder("crash")
        val f = CrashLog.write(dir, "Files crash report\n\njava.lang.RuntimeException: x", t0)
        val chooser = CrashLog.shareIntent(CrashReport(f, t0, "x"))
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION")
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("text/plain", send.type)
        assertTrue(send.getStringExtra(Intent.EXTRA_TEXT)!!.contains("RuntimeException"))
    }
}
