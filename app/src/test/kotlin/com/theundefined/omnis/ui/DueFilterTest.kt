package com.theundefined.omnis.ui

import com.theundefined.omnis.data.model.Loan
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class DueFilterTest {
    private val today = LocalDate.of(2026, 11, 6)

    private fun loan(id: String, due: String) =
        Loan(
            id = id,
            mmsid = "m$id",
            title = "T$id",
            author = null,
            dueDate = due,
            dueHour = "",
            loanDate = "01/10/2026",
            status = "",
            libraryName = "L",
            locationName = "F",
            subLocationName = null,
            barcode = "B$id",
            renewable = true,
            accountId = "a"
        )

    private val loans =
        listOf(
            loan("1", "05/11/2026"), // po terminie
            loan("2", "09/11/2026"), // za 3 dni
            loan("3", "13/11/2026"), // za 7 dni
            loan("4", "20/11/2026"),
            loan("5", "brak")
        )

    private fun ids(f: DueFilter) = filterByDue(loans, f, today).map { it.id }

    @Test
    fun allKeepsEverything() = assertEquals(listOf("1", "2", "3", "4", "5"), ids(DueFilter.ALL))

    @Test fun overdueOnly() = assertEquals(listOf("1"), ids(DueFilter.OVERDUE))

    @Test fun withinThreeIncludesOverdue() = assertEquals(listOf("1", "2"), ids(DueFilter.WITHIN_3))

    @Test fun withinSeven() = assertEquals(listOf("1", "2", "3"), ids(DueFilter.WITHIN_7))
}
