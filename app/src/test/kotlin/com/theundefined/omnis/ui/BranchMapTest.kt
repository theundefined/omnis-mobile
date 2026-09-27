package com.theundefined.omnis.ui

import com.theundefined.omnis.data.model.BookVersion
import com.theundefined.omnis.data.model.BranchAvailability
import com.theundefined.omnis.data.model.Coordinates
import com.theundefined.omnis.data.model.SearchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BranchMapTest {

    private fun branch(
        name: String,
        sub: String?,
        url: String?,
        status: String = "available",
        dueDate: String? = null,
        overdue: Boolean = false
    ) = BranchAvailability(name, "BR", sub, status, dueDate, overdue, url)

    private fun version(mmsid: String, vararg branches: BranchAvailability) =
        BookVersion(
            mmsid = mmsid,
            title = "Przebudzenie Lewiatana",
            author = null,
            edition = null,
            publisher = null,
            publicationDate = "2013",
            isbns = emptyList(),
            frbrgroupid = null,
            branches = branches.toList()
        )

    private val central = "https://maps.app.goo.gl/central"
    private val lodowa = "https://maps.app.goo.gl/lodowa"

    private val result =
        SearchResult(
            frbrgroupid = null,
            title = "Przebudzenie Lewiatana",
            author = null,
            versions =
                listOf(
                    version(
                        "1",
                        branch("BG - Czytelnie", "Aleje Marcinkowskiego 23", central),
                        branch("Filia 04", "ul. Lodowa 4", lodowa)
                    ),
                    version(
                        "2",
                        branch("BG - Wypożyczalnia", "Aleje Marcinkowskiego 23", central),
                        branch("Filia 04", "ul. Lodowa 4", lodowa, "unavailable", "2026-10-01"),
                        branch("Filia 99", "Wypożyczalnia", null)
                    )
                )
        )

    @Test
    fun `branches are merged across editions`() {
        val branches = mapBranchesOf(result, "Biblioteka Raczyńskich (Poznań)")
        assertEquals(
            listOf("BG - Czytelnie", "Filia 04", "BG - Wypożyczalnia", "Filia 99"),
            branches.map { it.name }
        )
        val filia04 = branches.first { it.name == "Filia 04" }
        assertEquals(listOf("1", "2"), filia04.holdings.map { it.version.mmsid })
        assertEquals("ul. Lodowa 4", filia04.address)
        assertEquals(lodowa, filia04.location.key)
        // "Wypożyczalnia" to nazwa działu, nie adres.
        assertEquals(null, branches.first { it.name == "Filia 99" }.address)
    }

    @Test
    fun `branches in the same building share one pin`() {
        val branches = mapBranchesOf(result, "Biblioteka Raczyńskich (Poznań)")
        val coordinates =
            mapOf(
                central to Coordinates(52.408439, 16.9286332),
                lodowa to Coordinates(52.393953, 16.9000829)
            )
        val state = BranchMapState("t", branches, coordinates)
        val pins = state.pins
        assertEquals(2, pins.size)
        assertEquals(
            listOf("BG - Czytelnie", "BG - Wypożyczalnia"),
            pins.first { it.lat == 52.408439 }.branches.map { it.name }
        )
        // Filia 99 jeszcze się ustala.
        assertTrue(state.isResolving)
    }

    @Test
    fun `unresolved branches are reported once lookup finishes`() {
        val branches = mapBranchesOf(result, "Biblioteka Raczyńskich (Poznań)")
        val keys = branches.map { it.location.key }.distinct()
        val coordinates =
            keys.associateWith<String, Coordinates?> { Coordinates(52.0, 16.0) } +
                (branches.last().location.key to null)
        val state = BranchMapState("t", branches, coordinates)
        assertFalse(state.isResolving)
        assertEquals(listOf("Filia 99"), state.unresolvedBranches.map { it.name })
    }

    @Test
    fun `pin status prefers available copies`() {
        val available = MapBranch("A", null, mapBranchesOf(result, null)[0].location, emptyList())
        fun pinOf(vararg b: BranchAvailability) =
            MapPin(
                0.0,
                0.0,
                listOf(available.copy(holdings = b.map { MapHolding(result.versions[0], it) }))
            )
        assertEquals(
            PinStatus.AVAILABLE,
            pinOf(branch("A", null, null), branch("A", null, null, "unavailable", "2026-10-01"))
                .status()
        )
        assertEquals(
            PinStatus.BORROWED,
            pinOf(branch("A", null, null, "unavailable", "2026-10-01")).status()
        )
        assertEquals(
            PinStatus.OVERDUE,
            pinOf(branch("A", null, null, "unavailable", "2026-01-01", overdue = true)).status()
        )
        assertEquals(PinStatus.UNKNOWN, pinOf(branch("A", null, null, "unavailable")).status())
    }

    @Test
    fun `map button needs a link or an address`() {
        assertTrue(hasMappableBranches(result))
        val bare =
            SearchResult(null, "x", null, listOf(version("3", branch("Filia 99", "Magazyn", null))))
        assertFalse(hasMappableBranches(bare))
    }
}
