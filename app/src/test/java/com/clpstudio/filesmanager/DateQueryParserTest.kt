package com.clpstudio.filesmanager

import com.clpstudio.filesmanager.data.DateQueryParser
import com.clpstudio.filesmanager.data.DateRange
import com.clpstudio.filesmanager.data.QuickDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import java.time.LocalDate

class DateQueryParserTest {
    private val today = LocalDate.of(2026, 10, 31) // a Saturday

    private fun day(y: Int, m: Int, d: Int) = DateRange.day(LocalDate.of(y, m, d))

    @Test fun slashDayFirst() = assertEquals(day(2026, 10, 31), DateQueryParser.parse("31/10/2026", today).range)
    @Test fun dashDayFirst() = assertEquals(day(2026, 10, 31), DateQueryParser.parse("31-10-2026", today).range)
    @Test fun dotDayFirst() = assertEquals(day(2026, 10, 31), DateQueryParser.parse("31.10.2026", today).range)
    @Test fun twoDigitYear() = assertEquals(day(2026, 10, 5), DateQueryParser.parse("5/10/26", today).range)
    @Test fun iso() = assertEquals(day(2026, 10, 31), DateQueryParser.parse("2026-10-31", today).range)
    @Test fun dayMonthName() = assertEquals(day(2026, 10, 31), DateQueryParser.parse("31 Oct 2026", today).range)
    @Test fun dayFullMonth() = assertEquals(day(2025, 1, 2), DateQueryParser.parse("2nd January 2025", today).range)
    @Test fun monthDayYear() = assertEquals(day(2026, 10, 31), DateQueryParser.parse("Oct 31, 2026", today).range)
    @Test fun dayMonthNoYear() = assertEquals(day(2026, 3, 14), DateQueryParser.parse("14 mar", today).range)

    @Test fun monthYear() {
        val r = DateQueryParser.parse("Oct 2026", today).range!!
        assertEquals(LocalDate.of(2026, 10, 1), r.start)
        assertEquals(LocalDate.of(2026, 10, 31), r.endInclusive)
    }

    @Test fun fullMonthYearFeb() {
        val r = DateQueryParser.parse("February 2024", today).range!!
        assertEquals(LocalDate.of(2024, 2, 29), r.endInclusive) // leap year
    }

    @Test fun numericMonthYear() = assertEquals(DateRange(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30)), DateQueryParser.parse("09/2026", today).range)

    @Test fun yearOnly() {
        val r = DateQueryParser.parse("2025", today).range!!
        assertEquals(LocalDate.of(2025, 1, 1), r.start)
        assertEquals(LocalDate.of(2025, 12, 31), r.endInclusive)
    }

    @Test fun keywords() {
        assertEquals(day(2026, 10, 31), DateQueryParser.parse("today", today).range)
        assertEquals(day(2026, 10, 30), DateQueryParser.parse("Yesterday", today).range)
        assertEquals(DateRange(LocalDate.of(2026, 10, 26), LocalDate.of(2026, 11, 1)), DateQueryParser.parse("this week", today).range)
        assertEquals(DateRange(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30)), DateQueryParser.parse("last month", today).range)
    }

    @Test fun nameAndDateCombine() {
        val p = DateQueryParser.parse("invoice 31/10/2026", today)
        assertEquals("invoice", p.nameQuery)
        assertEquals(day(2026, 10, 31), p.range)
        val q = DateQueryParser.parse("photo Oct 2026 beach", today)
        assertEquals("photo beach", q.nameQuery)
    }

    @Test fun invalidDateIsJustText() {
        val p = DateQueryParser.parse("31/02/2026", today)
        // 31 Feb doesn't exist: no day match; "02/2026" is read as February instead.
        assertEquals(DateRange(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28)), p.range)
        val q = DateQueryParser.parse("IMG_20261031_1200", today)
        assertNull(q.range) // digits inside a file name are not a date
        assertEquals("IMG_20261031_1200", q.nameQuery)
    }

    @Test fun plainNameHasNoDate() {
        val p = DateQueryParser.parse("holiday video", today)
        assertNull(p.range)
        assertEquals("holiday video", p.nameQuery)
    }

    @Test fun quickChips() {
        assertEquals(day(2026, 10, 31), DateQueryParser.quick(QuickDate.TODAY, today))
        assertEquals(day(2026, 10, 30), DateQueryParser.quick(QuickDate.YESTERDAY, today))
        assertEquals(LocalDate.of(2026, 10, 1), DateQueryParser.quick(QuickDate.THIS_MONTH, today)!!.start)
        assertNull(DateQueryParser.quick(QuickDate.ANY, today))
    }

    @Test fun nameMatchIsCaseInsensitiveAllWords() {
        assertTrue(DateQueryParser.nameMatches("Holiday_Video_GOA.mp4", "video goa"))
        assertFalse(DateQueryParser.nameMatches("Holiday_Video.mp4", "video goa"))
        assertTrue(DateQueryParser.nameMatches("anything", "  "))
    }
}
