package com.clpstudio.filesmanager

import com.clpstudio.filesmanager.data.DateGrouping
import com.clpstudio.filesmanager.data.DateSections
import com.clpstudio.filesmanager.data.FileItem
import com.clpstudio.filesmanager.data.SelectionLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class GroupingTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private fun at(y: Int, m: Int, d: Int, h: Int = 12) = Calendar.getInstance(utc).apply { clear(); set(y, m, d, h, 0, 0) }.timeInMillis
    private fun f(name: String, ms: Long) = FileItem("/s/$name", name, false, 1, ms)

    @Test fun allFiveModes() {
        val t = at(2026, Calendar.OCTOBER, 31) // a Saturday
        assertEquals("31 Oct 2026", DateSections.label(t, DateGrouping.DAY, utc))
        assertEquals("Week of 26 Oct 2026", DateSections.label(t, DateGrouping.WEEK, utc))
        assertEquals("October 2026", DateSections.label(t, DateGrouping.MONTH, utc))
        assertEquals("Q4 2026", DateSections.label(t, DateGrouping.QUARTER, utc))
        assertEquals("2026", DateSections.label(t, DateGrouping.YEAR, utc))
        assertEquals("", DateSections.label(t, DateGrouping.NONE, utc))
    }

    @Test fun weeksStartOnMondayAndCrossMonthsAndYears() {
        assertEquals("Week of 26 Oct 2026", DateSections.label(at(2026, Calendar.OCTOBER, 26, 0), DateGrouping.WEEK, utc)) // Monday itself
        assertEquals("Week of 19 Oct 2026", DateSections.label(at(2026, Calendar.OCTOBER, 25, 23), DateGrouping.WEEK, utc)) // Sunday
        assertEquals("Week of 29 Dec 2025", DateSections.label(at(2026, Calendar.JANUARY, 2), DateGrouping.WEEK, utc))
        assertEquals("Q1 2026", DateSections.label(at(2026, Calendar.MARCH, 31), DateGrouping.QUARTER, utc))
        assertEquals("Q2 2026", DateSections.label(at(2026, Calendar.APRIL, 1), DateGrouping.QUARTER, utc))
    }

    @Test fun sameWordsInAnyLocale() {
        val old = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-EG"))
            assertEquals("October 2026", DateSections.label(at(2026, Calendar.OCTOBER, 3), DateGrouping.MONTH, utc))
            Locale.setDefault(Locale.US) // Sunday-first locale: weeks still start on Monday
            assertEquals("Week of 26 Oct 2026", DateSections.label(at(2026, Calendar.NOVEMBER, 1), DateGrouping.WEEK, utc))
        } finally { Locale.setDefault(old) }
    }

    @Test fun groupsFollowListOrder() {
        val items = listOf(f("a", at(2026, 9, 30)), f("b", at(2026, 9, 2)), f("c", at(2026, 8, 15)), f("d", at(2025, 11, 1)))
        assertEquals(listOf("October 2026" to 2, "September 2026" to 1, "December 2025" to 1), DateSections.group(items, DateGrouping.MONTH, utc).map { it.first to it.second.size })
        assertEquals(listOf("2026" to 3, "2025" to 1), DateSections.group(items, DateGrouping.YEAR, utc).map { it.first to it.second.size })
        assertEquals(listOf("" to 4), DateSections.group(items, DateGrouping.NONE, utc).map { it.first to it.second.size })
        assertEquals(DateGrouping.MONTH, DateGrouping.parse(null))
    }

    @Test fun selectAllIsAToggle() {
        val all = listOf("a", "b", "c")
        assertEquals(all, SelectionLogic.toggleAll(listOf("a"), all)) // partial → all
        assertEquals(all, SelectionLogic.toggleAll(emptyList(), all))
        assertEquals(emptyList<String>(), SelectionLogic.toggleAll(all, all)) // all → none
        assertTrue(SelectionLogic.allSelected(listOf("c", "b", "a"), all))
        assertFalse(SelectionLogic.allSelected(emptyList(), emptyList()))
    }

    @Test fun groupCheckboxSelectsAndUnselectsItsGroup() {
        val oct = listOf("o1", "o2"); val sep = listOf("s1")
        assertEquals(SelectionLogic.GroupState.NONE, SelectionLogic.groupState(listOf("s1"), oct))
        assertEquals(SelectionLogic.GroupState.SOME, SelectionLogic.groupState(listOf("o1"), oct))
        val sel = SelectionLogic.toggleGroup(listOf("s1", "o1"), oct)
        assertEquals(listOf("s1", "o1", "o2"), sel)
        assertEquals(SelectionLogic.GroupState.ALL, SelectionLogic.groupState(sel, oct))
        assertEquals(listOf("s1"), SelectionLogic.toggleGroup(sel, oct))
        assertEquals(SelectionLogic.GroupState.ALL, SelectionLogic.groupState(SelectionLogic.toggleGroup(sel, oct), sep))
    }
}
