package com.theundefined.omnis.ui

import java.time.YearMonth
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class StatsSummaryTest {
    private val labels =
        StatsSummaryLabels(
            heading = { if (it == null) "Wszystko" else "Rok $it" },
            total = { "Razem: $it" },
            vsPrevious = { d, y -> "$d vs $y" },
            averagePerMonth = { "Srednio: $it" },
            bestMonth = { m, c -> "Best: $m ($c)" },
            topAuthors = { "Autorzy: $it" },
            averageDuration = { "Dni: ${"%.1f".format(Locale.US, it)}" },
            footer = "stopka"
        )

    private fun stats(
        previous: Int? = 4,
        authors: List<CountEntry> = emptyList(),
        duration: LoanDurationStats? = null
    ) =
        ReadingStats(
            availableYears = listOf(2026),
            scopeTotal = 7,
            previousYearTotal = previous,
            allTimeTotal = 11,
            thisYearTotal = 7,
            averagePerMonth = 0.6,
            bestMonth = YearMonth.of(2026, 6) to 3,
            months = emptyList(),
            years = emptyList(),
            monthsByYear = emptyMap(),
            topAuthors = authors,
            categories = emptyList(),
            libraries = emptyList(),
            duration = duration
        )

    @Test
    fun fullSummary() {
        val text =
            buildStatsSummary(
                stats(
                    authors =
                        listOf(
                            CountEntry("A", 3),
                            CountEntry(null, 2),
                            CountEntry("B", 2),
                            CountEntry("C", 1),
                            CountEntry("D", 1)
                        ),
                    duration = LoanDurationStats(5, 14.0, 14.0, 30, "x")
                ),
                2026,
                labels,
                Locale.US
            )
        assertEquals(
            "Rok 2026\n\nRazem: 7 (+3 vs 2025)\nSrednio: 0.6\nBest: June 2026 (3)\n" +
                "Autorzy: A (3), B (2), C (1)\nDni: 14.0\n\nstopka",
            text
        )
    }

    @Test
    fun allTimeOmitsComparisonAndEmptyLines() {
        val text = buildStatsSummary(stats().copy(bestMonth = null), null, labels, Locale.US)
        assertEquals("Wszystko\n\nRazem: 7\nSrednio: 0.6\n\nstopka", text)
    }

    @Test
    fun deltaFormatting() {
        assertEquals("+2", formatDelta(2))
        assertEquals("−2", formatDelta(-2))
        assertEquals("0", formatDelta(0))
    }
}
