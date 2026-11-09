package com.theundefined.omnis.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SharedTextTest {

    @Test
    fun `plain title is returned trimmed`() {
        assertEquals("Pan Tadeusz", queryFromSharedText("  Pan Tadeusz \n"))
    }

    @Test
    fun `isbn in text wins over the rest`() {
        assertEquals(
            "9788375780635",
            queryFromSharedText("Polecam tę książkę!\nISBN 978-83-7578-063-5\nhttps://example.com")
        )
    }

    @Test
    fun `isbn-10 with hyphens is found`() {
        assertEquals("8375780634", queryFromSharedText("ISBN: 83-7578-063-4"))
    }

    @Test
    fun `digits that are not an isbn do not become one`() {
        assertEquals("tel 1234567890123", queryFromSharedText("tel 1234567890123"))
    }

    @Test
    fun `urls are dropped and first non-empty line is used`() {
        assertEquals(
            "Wiedźmin",
            queryFromSharedText("https://example.com/a?b=c\n\nWiedźmin https://x.pl\nDruga linia")
        )
    }

    @Test
    fun `only a url gives null`() {
        assertNull(queryFromSharedText("https://example.com/książka"))
        assertNull(queryFromSharedText("www.example.com"))
    }

    @Test
    fun `blank and null give null`() {
        assertNull(queryFromSharedText(null))
        assertNull(queryFromSharedText(""))
        assertNull(queryFromSharedText("  \n\t "))
    }

    @Test
    fun `long text is truncated to 120 characters`() {
        val result = queryFromSharedText("a".repeat(500))!!
        assertEquals(120, result.length)
    }

    @Test
    fun `control characters and repeated whitespace are collapsed`() {
        assertEquals("Ala ma kota", queryFromSharedText("Ala\tma \u0007  kota"))
    }
}
