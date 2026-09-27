package com.theundefined.omnis.ui

import com.theundefined.omnis.data.model.Loan
import org.junit.Assert.assertEquals
import org.junit.Test

class LoanGroupingTest {

    private fun loan(
        id: String,
        owner: String,
        dueDate: String,
        tenant: String? = "Lib",
        location: String = "Location"
    ) =
        Loan(
            id = id,
            mmsid = "mms$id",
            title = "Title $id",
            author = "Author",
            dueDate = dueDate,
            dueHour = "12:00",
            loanDate = "01/12/2025",
            status = "Active",
            libraryName = "Sprawdź dostępność w innych bibliotekach",
            locationName = location,
            subLocationName = null,
            barcode = "barcode$id",
            renewable = true,
            accountId = "acc-$owner",
            ownerName = owner,
            tenantName = tenant
        )

    private val loans =
        listOf(
            loan("a1", "Anna", "20/10/2026"),
            loan("b1", "Bartek", "05/10/2026", tenant = "Other"),
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
    fun `branch grouping prefixes library only when loans span several libraries`() {
        val result = groupAndSortLoans(loans, GroupingMode.BRANCH, SortMode.DUE_DATE, "All")

        assertEquals(setOf("Lib - Location", "Other - Location"), result.keys)
        assertEquals(listOf("a2", "a1", "b2"), result.getValue("Lib - Location").map { it.id })
    }

    @Test
    fun `branch grouping of a single library uses bare location name`() {
        val single =
            listOf(
                loan("a1", "Anna", "20/10/2026", location = "Filia 35"),
                loan("a2", "Anna", "15/10/2026", location = "Filia 01"),
                // Wypożyczenie z cache'u sprzed dodania tenantName — nie wymusza prefiksu.
                loan("b1", "Bartek", "05/10/2026", tenant = null, location = "Filia 35")
            )

        val result = groupAndSortLoans(single, GroupingMode.BRANCH, SortMode.DUE_DATE, "All")

        assertEquals(setOf("Filia 35", "Filia 01"), result.keys)
        assertEquals(listOf("b1", "a1"), result.getValue("Filia 35").map { it.id })
    }
}
