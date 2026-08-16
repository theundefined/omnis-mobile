package com.theundefined.omnis.data.model

import org.junit.Assert.assertTrue
import org.junit.Test

class TenantsTest {

    @Test
    fun `no duplicate searchKey among known tenants`() {
        val keys = KNOWN_TENANTS.map { it.searchKey() }
        assertTrue(
            "Duplicate institution|view keys: ${
                keys.groupingBy { it }.eachCount().filterValues { it > 1 }
            }",
            keys.size == keys.toSet().size
        )
    }

    @Test
    fun `no duplicate tenant names`() {
        val names = KNOWN_TENANTS.map { it.name }
        assertTrue(
            "Duplicate names: ${
                names.groupingBy { it }.eachCount().filterValues { it > 1 }
            }",
            names.size == names.toSet().size
        )
    }

    @Test
    fun `every tenant baseUrl uses https`() {
        assertTrue(KNOWN_TENANTS.all { it.baseUrl.startsWith("https://") })
    }
}
