package com.theundefined.omnis.ui

import com.theundefined.omnis.data.model.Hold
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HoldDeadlineTest {

    private fun hold(id: String, status: String, available: Boolean = true, owner: String = "A") =
        Hold(
            id = id,
            title = "Upadek Lewiatana",
            author = null,
            status = status,
            available = available,
            cancellable = true,
            pickupLocation = "Filia 08",
            requestDate = "20261001",
            mmsid = null,
            accountId = owner,
            ownerName = owner,
            tenantName = "Łódź"
        )

    @Test
    fun parsesDayMonthYearFromStatus() {
        assertEquals(
            LocalDate.of(2026, 10, 8),
            holdDeadline(hold("1", "Na półce rezerwacji do 08/10/2026"))
        )
    }

    @Test
    fun noDeadlineWhenNotAvailableOrMissingOrInvalid() {
        assertNull(holdDeadline(hold("1", "Na półce rezerwacji do 08/10/2026", available = false)))
        assertNull(holdDeadline(hold("1", "W realizacji")))
        assertNull(holdDeadline(hold("1", "do 31/02/2026")))
    }

    @Test
    fun urgentOnLastTwoDaysAndAfter() {
        val today = LocalDate.of(2026, 10, 4)
        assertFalse(isHoldDeadlineUrgent(LocalDate.of(2026, 10, 6), today))
        assertTrue(isHoldDeadlineUrgent(LocalDate.of(2026, 10, 5), today))
        assertTrue(isHoldDeadlineUrgent(today, today))
        assertTrue(isHoldDeadlineUrgent(LocalDate.of(2026, 10, 1), today))
    }

    @Test
    fun readyHoldsSortedByDeadlineAcrossAccounts() {
        val holds =
            mapOf(
                "A" to
                    listOf(
                        hold("1", "Na półce rezerwacji do 10/10/2026"),
                        hold("2", "W realizacji", available = false)
                    ),
                "B" to
                    listOf(
                        hold("3", "Na półce", owner = "B"),
                        hold("4", "Na półce rezerwacji do 06/10/2026", owner = "B")
                    )
            )
        assertEquals(listOf("4", "1", "3"), readyHolds(holds).map { it.id })
    }

    @Test
    fun dismissedBannerReturnsForNewHoldOrUrgentDeadline() {
        val today = LocalDate.of(2026, 10, 4)
        val a = hold("1", "Na półce rezerwacji do 10/10/2026")
        val b = hold("2", "Na półce rezerwacji do 12/10/2026")
        val dismissed = listOf(a, b).map { readyHoldBannerToken(it, today) }.toSet()

        assertTrue(isReadyHoldsBannerVisible(listOf(a, b), emptySet(), today))
        assertFalse(isReadyHoldsBannerVisible(listOf(a, b), dismissed, today))
        // Odebrana rezerwacja znika z listy — reszta nadal zamknięta.
        assertFalse(isReadyHoldsBannerVisible(listOf(b), dismissed, today))
        // Nowa rezerwacja na półce.
        val c = hold("3", "Na półce rezerwacji do 14/10/2026")
        assertTrue(isReadyHoldsBannerVisible(listOf(a, b, c), dismissed, today))
        // Termin a robi się pilny (zostało <= 1 dzień).
        assertTrue(isReadyHoldsBannerVisible(listOf(a, b), dismissed, LocalDate.of(2026, 10, 9)))
        assertFalse(isReadyHoldsBannerVisible(emptyList(), emptySet(), today))
    }
}
