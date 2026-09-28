package com.theundefined.omnis.ui

import com.theundefined.omnis.data.model.BookVersion
import com.theundefined.omnis.data.model.BranchAvailability
import com.theundefined.omnis.data.model.DueDate
import com.theundefined.omnis.data.model.DueDateLookup
import com.theundefined.omnis.data.model.Holding
import com.theundefined.omnis.data.model.SearchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class DueDateUpdateTest {

    private fun branch(name: String, pending: Boolean) =
        BranchAvailability(
            libraryName = name,
            libraryCode = "BR",
            subLocation = null,
            status = if (pending) "unavailable" else "available",
            dueDatePending = pending
        )

    private fun version(mmsid: String, vararg branches: BranchAvailability) =
        BookVersion(
            mmsid = mmsid,
            title = mmsid,
            author = null,
            edition = null,
            publisher = null,
            publicationDate = null,
            isbns = emptyList(),
            frbrgroupid = null,
            branches = branches.toList()
        )

    private val other = SearchResult(null, "Inna", null, listOf(version("m9", branch("F1", true))))
    private val results =
        listOf(
            SearchResult(
                null,
                "Tytuł",
                null,
                listOf(
                    version("m1", branch("F1", false), branch("F2", true), branch("F3", true)),
                    version("m2", branch("F2", true))
                )
            ),
            other
        )

    private fun lookup(mmsid: String, index: Int) =
        DueDateLookup(mmsid, index, Holding(mainLocation = "x"))

    @Test
    fun `fills only the targeted branch of the targeted version`() {
        val updated = results.withDueDate(lookup("m1", 1), DueDate("05/10/2026", overdue = false))

        val m1 = updated[0].versions[0].branches
        assertEquals("05/10/2026", m1[1].dueDate)
        assertEquals(false, m1[1].dueDatePending)
        assertEquals(true, m1[2].dueDatePending)
        assertEquals(true, updated[0].versions[1].branches[0].dueDatePending)
        assertSame(other, updated[1])
    }

    @Test
    fun `failed lookup clears pending without date`() {
        val branch = results.withDueDate(lookup("m2", 0), null)[0].versions[1].branches[0]

        assertNull(branch.dueDate)
        assertEquals(false, branch.dueDatePending)
    }
}
