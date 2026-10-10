package com.theundefined.omnis.ui

import com.theundefined.omnis.data.model.Loan
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReadingStatsTest {

    private fun loan(
        id: String,
        account: String,
        loanDate: String,
        returnDate: String? = null,
        author: String? = "Kowalski, Jan",
        category: String? = "Książka",
        location: String = "Filia 1",
        title: String = "Title $id"
    ) =
        Loan(
            id = id,
            mmsid = "mms$id",
            title = title,
            author = author,
            dueDate = "01/01/2027",
            dueHour = "12:00",
            loanDate = loanDate,
            status = "Returned",
            libraryName = "Sprawdź dostępność w innych bibliotekach",
            locationName = location,
            subLocationName = null,
            barcode = "barcode$id",
            accountId = account,
            ownerName = account,
            tenantName = "Lib",
            itemCategoryName = category,
            returnDate = returnDate
        )

    private val today = LocalDate.of(2026, 10, 10)

    private val loans =
        listOf(
            loan("1", "anna", "15/01/2025", returnDate = "20250205"),
            loan("2", "anna", "20/03/2026", returnDate = "20260330"),
            loan("3", "anna", "21/03/2026", returnDate = "20260401"),
            loan("4", "bartek", "05/03/2026", author = "Nowak, Ewa (1970- ).", category = null),
            loan("5", "bartek", "01/10/2026", location = "Filia 2"),
            loan("6", "bartek", "not a date")
        )

    @Test
    fun `year scope buckets loans by month and account`() {
        val stats = computeReadingStats(loans, null, 2026, today)

        assertEquals(4, stats.scopeTotal)
        assertEquals(1, stats.previousYearTotal)
        assertEquals(5, stats.allTimeTotal)
        assertEquals(4, stats.thisYearTotal)
        assertEquals(12, stats.months.size)
        assertEquals(mapOf("anna" to 2, "bartek" to 1), stats.months[2].perAccount)
        assertEquals(1, stats.months[9].total)
        assertEquals(YearMonth.of(2026, 3) to 3, stats.bestMonth)
        // styczeń-październik 2026
        assertEquals(0.4, stats.averagePerMonth, 1e-9)
        assertEquals(listOf(2026, 2025), stats.availableYears)
    }

    @Test
    fun `no previous year comparison before the first loan`() {
        assertNull(computeReadingStats(loans, null, 2025, today).previousYearTotal)
    }

    @Test
    fun `all years scope averages from first loan month`() {
        val stats = computeReadingStats(loans, null, null, today)

        assertEquals(5, stats.scopeTotal)
        assertNull(stats.previousYearTotal)
        assertEquals(emptyList<MonthBucket>(), stats.months)
        assertEquals(listOf(2025, 2026), stats.years.map { it.year })
        assertEquals(mapOf("anna" to 2, "bartek" to 2), stats.years[1].perAccount)
        // styczeń 2025 - październik 2026 = 22 miesiące
        assertEquals(5.0 / 22, stats.averagePerMonth, 1e-9)
    }

    @Test
    fun `account filter limits every statistic`() {
        val stats = computeReadingStats(loans, setOf("bartek"), null, today)

        assertEquals(2, stats.allTimeTotal)
        assertEquals(listOf(2026), stats.availableYears)
        assertEquals(
            listOf(CountEntry("Kowalski, Jan", 1), CountEntry("Nowak, Ewa", 1)),
            stats.topAuthors
        )
        assertEquals(listOf(CountEntry("Książka", 1), CountEntry(null, 1)), stats.categories)
        assertNull(stats.duration)
    }

    @Test
    fun `libraries combine tenant and branch`() {
        val stats = computeReadingStats(loans, null, null, today)

        assertEquals(
            listOf(CountEntry("Lib · Filia 1", 4), CountEntry("Lib · Filia 2", 1)),
            stats.libraries
        )
    }

    @Test
    fun `duration uses only returned loans`() {
        val stats = computeReadingStats(loans, null, null, today)
        val duration = stats.duration!!

        assertEquals(3, duration.sampleSize)
        assertEquals((21 + 10 + 11) / 3.0, duration.averageDays, 1e-9)
        assertEquals(11.0, duration.medianDays, 1e-9)
        assertEquals(21L, duration.longestDays)
        assertEquals("Title 1", duration.longestTitle)
    }

    @Test
    fun `authors are normalized and grouped case-insensitively`() {
        assertEquals("Tokarczuk, Olga", normalizeAuthor("Tokarczuk, Olga (1962- ). Autor"))
        assertEquals("Tokarczuk, Olga", normalizeAuthor("Tokarczuk, Olga, 1962-"))
        assertEquals("Mróz, Remigiusz", normalizeAuthor("Mróz, Remigiusz. Autor"))
        assertEquals("Lem, Stanisław", normalizeAuthor("Lem, Stanisław (1921-2006); Kowalski"))
        assertNull(normalizeAuthor(" . "))
        assertNull(normalizeAuthor(null))

        val stats =
            computeReadingStats(
                listOf(
                    loan("a", "x", "01/01/2026", author = "Lem, Stanisław."),
                    loan("b", "x", "01/01/2026", author = "LEM, STANISŁAW (1921-2006)"),
                    loan("c", "x", "01/01/2026", author = "Lem, Stanisław")
                ),
                null,
                null,
                today
            )
        assertEquals(listOf(CountEntry("Lem, Stanisław", 3)), stats.topAuthors)
    }

    @Test
    fun `merge prefers history and drops duplicates`() {
        val returned = loan("1", "anna", "01/01/2026", returnDate = "20260110")
        val active = loan("1", "anna", "01/01/2026")
        val other = loan("2", "anna", "02/01/2026")

        val merged = mergeStatsLoans(listOf(returned), listOf(active, other))

        assertEquals(listOf("1", "2"), merged.map { it.id })
        assertEquals("20260110", merged.first().returnDate)
    }

    @Test
    fun `empty input yields empty stats`() {
        val stats = computeReadingStats(emptyList(), null, 2026, today)

        assertEquals(0, stats.scopeTotal)
        assertEquals(0.0, stats.averagePerMonth, 1e-9)
        assertNull(stats.bestMonth)
        assertNull(stats.duration)
        assertEquals(12, stats.months.size)
    }
}
