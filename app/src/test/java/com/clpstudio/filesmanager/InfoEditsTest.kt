package com.clpstudio.filesmanager

import com.clpstudio.filesmanager.data.InfoEdits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

/** Item 15: editing name and date & time from the info sheet. */
class InfoEditsTest {
    private val india = ZoneId.of("Asia/Kolkata")
    private val ny = ZoneId.of("America/New_York")
    private fun day(y: Int, m: Int, d: Int) = LocalDate.of(y, m, d).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    private val none: (String) -> Boolean = { false }

    @Test fun composesDayAndTimeOnTheDeviceClock() {
        val ms = InfoEdits.compose(day(2026, 10, 31), 14, 30, india)
        assertEquals(ZonedDateTime.of(2026, 10, 31, 14, 30, 0, 0, india).toInstant().toEpochMilli(), ms)
        assertEquals(ZonedDateTime.of(2026, 10, 31, 9, 0, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli(), ms)
    }

    @Test fun midnightAndLastMinuteStayOnThePickedDay() {
        val start = InfoEdits.compose(day(2026, 1, 1), 0, 0, india)
        val end = InfoEdits.compose(day(2026, 1, 1), 23, 59, india)
        assertEquals(day(2026, 1, 1), InfoEdits.pickerDateOf(start, india))
        assertEquals(day(2026, 1, 1), InfoEdits.pickerDateOf(end, india))
        assertEquals(23 to 59, InfoEdits.timeOf(end, india))
    }

    @Test fun pickerDayRoundTripsAcrossZones() {
        // 31 Oct 2026 01:00 in India is still 30 Oct in UTC: the picker must show the local day.
        val ms = ZonedDateTime.of(2026, 10, 31, 1, 0, 0, 0, india).toInstant().toEpochMilli()
        assertEquals(day(2026, 10, 31), InfoEdits.pickerDateOf(ms, india))
        assertEquals(1 to 0, InfoEdits.timeOf(ms, india))
        assertEquals(ms, InfoEdits.compose(InfoEdits.pickerDateOf(ms, india), 1, 0, india))
    }

    @Test fun daylightSavingGapMovesForward() {
        // 8 Mar 2026, 02:30 does not exist in New York (clocks jump 02:00 -> 03:00).
        val ms = InfoEdits.compose(day(2026, 3, 8), 2, 30, ny)
        assertEquals(ZonedDateTime.of(2026, 3, 8, 3, 30, 0, 0, ny).toInstant().toEpochMilli(), ms)
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsBadTime() {
        InfoEdits.compose(day(2026, 1, 1), 24, 0, india)
    }

    @Test fun exifTextAndOffset() {
        val ms = InfoEdits.compose(day(2026, 10, 31), 14, 5, india)
        assertEquals("2026:10:31 14:05:00", InfoEdits.exifDate(ms, india))
        assertEquals("+05:30", InfoEdits.exifOffset(ms, india))
        assertEquals("-05:00", InfoEdits.exifOffset(InfoEdits.compose(day(2026, 1, 15), 9, 0, ny), ny))
    }

    @Test fun dateAppliedAllowsWholeSecondFileSystems() {
        assertTrue(InfoEdits.dateApplied(1_000_000L, 1_000_000L))
        assertTrue(InfoEdits.dateApplied(1_000_500L, 1_000_000L))
        assertTrue(InfoEdits.dateApplied(1_002_000L, 1_000_000L))
        assertFalse(InfoEdits.dateApplied(1_000_000L, 0L))
    }

    @Test fun validNamesPass() {
        assertNull(InfoEdits.nameError("Holiday 2026.jpg", "IMG_1.jpg", none))
        assertNull(InfoEdits.nameError("  trimmed.pdf  ", "a.pdf", none))
        assertNull(InfoEdits.nameError("नमस्ते.txt", "a.txt", none))
        // Item 56: a leading dot would hide the file, so it's refused.
        assertNotNull(InfoEdits.nameError(".hidden", "a", none))
    }

    @Test fun emptyAndDotNamesFail() {
        assertEquals("Type a name", InfoEdits.nameError("", "a.jpg", none))
        assertEquals("Type a name", InfoEdits.nameError("   ", "a.jpg", none))
        assertNotNull(InfoEdits.nameError(".", "a.jpg", none))
        assertNotNull(InfoEdits.nameError("..", "a.jpg", none))
    }

    @Test fun illegalCharactersFail() {
        for (c in listOf("/", "\\", ":", "*", "?", "\"", "<", ">", "|")) {
            val err = InfoEdits.nameError("a${c}b.jpg", "a.jpg", none)
            assertNotNull(c, err)
            assertTrue(err!!, err.contains(c))
        }
        assertNotNull(InfoEdits.nameError("a\u0000b", "a", none))
        assertNotNull(InfoEdits.nameError("a\nb", "a", none))
    }

    @Test fun tooLongNamesFail() {
        assertNull(InfoEdits.nameError("a".repeat(251) + ".jpg", "a.jpg", none))
        assertNotNull(InfoEdits.nameError("a".repeat(252) + ".jpg", "a.jpg", none))
        // 255 bytes, not characters: each Devanagari letter is 3 bytes in UTF-8.
        assertNotNull(InfoEdits.nameError("न".repeat(86), "a", none))
        assertNull(InfoEdits.nameError("न".repeat(85), "a", none))
    }

    @Test fun clashWithAnotherFileFails() {
        val taken = setOf("b.jpg", "Photos")
        assertEquals("\"b.jpg\" is already in this folder", InfoEdits.nameError("b.jpg", "a.jpg") { it in taken })
        assertNotNull(InfoEdits.nameError("Photos", "Pics") { it in taken })
    }

    @Test fun sameNameOrCaseOnlyIsAllowed() {
        val all: (String) -> Boolean = { true } // case-insensitive storage says every spelling exists
        assertNull(InfoEdits.nameError("a.jpg", "a.jpg", all))
        assertNull(InfoEdits.nameError("A.JPG", "a.jpg", all))
        assertTrue(InfoEdits.isCaseOnly("A.JPG", "a.jpg"))
        assertFalse(InfoEdits.isCaseOnly("a.jpg", "a.jpg"))
        assertFalse(InfoEdits.isCaseOnly("b.jpg", "a.jpg"))
    }
}
