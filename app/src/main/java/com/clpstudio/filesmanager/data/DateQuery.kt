package com.clpstudio.filesmanager.data

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters
import java.util.Locale

data class DateRange(val start: LocalDate, val endInclusive: LocalDate) {
    fun contains(date: LocalDate): Boolean = !date.isBefore(start) && !date.isAfter(endInclusive)

    companion object {
        fun day(d: LocalDate) = DateRange(d, d)
        fun month(ym: YearMonth) = DateRange(ym.atDay(1), ym.atEndOfMonth())
        fun year(y: Int) = DateRange(LocalDate.of(y, 1, 1), LocalDate.of(y, 12, 31))
    }
}

enum class QuickDate(val label: String) { ANY("Any time"), TODAY("Today"), YESTERDAY("Yesterday"), THIS_WEEK("This week"), THIS_MONTH("This month"), CUSTOM("Custom range") }

/**
 * Reads a date out of what the user typed in the search bar.
 *
 * Understood: 31/10/2026, 31-10-2026, 31.10.2026, 31/10/26, 2026-10-31, 2026/10/31,
 * 31 Oct 2026, 31 October 2026, Oct 31 2026, Oct 31, 2026, 31 Oct, Oct 2026, October 2026,
 * 10/2026, 2026-10, 2026, today, yesterday, this week, last week, this month, last month,
 * this year, last year. Day-first (Indian / European order). The rest of the text is the name.
 */
object DateQueryParser {

    data class Parsed(val nameQuery: String, val range: DateRange?, val matchedText: String? = null)

    private val months = mapOf(
        "jan" to 1, "january" to 1, "feb" to 2, "february" to 2, "mar" to 3, "march" to 3,
        "apr" to 4, "april" to 4, "may" to 5, "jun" to 6, "june" to 6, "jul" to 7, "july" to 7,
        "aug" to 8, "august" to 8, "sep" to 9, "sept" to 9, "september" to 9, "oct" to 10, "october" to 10,
        "nov" to 11, "november" to 11, "dec" to 12, "december" to 12,
    )
    private const val MON = "(jan|january|feb|february|mar|march|apr|april|may|jun|june|jul|july|aug|august|sep|sept|september|oct|october|nov|november|dec|december)"

    private fun year(s: String): Int = s.toInt().let { if (s.length == 2) 2000 + it else it }

    private fun date(y: Int, m: Int, d: Int): LocalDate? = runCatching { LocalDate.of(y, m, d) }.getOrNull()

    private class Rule(pattern: String, val build: (MatchResult, LocalDate) -> DateRange?) {
        val regex = Regex("(?<![\\p{L}\\d])$pattern(?![\\p{L}\\d])", RegexOption.IGNORE_CASE)
    }

    private val rules: List<Rule> = listOf(
        // Keywords
        Rule("today") { _, t -> DateRange.day(t) },
        Rule("yesterday") { _, t -> DateRange.day(t.minusDays(1)) },
        Rule("this\\s+week") { _, t -> weekOf(t) },
        Rule("last\\s+week") { _, t -> weekOf(t.minusWeeks(1)) },
        Rule("this\\s+month") { _, t -> DateRange.month(YearMonth.from(t)) },
        Rule("last\\s+month") { _, t -> DateRange.month(YearMonth.from(t).minusMonths(1)) },
        Rule("this\\s+year") { _, t -> DateRange.year(t.year) },
        Rule("last\\s+year") { _, t -> DateRange.year(t.year - 1) },
        // 2026-10-31, 2026/10/31, 2026.10.31
        Rule("(\\d{4})[-/.](\\d{1,2})[-/.](\\d{1,2})") { m, _ ->
            date(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())?.let { DateRange.day(it) }
        },
        // 31/10/2026, 31-10-2026, 31.10.2026, 31/10/26
        Rule("(\\d{1,2})[-/.](\\d{1,2})[-/.](\\d{4}|\\d{2})") { m, _ ->
            date(year(m.groupValues[3]), m.groupValues[2].toInt(), m.groupValues[1].toInt())?.let { DateRange.day(it) }
        },
        // 31 Oct 2026, 31st October 2026, 31-Oct-2026
        Rule("(\\d{1,2})(?:st|nd|rd|th)?[\\s-]+$MON,?[\\s-]+(\\d{4})") { m, _ ->
            date(m.groupValues[3].toInt(), months.getValue(m.groupValues[2].lowercase(Locale.ROOT)), m.groupValues[1].toInt())?.let { DateRange.day(it) }
        },
        // Oct 31 2026, Oct 31, 2026
        Rule("$MON\\s+(\\d{1,2})(?:st|nd|rd|th)?,?\\s+(\\d{4})") { m, _ ->
            date(m.groupValues[3].toInt(), months.getValue(m.groupValues[1].lowercase(Locale.ROOT)), m.groupValues[2].toInt())?.let { DateRange.day(it) }
        },
        // Oct 2026, October 2026
        Rule("$MON,?\\s+(\\d{4})") { m, _ ->
            DateRange.month(YearMonth.of(m.groupValues[2].toInt(), months.getValue(m.groupValues[1].lowercase(Locale.ROOT))))
        },
        // 31 Oct (this year)
        Rule("(\\d{1,2})(?:st|nd|rd|th)?\\s+$MON") { m, t ->
            date(t.year, months.getValue(m.groupValues[2].lowercase(Locale.ROOT)), m.groupValues[1].toInt())?.let { DateRange.day(it) }
        },
        // 2026-10, 2026/10
        Rule("(\\d{4})[-/](\\d{1,2})") { m, _ ->
            val mo = m.groupValues[2].toInt()
            if (mo in 1..12) DateRange.month(YearMonth.of(m.groupValues[1].toInt(), mo)) else null
        },
        // 10/2026, 10-2026
        Rule("(\\d{1,2})[-/](\\d{4})") { m, _ ->
            val mo = m.groupValues[1].toInt()
            if (mo in 1..12) DateRange.month(YearMonth.of(m.groupValues[2].toInt(), mo)) else null
        },
        // A month name alone: October (this year)
        Rule(MON) { m, t -> DateRange.month(YearMonth.of(t.year, months.getValue(m.groupValues[1].lowercase(Locale.ROOT)))) },
        // 2026
        Rule("(19\\d{2}|20\\d{2})") { m, _ -> DateRange.year(m.groupValues[1].toInt()) },
    )

    fun weekOf(d: LocalDate): DateRange {
        val start = d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return DateRange(start, start.plusDays(6))
    }

    fun quick(q: QuickDate, today: LocalDate): DateRange? = when (q) {
        QuickDate.ANY, QuickDate.CUSTOM -> null
        QuickDate.TODAY -> DateRange.day(today)
        QuickDate.YESTERDAY -> DateRange.day(today.minusDays(1))
        QuickDate.THIS_WEEK -> weekOf(today)
        QuickDate.THIS_MONTH -> DateRange.month(YearMonth.from(today))
    }

    fun parse(input: String, today: LocalDate = LocalDate.now()): Parsed {
        val text = input.trim()
        if (text.isEmpty()) return Parsed("", null)
        for (rule in rules) {
            for (m in rule.regex.findAll(text)) {
                val range = rule.build(m, today) ?: continue
                val rest = (text.substring(0, m.range.first) + " " + text.substring(m.range.last + 1))
                    .replace(Regex("\\s+"), " ").trim()
                return Parsed(rest, range, m.value)
            }
        }
        return Parsed(text, null)
    }

    /** Name match: every word must appear in the name (case-insensitive). */
    fun nameMatches(name: String, query: String): Boolean {
        if (query.isBlank()) return true
        val lower = name.lowercase(Locale.ROOT)
        return query.lowercase(Locale.ROOT).split(' ').filter { it.isNotBlank() }.all { lower.contains(it) }
    }
}
