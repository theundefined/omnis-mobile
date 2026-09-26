package com.theundefined.omnis.ui

import com.theundefined.omnis.data.model.Loan
import com.theundefined.omnis.ui.components.parseFlexibleDate

enum class GroupingMode {
    ACCOUNT,
    BRANCH,
    // Jedna płaska lista ze wszystkich kont i bibliotek — sortowanie działa wtedy globalnie, a nie
    // tylko w obrębie grupy (np. "co oddać najpierw", niezależnie od tego, czyje to konto).
    NONE
}

enum class SortMode {
    DUE_DATE,
    LOAN_DATE,
    TITLE
}

/**
 * Czysta funkcja (bez Context) — wydzielona z `OmnisViewModel`, żeby dało się ją pokryć testem
 * jednostkowym. [allGroupLabel] to nagłówek jedynej grupy w trybie [GroupingMode.NONE]
 * (lokalizowany, więc przekazywany z zewnątrz).
 */
fun groupAndSortLoans(
    loans: List<Loan>,
    groupingMode: GroupingMode,
    sortMode: SortMode,
    allGroupLabel: String
): Map<String, List<Loan>> {
    val grouped =
        when (groupingMode) {
            // ownerName jest ustawiane w OmnisRepository jako account.displayName ?:
            // account.username, więc grupowanie po nim jest równoważne grupowaniu po Account.
            GroupingMode.ACCOUNT -> loans.groupBy { it.ownerName ?: "?" }
            GroupingMode.BRANCH -> loans.groupBy { it.libraryName + " - " + it.locationName }
            GroupingMode.NONE -> if (loans.isEmpty()) emptyMap() else mapOf(allGroupLabel to loans)
        }
    return grouped.mapValues { entry -> sortLoans(entry.value, sortMode) }
}

fun sortLoans(loans: List<Loan>, sortMode: SortMode): List<Loan> {
    // Daty z API są tekstowe (dd/MM/yyyy) — sortowanie leksykograficzne po Stringu porównywałoby
    // de facto tylko dzień miesiąca, ignorując rok. Parsujemy więc raz na element do LocalDate
    // (nie w każdym porównaniu); wpisy z datą, której nie da się sparsować, lądują na końcu.
    fun byDate(selector: (Loan) -> String, ascending: Boolean): List<Loan> {
        val withKeys = loans.map { it to parseFlexibleDate(selector(it)) }
        val (withDate, withoutDate) = withKeys.partition { it.second != null }
        val sorted =
            if (ascending) withDate.sortedBy { it.second }
            else withDate.sortedByDescending { it.second }
        return sorted.map { it.first } + withoutDate.map { it.first }
    }

    return when (sortMode) {
        SortMode.DUE_DATE -> byDate({ it.dueDate }, ascending = true)
        SortMode.LOAN_DATE -> byDate({ it.loanDate }, ascending = false)
        SortMode.TITLE -> loans.sortedBy { it.title.lowercase() }
    }
}
