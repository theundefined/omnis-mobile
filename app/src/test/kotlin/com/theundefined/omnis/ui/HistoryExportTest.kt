package com.theundefined.omnis.ui

import com.theundefined.omnis.data.model.Loan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryExportTest {
    private val header = HistoryCsvHeader("T", "A", "LD", "DD", "RD", "Lib", "Br", "P", "BC")

    private fun loan(
        title: String,
        author: String? = null,
        loanDate: String = "01/10/2026",
        returnDate: String? = "20261020"
    ) =
        Loan(
            id = title,
            mmsid = "m",
            title = title,
            author = author,
            dueDate = "20261101",
            dueHour = "",
            loanDate = loanDate,
            status = "",
            libraryName = "ILS",
            locationName = "Filia",
            subLocationName = null,
            barcode = "123",
            ownerName = "Ania",
            tenantName = "Biblioteka",
            returnDate = returnDate
        )

    @Test
    fun bomHeaderAndCrlf() {
        val csv = historyToCsv(emptyList(), header)
        assertEquals("\uFEFFT,A,LD,DD,RD,Lib,Br,P,BC\r\n", csv)
    }

    @Test
    fun rowWithIsoDatesAndTenantName() {
        val csv = historyToCsv(listOf(loan("Dune", "Herbert")), header)
        assertTrue(
            csv.endsWith(
                "Dune,Herbert,2026-10-01,2026-11-01,2026-10-20,Biblioteka,Filia,Ania,123\r\n"
            )
        )
    }

    @Test
    fun quotesCommasAndNewlinesAreEscaped() {
        val csv =
            historyToCsv(listOf(loan("Ala, \"kot\"\nI pies", null, returnDate = null)), header)
        assertTrue(csv.contains("\"Ala, \"\"kot\"\"\nI pies\",,2026-10-01,2026-11-01,,"))
    }

    @Test
    fun formulaLikeCellsGetApostrophePrefix() {
        val csv =
            historyToCsv(
                listOf(
                    loan("=HYPERLINK(\"http://x\")", "+1", returnDate = null),
                    loan("-2", "@SUM(A1)"),
                    loan("\tTab", "\rCR"),
                    loan("Zwykły", "Jan - Kowalski")
                ),
                header
            )
        assertTrue(csv.contains("\"'=HYPERLINK(\"\"http://x\"\")\",'+1,"))
        assertTrue(csv.contains("'-2,'@SUM(A1),"))
        assertTrue(csv.contains("'\tTab,\"'\rCR\","))
        // Środek tekstu i zwykłe pola bez zmian.
        assertTrue(csv.contains("Zwykły,Jan - Kowalski,"))
        assertEquals("'=1", csvField("=1"))
        assertEquals("abc", csvField("abc"))
    }

    @Test
    fun unreadableDateStaysRaw() {
        val csv = historyToCsv(listOf(loan("X", loanDate = "kiedyś")), header)
        assertTrue(csv.contains("X,,kiedyś,"))
    }

    @Test
    fun sortNewestFirstUndatedLast() {
        val sorted =
            sortForExport(
                listOf(
                    loan("old", loanDate = "01/01/2020"),
                    loan("bad", loanDate = "?"),
                    loan("new", loanDate = "01/01/2026")
                )
            )
        assertEquals(listOf("new", "old", "bad"), sorted.map { it.title })
    }
}
