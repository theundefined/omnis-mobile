package com.theundefined.omnis.ui

import com.theundefined.omnis.data.model.BookVersion
import com.theundefined.omnis.data.model.BranchAvailability
import com.theundefined.omnis.data.model.SearchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaFilterTest {

    private fun version(mmsid: String, type: String?, branch: String = "Filia 02") =
        BookVersion(
            mmsid = mmsid,
            title = "Artemis",
            author = null,
            edition = null,
            publisher = null,
            publicationDate = null,
            isbns = emptyList(),
            frbrgroupid = null,
            branches = listOf(BranchAvailability(branch, "B", null, "available")),
            resourceType = type
        )

    private fun section(vararg results: SearchResult, selected: Set<String> = emptySet()) =
        SearchTenantSection(
            tenantKey = "t",
            tenantLabel = "T",
            results = results.toList(),
            confirmedBranches = listOf("Filia 02", "Filia 11"),
            selectedBranches = selected,
            showAllBranches = selected.isEmpty()
        )

    private fun result(title: String, vararg versions: BookVersion) =
        SearchResult(frbrgroupid = null, title = title, author = null, versions = versions.toList())

    @Test
    fun `media type is normalized and missing type means book`() {
        assertEquals("audiobook", version("a", " Audiobook ").mediaType)
        assertEquals("book", version("b", null).mediaType)
        assertEquals("book", version("c", "").mediaType)
    }

    @Test
    fun `book filter drops audiobook versions and keeps book and untyped`() {
        val s =
            section(
                result(
                    "Artemis",
                    version("a", "Audiobook"),
                    version("b", "book"),
                    version("c", null)
                )
            )
        val filtered = s.filteredResults(setOf("book"))
        assertEquals(listOf("b", "c"), filtered.single().versions.map { it.mmsid })
    }

    @Test
    fun `book filter drops results without any printed version`() {
        val s =
            section(
                result("Audio", version("a", "Audiobook")),
                result("Book", version("b", "Book"))
            )
        assertEquals(listOf("Book"), s.filteredResults(setOf("book")).map { it.title })
    }

    @Test
    fun `audiobook filter keeps only audiobooks`() {
        val s = section(result("Artemis", version("a", "Audiobook"), version("b", "book")))
        assertEquals(
            listOf("a"),
            s.filteredResults(setOf("audiobook")).single().versions.map { it.mmsid }
        )
    }

    @Test
    fun `empty selection keeps every version`() {
        val s = section(result("Artemis", version("a", "Audiobook"), version("b", "book")))
        assertEquals(2, s.filteredResults(emptySet()).single().versions.size)
    }

    @Test
    fun `remembered type missing from results does not filter`() {
        val state =
            SearchUiState(
                tenantSections = listOf(section(result("Artemis", version("b", "book")))),
                selectedMediaTypes = setOf("video")
            )
        assertEquals(listOf("book"), state.availableMediaTypes())
        assertTrue(state.effectiveMediaTypes().isEmpty())
    }

    @Test
    fun `book filter combines with branch filter`() {
        val s =
            section(
                result(
                    "Artemis",
                    version("a", "Audiobook", "Filia 02"),
                    version("b", "book", "Filia 11"),
                    version("c", "book", "Filia 02")
                ),
                selected = setOf("Filia 02")
            )
        val filtered = s.filteredResults(setOf("book"))
        assertEquals(listOf("c"), filtered.single().versions.map { it.mmsid })
        assertTrue(filtered.single().versions.single().isPrintBook)
    }
}
