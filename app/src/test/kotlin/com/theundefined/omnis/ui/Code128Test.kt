package com.theundefined.omnis.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Code128Test {

    private fun modules(text: String) =
        encodeCode128(text)!!.joinToString("") { if (it) "1" else "0" }

    @Test
    fun `card-like number matches reference verified with zxing decoder`() {
        // Wzorzec sprawdzony dekoderem zxing-cpp (odczytuje "AB123456" jako Code 128).
        assertEquals(
            "110100100001010001100010001011000100111001101100111001011001011100110010011101101110010011001110100101101110001100011101011",
            modules("AB123456")
        )
    }

    @Test
    fun `length is start plus 11 modules per char plus checksum and stop`() {
        // START(11) + n*11 + checksum(11) + STOP(13)
        assertEquals(11 + 8 * 11 + 11 + 13, modules("XY000001").length)
    }

    @Test
    fun `starts with bar and ends with stop pattern`() {
        val m = modules("A")
        assertTrue(m.startsWith("11010010000")) // START B
        assertTrue(m.endsWith("1100011101011")) // STOP
    }

    @Test
    fun `empty text and non-ascii characters are rejected`() {
        assertNull(encodeCode128(""))
        assertNull(encodeCode128("ŁÓDŹ1"))
        assertFalse(isCode128Encodable("ą"))
        assertTrue(isCode128Encodable("Test-42 ~}|"))
    }
}
