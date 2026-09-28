package com.theundefined.omnis.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LibraryCardNumberTest {

    private val tenant = KNOWN_TENANTS.first()

    private fun account(username: String, cardNumber: String? = null) =
        Account(
            id = "1",
            username = username,
            password = "x",
            tenant = tenant,
            cardNumber = cardNumber
        )

    @Test
    fun `login that looks like a card number is used`() {
        assertEquals("AB123456", account("AB123456").libraryCardNumber)
    }

    @Test
    fun `email and PESEL-like logins are not card numbers`() {
        assertNull(account("jan@example.com").libraryCardNumber)
        assertNull(account("90010112345").libraryCardNumber)
    }

    @Test
    fun `manual card number wins over login`() {
        assertEquals("XY000001", account("jan@example.com", "XY000001").libraryCardNumber)
    }
}
