package com.theundefined.omnis.data.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoModeTest {

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
        tenant: Tenant = realTenant,
        isEnabled: Boolean = true,
        isDemo: Boolean = false,
        disabledByDemo: Boolean = false
    ) =
        Account(
            id = id,
            username = username,
            password = "pw",
            tenant = tenant,
            isEnabled = isEnabled,
            isDemo = isDemo,
            disabledByDemo = disabledByDemo
        )

    @Test
    fun `applyDemoMode on empty list creates one enabled demo account`() {
        val result = applyDemoMode(emptyList())

        assertEquals(1, result.size)
        val demo = result[0]
        assertTrue(demo.isDemo)
        assertTrue(demo.isEnabled)
        assertEquals(MOCK_TENANT, demo.tenant)
        assertEquals(MOCK_TENANT.defaultTimeoutSeconds, demo.timeoutSeconds)
        assertEquals(DEMO_USERNAME, demo.username)
        assertEquals(DEMO_PASSWORD, demo.password)
    }

    @Test
    fun `applyDemoMode disables existing enabled accounts`() {
        val real = account(id = "1", isEnabled = true)
        val result = applyDemoMode(listOf(real))

        val updatedReal = result.first { !it.isDemo }
        assertFalse(updatedReal.isEnabled)
        assertTrue(updatedReal.disabledByDemo)
    }

    @Test
    fun `applyDemoMode is idempotent`() {
        val once = applyDemoMode(listOf(account(id = "1", isEnabled = true)))
        val twice = applyDemoMode(once)

        assertEquals(1, twice.count { it.isDemo })
    }

    @Test
    fun `applyDemoMode reuses existing disabled demo account preserving its id`() {
        val existingDemo =
            account(
                id = "demo-1",
                username = DEMO_USERNAME,
                tenant = MOCK_TENANT,
                isEnabled = false,
                isDemo = true
            )
        val result = applyDemoMode(listOf(existingDemo))

        assertEquals(1, result.size)
        assertEquals("demo-1", result[0].id)
        assertTrue(result[0].isEnabled)
    }

    @Test
    fun `exitDemoMode restores only accounts disabled by demo`() {
        val manuallyDisabled = account(id = "1", isEnabled = false)
        val demoDisabled = account(id = "2", isEnabled = false, disabledByDemo = true)
        val demoAccount =
            account(
                id = "3",
                username = DEMO_USERNAME,
                tenant = MOCK_TENANT,
                isEnabled = true,
                isDemo = true
            )

        val result = exitDemoMode(listOf(manuallyDisabled, demoDisabled, demoAccount))

        assertFalse(result.first { it.id == "1" }.isEnabled)
        val restored = result.first { it.id == "2" }
        assertTrue(restored.isEnabled)
        assertFalse(restored.disabledByDemo)
        assertFalse(result.first { it.id == "3" }.isEnabled)
    }

    @Test
    fun `old accounts without new fields deserialize with backward-compatible defaults`() {
        val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            encodeDefaults = true
        }
        val legacyJson =
            """{"id":"1","username":"user","password":"pw","tenant":{"name":"Example","baseUrl":"https://example.com","institution":"INST","view":"INST:VIEW"}}"""

        val decoded = json.decodeFromString(Account.serializer(), legacyJson)

        assertTrue(decoded.isEnabled)
        assertFalse(decoded.isDemo)
        assertFalse(decoded.disabledByDemo)
        assertNull(decoded.timeoutSeconds)
    }
}
