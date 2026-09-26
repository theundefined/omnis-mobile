package com.theundefined.omnis.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IsbnUtilsTest {

    @Test
    fun `valid EAN-13 ISBN is returned unchanged`() {
        assertEquals("9788375780635", normalizeIsbn("9788375780635"))
    }

    @Test
    fun `979 prefix is accepted`() {
        assertEquals("9791032305690", normalizeIsbn("9791032305690"))
    }

    @Test
    fun `hyphens and spaces are stripped`() {
        assertEquals("9788375780635", normalizeIsbn(" 978-83-7578-063-5 "))
    }

    @Test
    fun `valid ISBN-10 is accepted`() {
        assertEquals("8375780634", normalizeIsbn("83-7578-063-4"))
    }

    @Test
    fun `ISBN-10 with X check digit is accepted in any case`() {
        assertEquals("080442957X", normalizeIsbn("0-8044-2957-x"))
    }

    @Test
    fun `X outside the check digit position is rejected`() {
        assertNull(normalizeIsbn("08044X9575"))
    }

    @Test
    fun `wrong EAN-13 checksum is rejected`() {
        assertNull(normalizeIsbn("9788375780636"))
    }

    @Test
    fun `wrong ISBN-10 checksum is rejected`() {
        assertNull(normalizeIsbn("8375780635"))
    }

    @Test
    fun `ISSN EAN with 977 prefix is rejected`() {
        assertNull(normalizeIsbn("9771234567003"))
    }

    @Test
    fun `regular product EAN is rejected`() {
        assertNull(normalizeIsbn("5901234123457"))
    }

    @Test
    fun `empty and garbage input is rejected`() {
        assertNull(normalizeIsbn(""))
        assertNull(normalizeIsbn("abc"))
    }
}
