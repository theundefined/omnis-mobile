package com.theundefined.omnis.ui

import com.theundefined.omnis.data.model.Hold
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

// Primo (lang=pl) podaje termin odbioru tylko w tekście statusu: "Na półce rezerwacji do
// 08/10/2026" — dd/MM/yyyy, jak inne daty z tego API (parseFlexibleDate).
private val STATUS_DATE = Regex("""\b\d{2}/\d{2}/\d{4}\b""")
private val STATUS_DATE_FORMAT =
    DateTimeFormatter.ofPattern("dd/MM/uuuu").withResolverStyle(ResolverStyle.STRICT)

/** Fragment statusu z datą — do podmiany na format aplikacji. */
fun holdStatusDateMatch(status: String): MatchResult? = STATUS_DATE.find(status)

/** Termin odbioru rezerwacji czekającej na półce; null, gdy status go nie podaje. */
fun holdDeadline(hold: Hold): LocalDate? {
    if (!hold.available) return null
    val match = holdStatusDateMatch(hold.status) ?: return null
    return runCatching { LocalDate.parse(match.value, STATUS_DATE_FORMAT) }.getOrNull()
}

/** Ostatni dzień albo przedostatni (lub już po terminie) — trzeba się pospieszyć z odbiorem. */
fun isHoldDeadlineUrgent(deadline: LocalDate, today: LocalDate = LocalDate.now()): Boolean =
    !deadline.isAfter(today.plusDays(1))

/**
 * Rezerwacje gotowe do odbioru ze wszystkich kont, od najbliższego terminu (bez terminu na końcu).
 */
fun readyHolds(holds: Map<String, List<Hold>>): List<Hold> =
    holds.values
        .flatten()
        .filter { it.available }
        .sortedWith(compareBy(nullsLast()) { holdDeadline(it) })

/** requestid jest nadawane per instytucja — przy wielu kontach unikalna jest dopiero para. */
fun holdKey(hold: Hold): String = "${hold.accountId}:${hold.id}"

/**
 * Token zamknięcia banera "do odbioru" dla jednej rezerwacji. Zmienia się, gdy termin odbioru robi
 * się pilny, więc baner zamknięty wcześniej wraca na ostatnie dni.
 */
fun readyHoldBannerToken(hold: Hold, today: LocalDate = LocalDate.now()): String {
    val urgent = holdDeadline(hold)?.let { isHoldDeadlineUrgent(it, today) } == true
    return if (urgent) "${holdKey(hold)}:urgent" else holdKey(hold)
}

/** Baner widać, dopóki choć jednej z gotowych rezerwacji (w bieżącym stanie) nie zamknięto. */
fun isReadyHoldsBannerVisible(
    ready: List<Hold>,
    dismissed: Set<String>,
    today: LocalDate = LocalDate.now()
): Boolean = ready.any { readyHoldBannerToken(it, today) !in dismissed }
