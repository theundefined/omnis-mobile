package com.theundefined.omnis.widget

import com.theundefined.omnis.data.model.Loan
import com.theundefined.omnis.data.model.parseFlexibleDate
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Stopień pilności — te same progi co kolor terminu w aplikacji (getDueDateColor). */
enum class DueLevel {
    OVERDUE_OR_TODAY, // czerwony: dziś i po terminie
    SOON, // żółty: w ciągu 7 dni
    LATER, // zielony
    UNKNOWN // nieczytelna data
}

/** [days] = dni od dziś do terminu (ujemne = po terminie); null, gdy data nieczytelna. */
data class WidgetItem(val title: String, val due: LocalDate?, val days: Long?, val level: DueLevel)

data class WidgetModel(
    /** Wszystkie wypożyczenia, także z nieczytelną datą. */
    val count: Int,
    val overdueCount: Int,
    /** Najbliższy (najwcześniejszy) czytelny termin; null, gdy brak czytelnych dat. */
    val nearestDue: LocalDate?,
    val nearestDays: Long?,
    val items: List<WidgetItem>
)

fun dueLevel(days: Long?): DueLevel =
    when {
        days == null -> DueLevel.UNKNOWN
        days <= 0 -> DueLevel.OVERDUE_OR_TODAY
        days <= 7 -> DueLevel.SOON
        else -> DueLevel.LATER
    }

/**
 * Model widżetu z listy wypożyczeń (cache) i dzisiejszej daty — czysta funkcja, bez Androida.
 * Pozycje sortujemy po terminie (po terminie na początku), pozycje z nieczytelną datą idą na koniec
 * i nie wpływają na "najbliższy termin"; [limit] przycina listę, ale [WidgetModel.count] liczy
 * wszystko.
 */
fun buildWidgetModel(loans: List<Loan>, today: LocalDate, limit: Int = 5): WidgetModel {
    val items =
        loans
            .map { loan ->
                val due = parseFlexibleDate(loan.dueDate)
                val days = due?.let { ChronoUnit.DAYS.between(today, it) }
                WidgetItem(loan.title, due, days, dueLevel(days))
            }
            .sortedWith(
                compareBy<WidgetItem, LocalDate?>(nullsLast()) { it.due }.thenBy { it.title }
            )
    val nearest = items.firstOrNull { it.due != null }
    return WidgetModel(
        count = loans.size,
        overdueCount = items.count { it.days != null && it.days < 0 },
        nearestDue = nearest?.due,
        nearestDays = nearest?.days,
        items = items.take(limit.coerceAtLeast(0))
    )
}
