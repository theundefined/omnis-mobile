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

// Surowe addata.seriestitle z żywego katalogu Primo (Raczyńscy, UJ, MBP) — tom i odpowiedzialność
// muszą zniknąć, bo szukanie po polu SERIES z nimi gubi tomy o innym opisie serii.
class SeriesSearchTermTest {

    @Test
    fun `strips volume in brackets and statement of responsibility`() {
        assertEquals(
            "Garstka z Ustki",
            seriesSearchTerm("Garstka z Ustki / Aneta Jadowska ; [t. 1]")
        )
    }

    @Test
    fun `strips volume after semicolon with irregular spacing`() {
        assertEquals("Heksalogia o Dorze Wilk", seriesSearchTerm("Heksalogia o Dorze Wilk ;  2"))
    }

    @Test
    fun `strips author and numeric volume`() {
        assertEquals(
            "Heksalogia o Dorze Wilk",
            seriesSearchTerm("Heksalogia o Dorze Wilk / Aneta Jadowska ; 5")
        )
    }

    @Test
    fun `strips abbreviated volume`() {
        assertEquals("Harry Potter", seriesSearchTerm("Harry Potter ; T.8"))
    }

    @Test
    fun `keeps series without volume unchanged`() {
        assertEquals("Harry Potter", seriesSearchTerm("Harry Potter"))
    }

    @Test
    fun `keeps slash without surrounding spaces`() {
        assertEquals("AC/DC", seriesSearchTerm("AC/DC ; 3"))
    }

    @Test
    fun `keeps comma inside series name`() {
        assertEquals("Fantastyka, Przygoda", seriesSearchTerm("Fantastyka, Przygoda / \"Iskry\""))
    }
}

class SeriesVolumeTest {

    @Test
    fun `reads volume in its various notations`() {
        assertEquals(1, seriesVolume("Garstka z Ustki / Aneta Jadowska ; [t. 1]"))
        assertEquals(2, seriesVolume("Heksalogia o Dorze Wilk ;  2"))
        assertEquals(8, seriesVolume("Harry Potter ; T.8"))
        assertEquals(2, seriesVolume("Harry Potter ; rok 2"))
    }

    @Test
    fun `returns null without volume after semicolon`() {
        assertNull(seriesVolume("Garstka z Ustki / Aneta Jadowska"))
        assertNull(seriesVolume("Filologia Polska / Uniwersytet, nr 239"))
    }
}

// Seria i autor wypożyczenia pochodzą z pnx rekordu (OmnisRepository.withCatalogDetails) — pnx musi
// się sparsować przez createPrimoGson(), z tym samym deserializerem co w wynikach wyszukiwania.
class RecordResponsePnxTest {

    @Test
    fun `parses series and author from record pnx`() {
        val json =
            """{"pnx":{"addata":{"seriestitle":["Garstka z Ustki / Aneta Jadowska"],"au":"Jadowska, Aneta"}},"delivery":{"holding":[]}}"""
        val pnx = createPrimoGson().fromJson(json, RecordResponse::class.java).pnx

        assertEquals("Garstka z Ustki / Aneta Jadowska", pnx?.addataFirst("seriestitle"))
        assertEquals("Jadowska, Aneta", pnx?.addataFirst("au"))
    }
}

class AuthorSearchTermTest {

    @Test
    fun `strips dates and role from authority-style name`() {
        assertEquals("Jadowska, Aneta", authorSearchTerm("Jadowska, Aneta (1981- ). Autor"))
        assertEquals("Lem, Stanisław", authorSearchTerm("Lem, Stanisław (1921-2006)"))
    }

    @Test
    fun `keeps plain name unchanged`() {
        assertEquals("Jadowska, Aneta", authorSearchTerm("Jadowska, Aneta"))
        assertEquals("J. K. Rowling", authorSearchTerm("J. K. Rowling"))
        assertEquals("Rowling, J. K.", authorSearchTerm("Rowling, J. K."))
    }
}

class DisplayTitleTest {

    @Test
    fun `strips statement of responsibility`() {
        assertEquals(
            "Pan Tadeusz, czyli Ostatni zajazd na Litwie",
            displayTitle(
                "Pan Tadeusz, czyli Ostatni zajazd na Litwie / Adam Mickiewicz ; " +
                    "opracował Stanisław Pigoń ; [ilustracje Michał Elwiro Andriolli]."
            )
        )
        assertEquals("Lalka", displayTitle("Lalka / Bolesław Prus ; posłowie Józef Bachórz."))
    }

    @Test
    fun `keeps subtitle and slash without spaces`() {
        assertEquals("Diuna : powieść", displayTitle("Diuna : powieść / Frank Herbert."))
        assertEquals("AC/DC", displayTitle("AC/DC"))
    }

    @Test
    fun `recognises regular loan status only`() {
        assert(isRegularLoanStatus("Zwykłe"))
        assert(isRegularLoanStatus("Active"))
        assert(!isRegularLoanStatus("Zagubione"))
    }
}

class RenewStatusMessagesTest {

    private fun parse(json: String) = com.google.gson.JsonParser.parseString(json)

    @Test
    fun `reads list of messages`() {
        assertEquals(
            listOf("Okres, na który można dokonać prolongaty to 7 dni przed datą zwrotu"),
            renewStatusMessages(
                parse(
                    """{"renewstatus": ["Okres, na który można dokonać prolongaty to 7 dni przed datą zwrotu"]}"""
                )
            )
        )
    }

    @Test
    fun `accepts bare string and missing values`() {
        assertEquals(listOf("Nie"), renewStatusMessages(parse("""{"renewstatus": "Nie"}""")))
        assertEquals(emptyList<String>(), renewStatusMessages(null))
        assertEquals(emptyList<String>(), renewStatusMessages(parse("""{}""")))
    }
}

// Primo po rozwinięciu grupy FRBR ignoruje `q` i zwraca wydania innych serii/autorów
// (np. "Nowa kolekcja bajek" z serią "Elena z Avaloru" ma w grupie też Tomka, Smerfy, Binga).
class PnxMatchesFieldTest {

    private fun pnx(series: String? = null, creator: String? = null, au: String? = null) =
        Pnx(
            display = if (creator != null) mapOf("creator" to listOf(creator)) else emptyMap(),
            addata =
                buildMap {
                    series?.let { put("seriestitle", listOf(it)) }
                    au?.let { put("au", listOf(it)) }
                }
        )

    @Test
    fun `series matches own series and rejects other series in the same group`() {
        assertEquals(
            true,
            pnx(series = "Elena z Avaloru").matchesField(SearchField.SERIES, "Elena")
        )
        assertEquals(
            false,
            pnx(series = "Tomek i Przyjaciele").matchesField(SearchField.SERIES, "Elena")
        )
        assertEquals(false, pnx(series = null).matchesField(SearchField.SERIES, "Elena"))
    }

    @Test
    fun `series requires all words but ignores case, diacritics and volume or author tail`() {
        val p = pnx(series = "Garstka z Ustki / Aneta Jadowska ; [t. 1]")
        assertEquals(
            true,
            p.matchesField(SearchField.SERIES, seriesSearchTerm(p.addata["seriestitle"]!![0]))
        )
        assertEquals(true, p.matchesField(SearchField.SERIES, "garstka USTKI"))
        assertEquals(true, pnx(series = "Żółć").matchesField(SearchField.SERIES, "zolc"))
        assertEquals(false, p.matchesField(SearchField.SERIES, "Garstka Gdyni"))
    }

    @Test
    fun `author matches creator with dates and role as well as addata au`() {
        val p = pnx(creator = "Jadowska, Aneta (1981- ) Autor\$\$QJadowska, Aneta (1981- )")
        assertEquals(
            true,
            p.matchesField(SearchField.AUTHOR, authorSearchTerm("Jadowska, Aneta (1981- ). Autor"))
        )
        assertEquals(false, p.matchesField(SearchField.AUTHOR, "Ferrante, Elena"))
        assertEquals(
            true,
            pnx(au = "Ferrante, Elena").matchesField(SearchField.AUTHOR, "Elena Ferrante")
        )
    }

    @Test
    fun `series matches a secondary series listed only in display series`() {
        val p =
            Pnx(
                display =
                    mapOf(
                        "series" to
                            listOf(
                                "Tomek i Przyjaciele - Firma Księgarska Olesiejuk\$\$QTomek i Przyjaciele - Firma Księgarska Olesiejuk",
                                "Tomek i Przyjaciele\$\$QTomek i Przyjaciele"
                            )
                    ),
                addata = mapOf("seriestitle" to listOf("Pierwsza Znajdywanka"))
            )
        assertEquals(true, p.matchesField(SearchField.SERIES, "Tomek i Przyjaciele"))
        assertEquals(true, p.matchesField(SearchField.SERIES, "Pierwsza Znajdywanka"))
        assertEquals(false, p.matchesField(SearchField.SERIES, "Smerfy"))
    }

    @Test
    fun `author matches a contributor such as an anthology editor`() {
        val p =
            Pnx(
                display =
                    mapOf(
                        "contributor" to
                            listOf("Child, Lee (1954- ) Redakcja\$\$QChild, Lee (1954- )")
                    ),
                addata = mapOf("au" to listOf("Penzler, Otto"), "addau" to listOf("Child, Lee"))
            )
        assertEquals(true, p.matchesField(SearchField.AUTHOR, "Child, Lee"))
        assertEquals(false, p.matchesField(SearchField.AUTHOR, "Ferrante, Elena"))
    }

    @Test
    fun `author named only in the title statement matches when the words are adjacent`() {
        val wrong =
            Pnx(
                display =
                    mapOf(
                        "title" to
                            listOf(
                                "Poszukiwany / Lee Child ; z angielskiego przełożył Łukasz Praski."
                            ),
                        "creator" to
                            listOf("Child, Lauren (1965- ) Autor\$\$QChild, Lauren (1965- )")
                    ),
                addata = mapOf("au" to listOf("Child, Lauren"))
            )
        assertEquals(true, wrong.matchesField(SearchField.AUTHOR, "Child, Lee"))
        val noise =
            Pnx(
                display =
                    mapOf(
                        "title" to
                            listOf(
                                "Atak piratów / Marcus Emerson ; ilustracje David Lee ; Noah Child."
                            )
                    ),
                addata = mapOf("au" to listOf("Emerson, Marcus"))
            )
        assertEquals(false, noise.matchesField(SearchField.AUTHOR, "Child, Lee"))
    }

    @Test
    fun `any field always matches`() {
        assertEquals(true, pnx().matchesField(SearchField.ANY, "cokolwiek"))
    }
}
