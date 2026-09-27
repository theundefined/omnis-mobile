package com.theundefined.omnis.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BranchInfoTest {

    // Wartości subLocation/stackMapUrl z żywych odpowiedzi Primo różnych bibliotek.
    @Test
    fun `address detection accepts street-like subLocations`() {
        assertTrue(looksLikeAddress("ul. Druskienicka 32"))
        assertTrue(looksLikeAddress("os. Bolesława Chrobrego 117a"))
        assertTrue(looksLikeAddress("Archiwum Książki Dziecięcej - Aleje Marcinkowskiego 23"))
        assertTrue(looksLikeAddress("Biblioteka Traf (F71), ul. Cieszkowskiego 11a"))
        assertTrue(looksLikeAddress("al. Piłsudskiego 17"))
    }

    @Test
    fun `address detection rejects department names`() {
        assertFalse(looksLikeAddress(null))
        assertFalse(looksLikeAddress("Wypożyczalnia"))
        assertFalse(looksLikeAddress("Magazyn BUP"))
        assertFalse(looksLikeAddress("BG-Beletrystyka (Wolny Dostęp)"))
        assertFalse(looksLikeAddress("Filia nr 2 - wypożyczalnia"))
    }

    private fun holding(main: String, sub: String?, map: String?) =
        Holding(mainLocation = main, subLocation = sub, stackMapUrl = map)

    @Test
    fun `picks the holding of the loan's branch`() {
        val info =
            branchInfoFromHoldings(
                listOf(
                    holding("Filia 01", "ul. Rostworowskiego 15", "https://maps.app.goo.gl/a"),
                    holding("Filia 35", "ul. Druskienicka 32", "https://maps.app.goo.gl/b")
                ),
                "Filia 35",
                nowMillis = 42L
            )
        assertEquals(BranchInfo("ul. Druskienicka 32", "https://maps.app.goo.gl/b", 42L), info)
    }

    @Test
    fun `prefers holding with address and map when branch has several`() {
        val info =
            branchInfoFromHoldings(
                listOf(
                    holding("Filia 35", "Wypożyczalnia", ""),
                    holding("Filia 35", "ul. Druskienicka 32", "https://maps.app.goo.gl/b")
                ),
                " filia 35 ",
                nowMillis = 0L
            )
        assertEquals("ul. Druskienicka 32", info?.address)
        assertEquals("https://maps.app.goo.gl/b", info?.mapsUrl)
    }

    @Test
    fun `department name and blank map url are dropped`() {
        val info = branchInfoFromHoldings(listOf(holding("BN", "Magazyn Książek", "")), "BN", 0L)
        assertNull(info?.address)
        assertNull(info?.mapsUrl)
    }

    @Test
    fun `unknown branch gives null`() {
        assertNull(branchInfoFromHoldings(listOf(holding("Filia 01", null, null)), "Filia 35", 0L))
    }

    @Test
    fun `maps link detection`() {
        assertTrue(isMapsLink("https://maps.app.goo.gl/WYDxexnNDGpSKnZF9"))
        assertTrue(isMapsLink("https://www.google.pl/maps/place/Filia+nr+12/@54.2,16.1"))
        assertFalse(isMapsLink("https://biblioteka.lodz.pl/znajdz-filie/"))
        assertFalse(isMapsLink("not a url"))
    }

    @Test
    fun `maps search url encodes query`() {
        val query = branchMapsQuery("Biblioteka Raczyńskich (Poznań)", "Filia 35", null)
        assertEquals("Biblioteka Raczyńskich (Poznań), Filia 35", query)
        assertTrue(
            mapsSearchUrl(query).startsWith("https://www.google.com/maps/search/?api=1&query=")
        )
        assertFalse(mapsSearchUrl(query).contains(" "))
    }
}
