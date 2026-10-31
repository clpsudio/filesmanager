package com.clpstudio.filesmanager

import com.clpstudio.filesmanager.viewer.HexDump
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HexDumpTest {
    @Test fun formatsLines() {
        val lines = HexDump.lines("Hello, Files!\n\u0000\u0001ABC".toByteArray())
        assertEquals(2, lines.size)
        assertTrue(lines[0].startsWith("00000000  48 65 6c 6c 6f 2c 20 46  69 6c 65 73 21 0a 00 01"))
        assertTrue(lines[0].endsWith("|Hello, Files!...|"))
        assertTrue(lines[1].startsWith("00000010  41 42 43"))
    }

    @Test fun textDetection() {
        assertTrue(HexDump.looksLikeText("plain text\nline 2".toByteArray()))
        assertFalse(HexDump.looksLikeText(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x00)))
    }
}
