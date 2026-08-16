package com.theundefined.omnis.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class AccountSortingTest {

    private val realTenant =
        Tenant(
            name = "Example Library",
            baseUrl = "https://example.com",
            institution = "INST",
            view = "INST:VIEW"
        )

    private fun account(
        id: String,
        username: String = "user",
        displayName: String? = null,
        isDemo: Boolean = false
    ) =
        Account(
            id = id,
            username = username,
            password = "pw",
            tenant = if (isDemo) MOCK_TENANT else realTenant,
            displayName = displayName,
            isDemo = isDemo
        )

    @Test
    fun `sorts accounts alphabetically by display name, falling back to username`() {
        val zorro = account(id = "1", username = "zorro")
        val anna = account(id = "2", username = "user2", displayName = "Anna")
        val basia = account(id = "3", username = "user3", displayName = "Basia")

        val result = listOf(zorro, anna, basia).sortedForSettings()

        assertEquals(listOf(anna, basia, zorro), result)
    }

    @Test
    fun `sorts Polish diacritics in locale-aware order`() {
        // W polskim alfabecie "ż" jest osobną literą sortowaną PO "z" (a ą b c ć d e ę f g h i j
        // k l ł m n o ó p q r s ś t u v w x y z ź ż) — inaczej niż w prostym porównaniu Unicode,
        // gdzie "Ż" (U+017B) i tak wypadłoby po "Z", ale test wymusza użycie polskiego Collatora
        // zamiast domyślnego locale'u, w którym część znaków diakrytycznych sortuje się inaczej.
        val zulu = account(id = "1", username = "Zulu")
        val agata = account(id = "2", username = "Agata")
        val zaba = account(id = "3", username = "Żaba")

        val result = listOf(zulu, agata, zaba).sortedForSettings()

        assertEquals(listOf(agata, zulu, zaba), result)
    }

    @Test
    fun `demo account is always sorted last regardless of name`() {
        val demo = account(id = "1", username = DEMO_USERNAME, isDemo = true)
        val aAccount = account(id = "2", username = "Aaa")
        val zAccount = account(id = "3", username = "Zzz")

        val result = listOf(demo, aAccount, zAccount).sortedForSettings()

        assertEquals(listOf(aAccount, zAccount, demo), result)
    }
}
