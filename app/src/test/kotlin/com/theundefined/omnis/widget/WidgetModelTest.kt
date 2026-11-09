package com.theundefined.omnis.widget

import com.theundefined.omnis.data.model.Loan
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WidgetModelTest {
    private val today = LocalDate.of(2026, 11, 9)

    private fun loan(title: String, due: String) =
        Loan(
            id = title,
            mmsid = "1",
            title = title,
            author = null,
            dueDate = due,
            dueHour = "00:00",
            loanDate = "20260101",
            status = "",
            libraryName = "",
            locationName = "",
            subLocationName = null,
            barcode = ""
        )

    @Test
    fun `empty list gives empty model`() {
        val m = buildWidgetModel(emptyList(), today)
        assertEquals(0, m.count)
        assertEquals(0, m.overdueCount)
        assertNull(m.nearestDue)
        assertNull(m.nearestDays)
        assertEquals(emptyList<WidgetItem>(), m.items)
    }

    @Test
    fun `sorts by due date with overdue first and levels as in app`() {
        val m =
            buildWidgetModel(
                listOf(
                    loan("C", "20261201"),
                    loan("B", "20261112"),
                    loan("A", "20261105"),
                    loan("T", "20261109")
                ),
                today
            )
        assertEquals(listOf("A", "T", "B", "C"), m.items.map { it.title })
        assertEquals(
            listOf(
                DueLevel.OVERDUE_OR_TODAY,
                DueLevel.OVERDUE_OR_TODAY,
                DueLevel.SOON,
                DueLevel.LATER
            ),
            m.items.map { it.level }
        )
        assertEquals(-4L, m.items[0].days)
        assertEquals(0L, m.items[1].days)
        assertEquals(1, m.overdueCount) // dziś nie jest "po terminie"
        assertEquals(LocalDate.of(2026, 11, 5), m.nearestDue)
        assertEquals(-4L, m.nearestDays)
    }

    @Test
    fun `due today is nearest and red`() {
        val m = buildWidgetModel(listOf(loan("T", "09/11/2026")), today)
        assertEquals(0L, m.nearestDays)
        assertEquals(DueLevel.OVERDUE_OR_TODAY, m.items[0].level)
        assertEquals(0, m.overdueCount)
    }

    @Test
    fun `seven days is soon and eight is later`() {
        val m = buildWidgetModel(listOf(loan("a", "20261116"), loan("b", "20261117")), today)
        assertEquals(listOf(DueLevel.SOON, DueLevel.LATER), m.items.map { it.level })
    }

    @Test
    fun `limit truncates list but count keeps total`() {
        val loans = (1..8).map { loan("L$it", "2026120$it") }
        val m = buildWidgetModel(loans, today, limit = 3)
        assertEquals(8, m.count)
        assertEquals(listOf("L1", "L2", "L3"), m.items.map { it.title })
    }

    @Test
    fun `unparsable dates go last, are counted and do not set nearest`() {
        val m = buildWidgetModel(listOf(loan("X", "???"), loan("A", "20261201")), today)
        assertEquals(2, m.count)
        assertEquals(listOf("A", "X"), m.items.map { it.title })
        assertEquals(DueLevel.UNKNOWN, m.items[1].level)
        assertNull(m.items[1].days)
        assertEquals(LocalDate.of(2026, 12, 1), m.nearestDue)
    }

    @Test
    fun `only unparsable dates give no nearest`() {
        val m = buildWidgetModel(listOf(loan("X", "")), today)
        assertEquals(1, m.count)
        assertNull(m.nearestDue)
    }
}
