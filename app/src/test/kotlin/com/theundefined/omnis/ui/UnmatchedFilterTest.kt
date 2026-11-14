package com.theundefined.omnis.ui

import com.theundefined.omnis.data.model.BookVersion
import com.theundefined.omnis.data.model.BranchAvailability
import com.theundefined.omnis.data.model.SearchResult
import org.junit.Assert.assertEquals
import org.junit.Test

// Wyszukiwanie po autorze/serii: dzieła bez dopasowania (SearchResult.matchesQuery == false) są
// domyślnie ukrywane, ale nigdy nie znikają po cichu ani nie zostawiają pustego ekranu.
class UnmatchedFilterTest {

    private fun result(title: String, matches: Boolean, type: String? = null) =
        SearchResult(
            frbrgroupid = null,
            title = title,
            author = null,
            versions =
                listOf(
                    BookVersion(
                        mmsid = title,
                        title = title,
                        author = null,
                        edition = null,
                        publisher = null,
                        publicationDate = null,
                        isbns = emptyList(),
                        frbrgroupid = null,
                        branches = listOf(BranchAvailability("Filia 02", "B", null, "available")),
                        resourceType = type
                    )
                ),
            matchesQuery = matches
        )

    private fun section(vararg results: SearchResult) =
        SearchTenantSection(tenantKey = "t", tenantLabel = "T", results = results.toList())

    private val mixed =
        listOf(result("Poziom śmierci", true), result("Horrid Henry", false), result("Podejrzany", true))

    @Test
    fun `hides unmatched works only when asked and something matches`() {
        assertEquals(listOf("Poziom śmierci", "Podejrzany"), mixed.withoutUnmatched(true).map { it.title })
        assertEquals(mixed, mixed.withoutUnmatched(false))
    }

    @Test
    fun `hides nothing when no work matches so the screen is never empty`() {
        val none = listOf(result("A", false), result("B", false))
        assertEquals(none, none.withoutUnmatched(true))
        assertEquals(0, section(*none.toTypedArray()).unmatchedCount())
    }

    @Test
    fun `search without field filtering keeps everything`() {
        val all = listOf(result("A", true), result("B", true))
        assertEquals(all, all.withoutUnmatched(true))
    }

    @Test
    fun `filteredResults hides unmatched and still applies the media type filter`() {
        val s =
            section(
                result("Książka", true, type = null),
                result("Film", true, type = "dvd"),
                result("Szum", false, type = null)
            )
        assertEquals(listOf("Książka", "Film"), s.filteredResults(hideUnmatched = true).map { it.title })
        assertEquals(
            listOf("Film"),
            s.filteredResults(setOf("dvd"), hideUnmatched = true).map { it.title }
        )
        assertEquals(3, s.filteredResults().size)
    }

    @Test
    fun `counts reflect hidden works and the show toggle`() {
        val s = section(*mixed.toTypedArray())
        val state = SearchUiState(tenantSections = listOf(s, s))
        assertEquals(2, state.unmatchedCount())
        assertEquals(2, state.hiddenUnmatchedCount())
        // po „Pokaż”: nic nie jest ukryte, ale chip dalej wie, że jest co ukryć
        val shown = state.copy(showUnmatched = true)
        assertEquals(0, shown.hiddenUnmatchedCount())
        assertEquals(2, shown.unmatchedCount())
    }
}
