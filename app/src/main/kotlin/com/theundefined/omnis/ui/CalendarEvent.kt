package com.theundefined.omnis.ui

import com.theundefined.omnis.data.model.Loan
import com.theundefined.omnis.data.model.displayTitle
import com.theundefined.omnis.data.model.parseFlexibleDate
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Dane wydarzenia całodniowego do `Intent.ACTION_INSERT` (CalendarContract.Events). Dla wydarzeń
 * całodniowych kalendarz oczekuje północy UTC dnia początku, a koniec jest wyłączny (północ UTC
 * następnego dnia) — czas lokalny przesunąłby wydarzenie na inny dzień w strefach na zachód od UTC.
 */
data class ReturnEventData(
    val title: String,
    val description: String,
    val location: String,
    val beginMillis: Long,
    val endMillis: Long
)

/** Teksty wydarzenia — lokalizowane, więc przekazywane z zewnątrz (funkcja zostaje czysta). */
data class ReturnEventLabels(
    val single: (title: String) -> String,
    val several: (count: Int) -> String,
    val line: (title: String, date: String) -> String
)

/**
 * Wydarzenie „zwrot" dla jednego lub kilku wypożyczeń. Data = najwcześniejszy termin (nieczytelne
 * daty są pomijane); `null`, gdy żadnej nie da się odczytać albo lista jest pusta. Opis wymienia
 * każdą książkę z jej terminem, miejsce to filia (kilka różnych filii łączymy średnikiem).
 */
fun buildReturnEvent(loans: List<Loan>, labels: ReturnEventLabels): ReturnEventData? {
    if (loans.isEmpty()) return null
    val dated =
        loans
            .mapNotNull { loan -> parseFlexibleDate(loan.dueDate)?.let { loan to it } }
            .sortedBy { it.second }
    val earliest = dated.firstOrNull()?.second ?: return null
    val withLibrary = spansSeveralLibraries(loans)
    val title =
        if (loans.size == 1) labels.single(displayTitle(loans[0].title))
        else labels.several(loans.size)
    // Pozycje z nieczytelnym terminem też trafiają do opisu (bez daty), żeby nic nie zniknęło.
    val lines =
        dated.map { (loan, date) -> labels.line(displayTitle(loan.title), date.toString()) } +
            loans
                .filter { parseFlexibleDate(it.dueDate) == null }
                .map { labels.line(displayTitle(it.title), it.dueDate) }
    val location =
        loans
            .map { branchLabel(it, withLibrary) }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString("; ")
    return ReturnEventData(
        title = title,
        description = lines.joinToString("\n"),
        location = location,
        beginMillis = earliest.utcMidnightMillis(),
        endMillis = earliest.plusDays(1).utcMidnightMillis()
    )
}

private fun LocalDate.utcMidnightMillis(): Long =
    atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
