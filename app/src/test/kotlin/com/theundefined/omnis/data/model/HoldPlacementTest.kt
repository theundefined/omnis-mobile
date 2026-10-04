package com.theundefined.omnis.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// Kształty odpowiedzi jak w omnis-mock docs/SPEC.md (REQ-H9, REQ-H10a/b), z fikcyjnymi ID.
class HoldPlacementTest {

    private val gson = createPrimoGson()

    private val requestPath =
        "/primaws/rest/priv/ILSServices/itemServices/MOCK-SEARCH-A1/item/MOCK-ITEM-A1-F1/" +
            "PS-MOCK-SEARCH-A1/AlmaItemRequest?institution=MOCK&hasHold=true&hasBooking=false"

    private fun itemJson(
        itemId: String,
        status: String,
        allowed: String = "Y",
        services: String? = null
    ) =
        """
        {"itemid":"$itemId","mmsid":"MOCK-SEARCH-A1","itemstatusname":"$status",
         "itemcategoryname":"30 Days Loan","mainlocationname":"Filia Testowa 1",
         "secondarylocationname":"ul. Przykładowa 1",
         "listofservices":{"service":${services ?: """[{"type":"AlmaItemRequest","allowed":"$allowed",
           "link-to-service":"$requestPath"}]"""}}}
        """

    private fun holdings(vararg items: String): List<HoldableItem> =
        gson
            .fromJson(
                """{"data":{"itemInfo":{"locations":[{"main-location":"Filia Testowa 1",
                   "items":[${items.joinToString(",")}]}]}}}""",
                HoldingsStatusResponse::class.java
            )
            .holdableItems(gson, "FALLBACK")

    @Test
    fun `only items with an allowed AlmaItemRequest service are holdable`() {
        val items =
            holdings(
                itemJson("I1", "Egzemplarz na półce"),
                itemJson("I2", "Egzemplarz na półce", allowed = "N"),
                itemJson(
                    "I3",
                    "Egzemplarz na półce",
                    services = """[{"type":"AlmaBooking","allowed":"Y","link-to-service":"/x"}]"""
                )
            )
        assertEquals(listOf("I1"), items.map { it.itemId })
        val item = items[0]
        assertEquals(requestPath, item.requestPath)
        assertEquals("MOCK-SEARCH-A1", item.mmsid)
        assertEquals("Filia Testowa 1", item.mainLocation)
        assertEquals("ul. Przykładowa 1", item.subLocation)
    }

    @Test
    fun `a single service object instead of an array is accepted`() {
        val items =
            holdings(
                itemJson(
                    "I1",
                    "Egzemplarz na półce",
                    services =
                        """{"type":"AlmaItemRequest","allowed":"Y","link-to-service":"$requestPath"}"""
                )
            )
        assertEquals(listOf("I1"), items.map { it.itemId })
    }

    @Test
    fun `on-shelf copy is preferred over a loaned one`() {
        val items =
            holdings(
                itemJson("LOANED", "Wypożyczenie do 31/08/2026"),
                itemJson("SHELF", "Egzemplarz na półce")
            )
        assertEquals("SHELF", pickHoldableItem(items)?.itemId)
        assertEquals("LOANED", pickHoldableItem(items.take(1))?.itemId)
        assertNull(pickHoldableItem(emptyList()))
    }

    @Test
    fun `pickup key is split into library id and type`() {
        assertEquals(
            PickupLocation("42713777720009337", "LIBRARY", "Filia 01"),
            PickupLocation.fromKey("42713777720009337$\$LIBRARY", "Filia 01")
        )
        assertNull(PickupLocation.fromKey("42713777720009337", "x"))
        assertNull(PickupLocation.fromKey("$\$LIBRARY", "x"))
        assertNull(PickupLocation.fromKey("a$\$b$\$c", "x"))
    }

    @Test
    fun `hold form yields request and material type and pickups`() {
        val item = holdings(itemJson("I1", "Egzemplarz na półce")).single()
        val form =
            gson.fromJson(
                """
                {"services-arr":{"services":[{"itemId":"I1","type-name":"AlmaRequest",
                  "requestType":[{"key":"hold","value":"almaRequest.requestType.hold"}],
                  "groups-list-map":[{"requestType":"hold",
                    "materialType":{"key":"BOOK","value":"Książka"},
                    "pickupLocation":[
                      {"key":"MOCKLIB1$${'$'}LIBRARY","value":"Filia Testowa 1"},
                      {"key":"broken","value":"Zła"}],
                    "termsOfUse":[{"key":"--","value":"--"}]}],
                  "chosen-parameters-map":{"pickupInstitution":"MOCK"}}]},"info-notes":[]}
                """,
                HoldFormResponse::class.java
            )
        val options = form.toOptions(item)!!
        assertEquals("hold", options.requestType)
        assertEquals("BOOK", options.materialType)
        assertEquals(
            listOf(PickupLocation("MOCKLIB1", "LIBRARY", "Filia Testowa 1")),
            options.pickupLocations
        )
    }

    @Test
    fun `hold form without a hold group or material type gives no options`() {
        val item = holdings(itemJson("I1", "Egzemplarz na półce")).single()
        val booking =
            gson.fromJson(
                """{"services-arr":{"services":[{"groups-list-map":[{"requestType":"booking",
                   "materialType":{"key":"BOOK"}}]}]}}""",
                HoldFormResponse::class.java
            )
        assertNull(booking.toOptions(item))
        val noMaterial =
            gson.fromJson(
                """{"services-arr":{"services":[{"groups-list-map":[{"requestType":"hold"}]}]}}""",
                HoldFormResponse::class.java
            )
        assertNull(noMaterial.toOptions(item))
    }

    @Test
    fun `primo failure envelope is detected, success envelopes are not`() {
        assertNull(primoFailureMessage("""{"beaconO22":"123","reply-text":"ok","status":"ok"}"""))
        assertNull(primoFailureMessage("""{"status":"ok","reply-code":"0000","reply-text":"OK"}"""))
        assertNull(primoFailureMessage("""{"data":{}}"""))
        assertNull(primoFailureMessage(""))
        assertNull(primoFailureMessage("not json"))
        assertEquals(
            "Brak uprawnień",
            primoFailureMessage(
                """{"status":"failed","reply-code":"0002","reply-text":"Brak uprawnień"}"""
            )
        )
        assertEquals("""{"reply-code":"0003"}""", primoFailureMessage("""{"reply-code":"0003"}"""))
    }
}
