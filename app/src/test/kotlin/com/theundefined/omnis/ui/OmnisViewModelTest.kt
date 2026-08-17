package com.theundefined.omnis.ui

import com.theundefined.omnis.data.model.Loan
import org.junit.Assert.assertEquals
import org.junit.Test

class OmnisViewModelTest {

    private fun loan(id: String, title: String) =
        Loan(
            id = id,
            mmsid = "mms$id",
            title = title,
            author = "Author",
            dueDate = "01/01/2026",
            dueHour = "12:00",
            loanDate = "01/12/2025",
            status = "Active",
            libraryName = "Library",
            locationName = "Location",
            subLocationName = null,
            barcode = "barcode$id",
            renewable = true,
            accountId = "acc1",
            ownerName = "Owner"
        )

    @Test
    fun `all successes produce zero failed titles`() {
        val results =
            listOf(
                loan("1", "Book One") to Result.success(Unit),
                loan("2", "Book Two") to Result.success(Unit)
            )

        val summary = summarizeRenewResults(results)

        assertEquals(2, summary.succeeded)
        assertEquals(emptyList<String>(), summary.failedTitles)
    }

    @Test
    fun `partial failures are counted and titles collected`() {
        val results =
            listOf(
                loan("1", "Book One") to Result.success(Unit),
                loan("2", "Book Two") to Result.failure(Exception("network error")),
                loan("3", "Book Three") to Result.failure(Exception("network error"))
            )

        val summary = summarizeRenewResults(results)

        assertEquals(1, summary.succeeded)
        assertEquals(listOf("Book Two", "Book Three"), summary.failedTitles)
    }

    @Test
    fun `empty input yields zero succeeded and no failures`() {
        val summary = summarizeRenewResults(emptyList())

        assertEquals(0, summary.succeeded)
        assertEquals(emptyList<String>(), summary.failedTitles)
    }
}
