package com.theundefined.omnis.ui

import com.theundefined.omnis.data.model.Loan
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReturnEventTest {
    private val labels =
        ReturnEventLabels(
            single = { "Zwrot: $it" },
            several = { "Zwrot książek ($it)" },
            line = { t, d -> "$t - $d" }
        )

    private fun loan(id: String, title: String, due: String, branch: String = "Filia 1") =
        Loan(
            id = id,
            mmsid = "m$id",
            title = title,
            author = null,
            dueDate = due,
            dueHour = "",
            loanDate = "01/10/2026",
            status = "",
            libraryName = "L",
            locationName = branch,
            subLocationName = null,
            barcode = "B$id",
            accountId = "a"
        )

    private fun utc(y: Int, m: Int, d: Int) =
        LocalDate.of(y, m, d).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    @Test
    fun singleLoan() {
        val e = buildReturnEvent(listOf(loan("1", "Dune", "20261115")), labels)!!
        assertEquals("Zwrot: Dune", e.title)
        assertEquals("Dune - 2026-11-15", e.description)
        assertEquals("Filia 1", e.location)
        assertEquals(utc(2026, 11, 15), e.beginMillis)
        assertEquals(utc(2026, 11, 16), e.endMillis)
    }

    @Test
    fun groupUsesEarliestDateAndJoinsBranches() {
        val e =
            buildReturnEvent(
                listOf(
                    loan("1", "A", "20/12/2026"),
                    loan("2", "B", "05/12/2026", "Filia 2"),
                    loan("3", "C", "10/12/2026")
                ),
                labels
            )!!
        assertEquals("Zwrot książek (3)", e.title)
        assertEquals(utc(2026, 12, 5), e.beginMillis)
        assertEquals("B - 2026-12-05\nC - 2026-12-10\nA - 2026-12-20", e.description)
        assertEquals("Filia 1; Filia 2", e.location)
    }

    @Test
    fun yearBoundaryEnd() {
        val e = buildReturnEvent(listOf(loan("1", "A", "20261231")), labels)!!
        assertEquals(utc(2027, 1, 1), e.endMillis)
    }

    @Test
    fun unreadableDatesAreSkippedForTimeButKeptInDescription() {
        val e =
            buildReturnEvent(listOf(loan("1", "A", "brak"), loan("2", "B", "20261101")), labels)!!
        assertEquals(utc(2026, 11, 1), e.beginMillis)
        assertEquals("B - 2026-11-01\nA - brak", e.description)
    }

    @Test
    fun noUsableDateOrEmpty() {
        assertNull(buildReturnEvent(listOf(loan("1", "A", "brak")), labels))
        assertNull(buildReturnEvent(emptyList(), labels))
    }
}
