package com.theundefined.omnis.ui

import com.theundefined.omnis.data.model.Loan
import org.junit.Assert.assertEquals
import org.junit.Test

class LoanGroupingTest {

    private fun loan(id: String, owner: String, dueDate: String, library: String = "Lib") =
        Loan(
            id = id,
            mmsid = "mms$id",
            title = "Title $id",
            author = "Author",
            dueDate = dueDate,
            dueHour = "12:00",
            loanDate = "01/12/2025",
            status = "Active",
            libraryName = library,
            locationName = "Location",
            subLocationName = null,
            barcode = "barcode$id",
            renewable = true,
            accountId = "acc-$owner",
            ownerName = owner
        )

    private val loans =
        listOf(
            loan("a1", "Anna", "20/10/2026"),
            loan("b1", "Bartek", "05/10/2026", library = "Other"),
            loan("a2", "Anna", "15/10/2026"),
            loan("b2", "Bartek", "01/11/2026")
        )

    @Test
    fun `no grouping sorts across all accounts by due date`() {
        val result = groupAndSortLoans(loans, GroupingMode.NONE, SortMode.DUE_DATE, "All")

        assertEquals(listOf("All"), result.keys.toList())
        assertEquals(listOf("b1", "a2", "a1", "b2"), result.getValue("All").map { it.id })
    }

    @Test
    fun `no grouping of empty list yields no groups`() {
        assertEquals(
            emptyMap<String, List<Loan>>(),
            groupAndSortLoans(emptyList(), GroupingMode.NONE, SortMode.DUE_DATE, "All")
        )
    }

    @Test
    fun `account grouping sorts within each group`() {
        val result = groupAndSortLoans(loans, GroupingMode.ACCOUNT, SortMode.DUE_DATE, "All")

        assertEquals(listOf("a2", "a1"), result.getValue("Anna").map { it.id })
        assertEquals(listOf("b1", "b2"), result.getValue("Bartek").map { it.id })
    }

    @Test
    fun `branch grouping keys by library and location`() {
        val result = groupAndSortLoans(loans, GroupingMode.BRANCH, SortMode.DUE_DATE, "All")

        assertEquals(setOf("Lib - Location", "Other - Location"), result.keys)
        assertEquals(listOf("a2", "a1", "b2"), result.getValue("Lib - Location").map { it.id })
    }
}
