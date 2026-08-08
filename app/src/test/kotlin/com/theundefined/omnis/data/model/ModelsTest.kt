package com.theundefined.omnis.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// Regresja dla błędu "Expected BEGIN_ARRAY but was STRING at ... path $.docs[0].pnx.control"
// (Primo czasem zwraca pojedynczą wartość jako goły string zamiast jednoelementowej tablicy).
// Parsujemy przez createPrimoGson() całe PnxsSearchResponse, nie sam typ mapy w izolacji, żeby
// test wykrył też cofnięcie GsonConverterFactory.create(gson) na create() w OmnisRepository, oraz
// cofnięcie rejestracji pnxDeserializer po Pnx::class.java na rejestrację po Type z TypeToken
// (ta druga forma nie działa dla pól Kotlinowych z powodu wildcardów z wariancji — patrz
// komentarz przy pnxDeserializer w Models.kt).
class PnxDeserializerTest {

    private val gson = createPrimoGson()

    private fun parseControl(controlJson: String): Map<String, List<String>> {
        val json = """{"docs":[{"pnx":{"control":$controlJson}}]}"""
        val response = gson.fromJson(json, PnxsSearchResponse::class.java)
        return response.docs[0].pnx.control
    }

    @Test
    fun `bare string value is wrapped as single-element list`() {
        assertEquals(
            mapOf("recordid" to listOf("ALMA123")),
            parseControl("""{"recordid":"ALMA123"}""")
        )
    }

    @Test
    fun `array value is left unchanged`() {
        assertEquals(
            mapOf("sourcerecordid" to listOf("ALMA123", "x")),
            parseControl("""{"sourcerecordid":["ALMA123","x"]}""")
        )
    }

    @Test
    fun `null entries inside an array are dropped`() {
        assertEquals(
            mapOf("recordid" to listOf("ALMA123")),
            parseControl("""{"recordid":["ALMA123",null]}""")
        )
    }

    @Test
    fun `empty array for the whole map yields an empty map`() {
        assertEquals(emptyMap<String, List<String>>(), parseControl("[]"))
    }

    @Test
    fun `control key missing from pnx yields an empty map`() {
        val response = gson.fromJson("""{"docs":[{"pnx":{}}]}""", PnxsSearchResponse::class.java)
        assertEquals(emptyMap<String, List<String>>(), response.docs[0].pnx.control)
    }

    @Test
    fun `pnx missing from doc falls back to the Kotlin default`() {
        val response = gson.fromJson("""{"docs":[{}]}""", PnxsSearchResponse::class.java)
        assertEquals(Pnx(), response.docs[0].pnx)
    }

    @Test
    fun `other pnx fields survive alongside a missing control`() {
        val json = """{"docs":[{"pnx":{"display":{"title":["Lord Jim"]}}}]}"""
        val pnx = gson.fromJson(json, PnxsSearchResponse::class.java).docs[0].pnx
        assertEquals(emptyMap<String, List<String>>(), pnx.control)
        assertEquals(mapOf("title" to listOf("Lord Jim")), pnx.display)
    }
}

class PnxHelpersTest {

    @Test
    fun `displayFirst returns the first element of the field`() {
        val pnx = Pnx(display = mapOf("title" to listOf("Lord Jim", "wydanie 2")))
        assertEquals("Lord Jim", pnx.displayFirst("title"))
    }

    @Test
    fun `displayFirst returns null for a missing field`() {
        assertNull(Pnx().displayFirst("title"))
    }

    @Test
    fun `addataFirst returns the first element of the field`() {
        val pnx = Pnx(addata = mapOf("au" to listOf("Conrad, Joseph")))
        assertEquals("Conrad, Joseph", pnx.addataFirst("au"))
    }

    @Test
    fun `frbrgroupid reads from facets`() {
        val pnx = Pnx(facets = mapOf("frbrgroupid" to listOf("123456789")))
        assertEquals("123456789", pnx.frbrgroupid())
    }

    @Test
    fun `almaId reads recordid from control`() {
        val pnx = Pnx(control = mapOf("recordid" to listOf("alma991234567890")))
        assertEquals("alma991234567890", pnx.almaId())
    }

    @Test
    fun `bareMmsid prefers sourcerecordid over recordid`() {
        val pnx =
            Pnx(
                control =
                    mapOf(
                        "sourcerecordid" to listOf("991234567890"),
                        "recordid" to listOf("alma991234567890")
                    )
            )
        assertEquals("991234567890", pnx.bareMmsid())
    }

    @Test
    fun `bareMmsid strips the alma prefix from recordid when sourcerecordid is absent`() {
        val pnx = Pnx(control = mapOf("recordid" to listOf("alma991234567890")))
        assertEquals("991234567890", pnx.bareMmsid())
    }

    @Test
    fun `bareMmsid returns recordid unchanged when it has no alma prefix`() {
        val pnx = Pnx(control = mapOf("recordid" to listOf("991234567890")))
        assertEquals("991234567890", pnx.bareMmsid())
    }

    @Test
    fun `bareMmsid returns empty string when control has neither field`() {
        assertEquals("", Pnx().bareMmsid())
    }
}
