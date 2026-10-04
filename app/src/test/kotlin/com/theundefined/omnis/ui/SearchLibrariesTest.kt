package com.theundefined.omnis.ui

import com.theundefined.omnis.data.model.Account
import com.theundefined.omnis.data.model.KNOWN_TENANTS
import com.theundefined.omnis.data.model.MOCK_TENANT
import com.theundefined.omnis.data.model.Tenant
import com.theundefined.omnis.data.model.applyDemoMode
import com.theundefined.omnis.data.model.exitDemoMode
import com.theundefined.omnis.data.model.searchKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchLibrariesTest {

    private val realTenant = KNOWN_TENANTS.first { !it.isDemo }
    private val otherTenant = KNOWN_TENANTS.last { !it.isDemo && it != realTenant }

    private fun account(id: String, tenant: Tenant = realTenant, isEnabled: Boolean = true) =
        Account(
            id = id,
            username = "user$id",
            password = "pw",
            tenant = tenant,
            isEnabled = isEnabled
        )

    @Test
    fun `default selection is libraries of enabled accounts`() {
        val state = buildSearchLibrariesState(listOf(account("1")), override = null)

        assertEquals(setOf(realTenant.searchKey()), state.selectedKeys)
    }

    @Test
    fun `demo mode selects the demo library even with a saved override`() {
        val accounts = applyDemoMode(listOf(account("1")))
        val saved = setOf(otherTenant.searchKey())

        val state = buildSearchLibrariesState(accounts, override = saved)

        assertEquals(setOf(MOCK_TENANT.searchKey()), state.selectedKeys)
    }

    @Test
    fun `demo mode uses the demo override when set`() {
        val accounts = applyDemoMode(listOf(account("1")))
        val demoChoice = setOf(MOCK_TENANT.searchKey(), otherTenant.searchKey())

        val state =
            buildSearchLibrariesState(
                accounts,
                override = setOf(realTenant.searchKey()),
                demoOverride = demoChoice
            )

        assertEquals(demoChoice, state.selectedKeys)
    }

    @Test
    fun `exiting demo mode restores the saved override`() {
        val accounts = exitDemoMode(applyDemoMode(listOf(account("1"))))
        val saved = setOf(otherTenant.searchKey())

        val state =
            buildSearchLibrariesState(
                accounts,
                override = saved,
                demoOverride = setOf(MOCK_TENANT.searchKey())
            )

        assertEquals(saved, state.selectedKeys)
    }

    @Test
    fun `exiting demo mode without override falls back to enabled accounts`() {
        val accounts = exitDemoMode(applyDemoMode(listOf(account("1"))))

        val state = buildSearchLibrariesState(accounts, override = null)

        assertEquals(setOf(realTenant.searchKey()), state.selectedKeys)
    }

    @Test
    fun `manually added demo account next to real accounts keeps the saved override`() {
        val demo = applyDemoMode(emptyList()).single()
        val accounts = listOf(account("1"), demo)
        val saved = setOf(otherTenant.searchKey())

        val state =
            buildSearchLibrariesState(
                accounts,
                override = saved,
                demoOverride = setOf(MOCK_TENANT.searchKey())
            )

        assertEquals(saved, state.selectedKeys)
    }

    @Test
    fun `demo mode is active only when the demo account is the only enabled one`() {
        val demo = applyDemoMode(emptyList()).single()

        assertTrue(isDemoModeActive(listOf(demo, account("1", isEnabled = false))))
        assertFalse(isDemoModeActive(listOf(demo, account("1"))))
        assertFalse(isDemoModeActive(listOf(demo.copy(isEnabled = false), account("1"))))
    }

    @Test
    fun `picker lists account libraries first, then selected, then alphabetically`() {
        val a = Tenant(name = "Alfa", baseUrl = "https://a", institution = "A", view = "A:V")
        val b = Tenant(name = "Beta", baseUrl = "https://b", institution = "B", view = "B:V")
        val c = Tenant(name = "Ćma", baseUrl = "https://c", institution = "C", view = "C:V")
        val d = Tenant(name = "Delta", baseUrl = "https://d", institution = "D", view = "D:V")
        val e = Tenant(name = "Echo", baseUrl = "https://e", institution = "E", view = "E:V")
        val libraries =
            SearchLibrariesState(
                available = listOf(e, d, c, b, a),
                selectedKeys = setOf(b.searchKey(), e.searchKey())
            )

        val accountKeys = setOf(c.searchKey(), d.searchKey(), e.searchKey())

        val order = searchLibraryPickerOrder(libraries, accountKeys)

        // Z kontem: E (wybrana), potem C, D; bez konta: B (wybrana), potem A.
        assertEquals(listOf(e, c, d, b, a), order)
    }
}
