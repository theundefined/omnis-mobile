package com.theundefined.omnis.ui

import com.theundefined.omnis.data.model.Account
import com.theundefined.omnis.data.model.BookVersion
import com.theundefined.omnis.data.model.BranchAvailability
import com.theundefined.omnis.data.model.Hold
import com.theundefined.omnis.data.model.Holding
import com.theundefined.omnis.data.model.MOCK_TENANT
import com.theundefined.omnis.data.model.searchKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HoldPlacementStateTest {

    private fun account(
        id: String,
        enabled: Boolean = true,
        tenant: com.theundefined.omnis.data.model.Tenant = MOCK_TENANT
    ) = Account(id = id, username = id, password = "x", tenant = tenant, isEnabled = enabled)

    private fun branch(name: String, status: String, withHolding: Boolean = true) =
        BranchAvailability(
            name,
            "B",
            null,
            status,
            holding = if (withHolding) Holding(mainLocation = name, holKey = "K-$name") else null
        )

    private fun version(vararg branches: BranchAvailability) =
        BookVersion(
            mmsid = "LOCAL1",
            title = "Lalka",
            author = null,
            edition = null,
            publisher = null,
            publicationDate = null,
            isbns = emptyList(),
            frbrgroupid = null,
            branches = branches.toList(),
            networkMmsid = "NET1"
        )

    private fun hold(id: String, mmsid: String?) =
        Hold(
            id = id,
            title = "Lalka",
            author = null,
            status = "W realizacji",
            available = false,
            cancellable = true,
            pickupLocation = null,
            requestDate = null,
            mmsid = mmsid,
            accountId = "a",
            ownerName = "A",
            tenantName = "T"
        )

    @Test
    fun `only enabled accounts of the section's library can place a hold`() {
        val other =
            MOCK_TENANT.copy(name = "Inna", baseUrl = "https://inna.example", institution = "INNA")
        val accounts =
            listOf(account("a"), account("b", enabled = false), account("c", tenant = other))
        assertEquals(listOf("a"), holdAccounts(accounts, MOCK_TENANT.searchKey()).map { it.id })
    }

    @Test
    fun `branches without a holding are dropped and available ones come first`() {
        val branches =
            holdBranches(
                version(
                    branch("Filia 02", "unavailable"),
                    branch("Filia 11", "available"),
                    branch("Filia 01", "unavailable", withHolding = false),
                    branch("Filia 03", "available")
                )
            )
        assertEquals(listOf("Filia 03", "Filia 11", "Filia 02"), branches.map { it.libraryName })
    }

    @Test
    fun `account and branch are preselected only when there is a single choice`() {
        val v = version(branch("Filia 02", "available"))
        val single = HoldPlacementState("Lalka", v, listOf(account("a")), holdBranches(v))
        assertEquals("a", single.account?.id)
        assertEquals("Filia 02", single.branch?.libraryName)

        val two = version(branch("Filia 02", "available"), branch("Filia 03", "available"))
        val multi =
            HoldPlacementState("Lalka", two, listOf(account("a"), account("b")), holdBranches(two))
        assertNull(multi.account)
        assertNull(multi.branch)
    }

    @Test
    fun `record ids cover local, item and network ids`() {
        assertEquals(setOf("LOCAL1", "ITEM1", "NET1"), holdRecordIds(version(), "ITEM1"))
        assertEquals(setOf("LOCAL1", "NET1"), holdRecordIds(version(), null))
    }

    @Test
    fun `new hold is the one missing from the baseline, matching the record first`() {
        val before = setOf("OLD")
        val ids = setOf("LOCAL1", "NET1")
        assertNull(findNewHold(before, listOf(hold("OLD", "NET1")), ids))
        assertEquals(
            "NEW",
            findNewHold(before, listOf(hold("OLD", "NET1"), hold("NEW", "NET1")), ids)?.id
        )
        // Hold pod nieznanym id rekordu, ale jedyny nowy — to nasz.
        assertEquals("NEW", findNewHold(before, listOf(hold("NEW", "OTHER")), ids)?.id)
        // Dwa nowe, żaden nie pasuje — nie zgadujemy.
        assertNull(findNewHold(before, listOf(hold("N1", "X"), hold("N2", "Y")), ids))
    }
}
