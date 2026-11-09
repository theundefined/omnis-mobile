package com.theundefined.omnis.ui

import com.theundefined.omnis.data.model.BookVersion
import com.theundefined.omnis.data.model.Loan
import com.theundefined.omnis.data.model.SearchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadBeforeTest {

    private fun loan(
        mmsid: String = "1",
        title: String = "Tytuł",
        author: String? = "Autor, Jan",
        catalogAuthor: String? = null,
        loanDate: String = "20230510",
        owner: String? = "Ania"
    ) =
        Loan(
            id = "l$mmsid$loanDate",
            mmsid = mmsid,
            title = title,
            author = author,
            dueDate = "20230601",
            dueHour = "00:00",
            loanDate = loanDate,
            status = "",
            libraryName = "",
            locationName = "",
            subLocationName = null,
            barcode = "",
            ownerName = owner,
            catalogAuthor = catalogAuthor
        )

    private fun version(mmsid: String, title: String = "x", author: String? = null) =
        BookVersion(
            mmsid = mmsid,
            title = title,
            author = author,
            edition = null,
            publisher = null,
            publicationDate = null,
            isbns = emptyList(),
            frbrgroupid = null,
            branches = emptyList()
        )

    private fun result(
        title: String,
        author: String?,
        vararg versions: BookVersion
    ) = SearchResult("g", title, author, versions.toList())

    @Test
    fun `matches by mmsid of any edition`() {
        val index = buildReadBeforeIndex(emptyList(), listOf(loan(mmsid = "42")))
        val hit = index.find(result("Inny tytuł", "Ktoś", version("7"), version("42")))
        assertNotNull(hit)
        assertEquals(2023, hit!!.year)
        assertFalse(hit.currentlyBorrowed)
    }

    @Test
    fun `falls back to normalized title and author`() {
        val index = buildReadBeforeIndex(emptyList(), listOf(loan(title = "Żółć: powieść", author = "Kowalski, Jan")))
        assertNotNull(index.find(result("ŻÓŁĆ - POWIEŚĆ".replace('-', ':'), "Jan Kowalski", version("9"))))
    }

    @Test
    fun `author dates and extra authors do not break the match`() {
        val index =
            buildReadBeforeIndex(
                emptyList(),
                listOf(loan(title = "Kamień", author = "Rowling, J. K., 1965-; Fry, Stephen"))
            )
        assertNotNull(index.find(result("Kamień", "Rowling, J. K.", version("9"))))
    }

    @Test
    fun `catalog author is preferred over loan author`() {
        val index =
            buildReadBeforeIndex(
                emptyList(),
                listOf(loan(title = "Kamień", author = "J.K. R.", catalogAuthor = "Rowling, Joanne"))
            )
        assertNotNull(index.find(result("Kamień", "Joanne Rowling", version("9"))))
    }

    @Test
    fun `title alone is not enough when the result has an author`() {
        val index = buildReadBeforeIndex(emptyList(), listOf(loan(title = "Dziady", author = null)))
        assertNull(index.find(result("Dziady", "Mickiewicz, Adam", version("9"))))
    }

    @Test
    fun `different author with same title does not match`() {
        val index = buildReadBeforeIndex(emptyList(), listOf(loan(title = "Dziady", author = "Mickiewicz, Adam")))
        assertNull(index.find(result("Dziady", "Inny, Autor", version("9"))))
    }

    @Test
    fun `title alone matches when neither side has an author`() {
        val index = buildReadBeforeIndex(emptyList(), listOf(loan(title = "Dziady", author = null)))
        assertNotNull(index.find(result("dziady", null, version("9"))))
    }

    @Test
    fun `statement of responsibility after slash is ignored`() {
        val index = buildReadBeforeIndex(emptyList(), listOf(loan(title = "Dziady / Adam Mickiewicz", author = "Mickiewicz, Adam")))
        assertNotNull(index.find(result("Dziady", "Mickiewicz, Adam", version("9"))))
    }

    @Test
    fun `current loan wins over history`() {
        val index =
            buildReadBeforeIndex(
                active = listOf(loan(mmsid = "1", loanDate = "20240101", owner = "Basia")),
                history = listOf(loan(mmsid = "1", loanDate = "20250101", owner = "Ania"))
            )
        val hit = index.find(result("Tytuł", "Autor, Jan", version("1")))!!
        assertTrue(hit.currentlyBorrowed)
        assertEquals("Basia", hit.ownerName)
    }

    @Test
    fun `latest history loan gives the year and owner`() {
        val index =
            buildReadBeforeIndex(
                active = emptyList(),
                history =
                    listOf(
                        loan(loanDate = "20210301", owner = "Ania"),
                        loan(loanDate = "2023-05-10", owner = "Basia"),
                        loan(loanDate = "20220101", owner = "Ania")
                    )
            )
        val hit = index.find(result("Tytuł", "Autor, Jan", version("1")))!!
        assertEquals(2023, hit.year)
        assertEquals("Basia", hit.ownerName)
    }

    @Test
    fun `unparseable date still matches without a year`() {
        val index = buildReadBeforeIndex(emptyList(), listOf(loan(loanDate = "???")))
        val hit = index.find(result("Tytuł", "Autor, Jan", version("1")))!!
        assertNull(hit.year)
    }

    @Test
    fun `empty index finds nothing`() {
        assertTrue(ReadBeforeIndex.EMPTY.isEmpty)
        assertNull(ReadBeforeIndex.EMPTY.find(result("a", "b", version("1"))))
    }

    @Test
    fun `blank title never produces a key`() {
        assertNull(titleAuthorKey("  ... ", "Autor"))
    }
}
