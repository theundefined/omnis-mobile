package com.theundefined.omnis.data.model

import com.theundefined.omnis.ui.groupHolds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Kształt jak w zweryfikowanej na żywo odpowiedzi myaccount/requests (omnis-py,
// test_get_requests_parses_hold), z fikcyjnymi ID.
class HoldsTest {

    private val gson = createPrimoGson()
    private val account =
        Account(
            id = "acc-1",
            username = "BR000001",
            password = "x",
            tenant = MOCK_TENANT,
            displayName = "Jan Testowy"
        )

    private fun parse(json: String): List<Hold> =
        gson.fromJson(json, RequestsResponse::class.java).holdItems(gson).mapNotNull {
            it.toHold(account)
        }

    private val holdJson =
        """
        {"cancel":"Y","ilsinstitutionname":"Sprawdź dostępność w innych bibliotekach",
         "ilsinstitutioncode":"48OMNIS_NETWORK","mmsid":"991000000000001","title":"Przykładowa książka",
         "author":"Testowy, Autor","pickuplocationname":"Filia 01","available":"N",
         "requestid":"REQ-1","requestdate":"20260808","holdstatus":"W realizacji"}
        """

    @Test
    fun `hold array is parsed into the internal model`() {
        val holds = parse("""{"data":{"holds":{"hold":[$holdJson]},"bookings":{"booking":[]}}}""")
        assertEquals(1, holds.size)
        val hold = holds[0]
        assertEquals("REQ-1", hold.id)
        assertEquals("Przykładowa książka", hold.title)
        assertEquals("W realizacji", hold.status)
        assertEquals("Filia 01", hold.pickupLocation)
        assertEquals("20260808", hold.requestDate)
        assertFalse(hold.available)
        assertTrue(hold.cancellable)
        assertEquals("acc-1", hold.accountId)
        assertEquals("Jan Testowy", hold.ownerName)
        // Nazwa biblioteki z tenanta, nie etykieta UI z ilsinstitutionname.
        assertEquals(MOCK_TENANT.name, hold.tenantName)
    }

    @Test
    fun `single hold as a bare object is accepted`() {
        assertEquals(1, parse("""{"data":{"holds":{"hold":$holdJson}}}""").size)
    }

    @Test
    fun `empty or missing holds yield an empty list`() {
        assertEquals(emptyList<Hold>(), parse("""{"data":{"holds":{"hold":[]}}}"""))
        assertEquals(emptyList<Hold>(), parse("""{"data":{}}"""))
        assertEquals(emptyList<Hold>(), parse("""{}"""))
    }

    @Test
    fun `hold without request id is skipped and missing fields do not crash`() {
        val holds =
            parse("""{"data":{"holds":{"hold":[{"title":"Bez ID"},{"requestid":"REQ-2"}]}}}""")
        assertEquals(listOf("REQ-2"), holds.map { it.id })
        assertEquals("", holds[0].title)
        assertFalse(holds[0].cancellable)
    }

    @Test
    fun `available holds come first, then newest`() {
        fun hold(id: String, available: Boolean, date: String, owner: String = "Jan") =
            Hold(id, id, null, "", available, true, null, date, null, "acc", owner, "Lib")
        val grouped =
            groupHolds(
                listOf(
                    hold("old", false, "20260101"),
                    hold("ready", true, "20250101"),
                    hold("new", false, "20260301"),
                    hold("other", false, "20260101", owner = "Anna")
                )
            )
        assertEquals(listOf("Anna", "Jan"), grouped.keys.toList())
        assertEquals(listOf("ready", "new", "old"), grouped.getValue("Jan").map { it.id })
    }
}
