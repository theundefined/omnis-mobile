package com.theundefined.omnis.ui

import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

/** Teksty podsumowania — lokalizowane (w tym liczba mnoga dni), więc przekazywane z zewnątrz. */
data class StatsSummaryLabels(
    val heading: (year: Int?) -> String,
    val total: (count: Int) -> String,
    val vsPrevious: (delta: String, previousYear: Int) -> String,
    val averagePerMonth: (average: String) -> String,
    val bestMonth: (month: String, count: Int) -> String,
    val topAuthors: (authors: String) -> String,
    val averageDuration: (days: Double) -> String,
    val footer: String
)

private const val SUMMARY_AUTHORS = 3

fun formatDelta(delta: Int): String =
    when {
        delta > 0 -> "+$delta"
        delta < 0 -> "−${-delta}"
        else -> "0"
    }

fun formatYearMonth(yearMonth: YearMonth, locale: Locale): String =
    "${yearMonth.month.getDisplayName(TextStyle.FULL_STANDALONE, locale)} ${yearMonth.year}"

/**
 * Tekst do udostępnienia: podsumowanie wybranego roku ([year] == null = cały okres) z tych samych
 * liczb, które widać na ekranie statystyk. Linie bez danych (brak autora, brak zwrotów) pomijamy,
 * zamiast pokazywać puste wartości.
 */
fun buildStatsSummary(
    stats: ReadingStats,
    year: Int?,
    labels: StatsSummaryLabels,
    locale: Locale
): String {
    val lines = mutableListOf(labels.heading(year), "")
    val previous = stats.previousYearTotal
    val total = labels.total(stats.scopeTotal)
    lines +=
        if (year != null && previous != null && (previous > 0 || stats.scopeTotal > 0))
            total +
                " (" +
                labels.vsPrevious(formatDelta(stats.scopeTotal - previous), year - 1) +
                ")"
        else total
    lines += labels.averagePerMonth(String.format(locale, "%.1f", stats.averagePerMonth))
    stats.bestMonth?.let { (month, count) ->
        lines += labels.bestMonth(formatYearMonth(month, locale), count)
    }
    val authors = stats.topAuthors.filter { it.label != null }.take(SUMMARY_AUTHORS)
    if (authors.isNotEmpty()) {
        lines += labels.topAuthors(authors.joinToString(", ") { "${it.label} (${it.count})" })
    }
    stats.duration?.let { lines += labels.averageDuration(it.averageDays) }
    lines += ""
    lines += labels.footer
    return lines.joinToString("\n")
}
