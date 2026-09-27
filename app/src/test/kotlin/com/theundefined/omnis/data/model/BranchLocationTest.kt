package com.theundefined.omnis.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BranchLocationTest {

    // Rozwinięte (Location z maps.app.goo.gl) linki stackMapUrl Biblioteki Raczyńskich.
    private val centralUrl =
        "https://www.google.com/maps/place/Biblioteka+Raczy%C5%84skich/@52.4084422,16.9237569,753m/data=!3m2!1e3!4b1!4m6!3m5!1s0x47045b3871a9fbeb:0xe182b893202f0c03!8m2!3d52.408439!4d16.9286332!16s%2Fm%2F0268lph?entry=tts"
    private val wildeckaUrl =
        "https://www.google.pl/maps/place/Biblioteka+Raczy%C5%84skich+Filia+Wildecka%2F53+(dzieci%C4%99ca)/@52.3865256,16.9102479,946m/data=!3m2!1e3!4b1!4m6!3m5!1s0x47045b34e31dc5a7:0xc2dc0ffa1253ce75!8m2!3d52.3865224!4d16.9128228!16s%2Fg%2F11q1dqn0nb?entry=tts"

    @Test
    fun `place coordinates win over viewport center`() {
        assertEquals(52.408439 to 16.9286332, coordinatesFromMapsUrl(centralUrl))
        assertEquals(52.3865224 to 16.9128228, coordinatesFromMapsUrl(wildeckaUrl))
    }

    @Test
    fun `viewport and query coordinates are fallbacks`() {
        assertEquals(
            54.2 to 16.1,
            coordinatesFromMapsUrl("https://www.google.pl/maps/place/Filia+nr+12/@54.2,16.1")
        )
        assertEquals(
            52.1 to 21.05,
            coordinatesFromMapsUrl("https://www.google.com/maps/search/?api=1&query=52.1,21.05")
        )
    }

    @Test
    fun `short links and non-map pages have no coordinates`() {
        assertNull(coordinatesFromMapsUrl("https://maps.app.goo.gl/9MhsmJxoudbH7diz8"))
        assertNull(coordinatesFromMapsUrl("https://biblioteka.lodz.pl/znajdz-filie/"))
        assertNull(coordinatesFromMapsUrl("https://www.google.com/maps/@123.0,16.0"))
    }

    @Test
    fun `city comes from parentheses in tenant name`() {
        assertEquals("Poznań", cityFromTenantName("Biblioteka Raczyńskich (Poznań)"))
        assertNull(cityFromTenantName("Biblioteka Narodowa"))
        assertNull(cityFromTenantName(null))
    }

    @Test
    fun `request keyed by maps link when present`() {
        val r =
            branchLocationRequest(
                "Biblioteka Raczyńskich (Poznań)",
                "Filia 04",
                "ul. Lodowa 4",
                " https://maps.app.goo.gl/H7ukfX2zANGWpgtw6 "
            )
        assertEquals("https://maps.app.goo.gl/H7ukfX2zANGWpgtw6", r.key)
        assertEquals("https://maps.app.goo.gl/H7ukfX2zANGWpgtw6", r.mapsUrl)
        assertEquals(
            listOf(
                "ul. Lodowa 4, Poznań",
                "Biblioteka Raczyńskich (Poznań), Filia 04, ul. Lodowa 4"
            ),
            r.geocodeQueries
        )
    }

    @Test
    fun `request without maps link falls back to geocoder query key`() {
        val r =
            branchLocationRequest(
                "Biblioteka Narodowa",
                "Czytelnia",
                null,
                "https://biblioteka.lodz.pl/znajdz-filie/"
            )
        assertNull(r.mapsUrl)
        assertEquals(listOf("Biblioteka Narodowa, Czytelnia"), r.geocodeQueries)
        assertEquals("q:Biblioteka Narodowa, Czytelnia", r.key)
    }
}
