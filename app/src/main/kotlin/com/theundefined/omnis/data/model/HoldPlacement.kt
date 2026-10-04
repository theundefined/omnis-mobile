package com.theundefined.omnis.data.model

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName

// Składanie rezerwacji — port `get_holdable_items`/`get_hold_options`/`place_hold` z omnis-py
// (client.py), gdzie przepływ został przechwycony z oficjalnego UI (curls/zamowienie) i
// zweryfikowany na żywo 2026-10-04. Modele efemeryczne (nigdy nie persystowane), więc tylko Gson.

/**
 * Egzemplarz, dla którego Primo oferuje usługę `AlmaItemRequest` (pozycja z
 * `ILSServices/holdings`). [requestPath] to `link-to-service` Primo, używany dosłownie — zawiera
 * `physicalServiceId`, który zmienia się między logowaniami, więc egzemplarz jest ważny tylko w
 * sesji, w której go pobrano.
 */
data class HoldableItem(
    val mmsid: String,
    val itemId: String,
    val requestPath: String,
    val statusName: String?,
    val category: String?,
    val mainLocation: String?,
    val subLocation: String?
)

/**
 * Miejsce odbioru z formularza. Primo koduje je jako jeden klucz `"<libraryId>$$<TYPE>"` (np.
 * `"42713777720009337$$LIBRARY"`), a body złożenia potrzebuje obu połówek osobno.
 */
data class PickupLocation(val id: String, val type: String, val name: String) {
    companion object {
        /** null przy każdym innym formacie klucza — nie zgadujemy, jak go rozdzielić. */
        fun fromKey(key: String, name: String): PickupLocation? {
            val parts = key.split("$$")
            if (parts.size != 2 || parts.any { it.isEmpty() }) return null
            return PickupLocation(parts[0], parts[1], name)
        }
    }
}

data class HoldRequestOptions(
    val item: HoldableItem,
    val requestType: String,
    val materialType: String,
    val pickupLocations: List<PickupLocation>
)

/**
 * W filii każdy egzemplarz da się zamówić; wolimy stojący na półce, żeby rezerwacja była
 * realizowana od ręki, a nie trafiała do kolejki za wypożyczeniem (jak `_pick_holdable_item` w
 * omnis-py).
 */
fun pickHoldableItem(items: List<HoldableItem>): HoldableItem? =
    items.firstOrNull { it.statusName?.lowercase()?.contains("na półce") == true }
        ?: items.firstOrNull()

/**
 * Primo zgłasza część błędów jako HTTP 200 z kopertą `{"status": "failed", "reply-code": "0002",
 * "reply-text": ...}` (sukces to `"status": "ok"`/`"reply-code": "0000"`). Zwraca opis błędu albo
 * null — także dla odpowiedzi bez tych kluczy, bo nie każdy endpoint tak opakowuje body.
 */
fun primoFailureMessage(body: String?): String? {
    if (body.isNullOrBlank()) return null
    val obj =
        runCatching { JsonParser.parseString(body) }
            .getOrNull()
            ?.takeIf { it.isJsonObject }
            ?.asJsonObject ?: return null
    val status = obj.get("status")?.takeIf { it.isJsonPrimitive }?.asString
    val replyCode = obj.get("reply-code")?.takeIf { it.isJsonPrimitive }?.asString
    if (status != "failed" && (replyCode == null || replyCode == "0000")) return null
    return obj.get("reply-text")
        ?.takeIf { it.isJsonPrimitive }
        ?.asString
        ?.takeIf { it.isNotBlank() } ?: body.take(200)
}

// --- DTO formularza `GET .../AlmaItemRequest` (kształt z omnis-py i omnis-mock SPEC REQ-H10a) ---

data class HoldFormResponse(@SerializedName("services-arr") val servicesArr: HoldFormServices?)

data class HoldFormServices(val services: List<HoldFormService>?)

// Na poziomie usługi `requestType` jest tablicą, w grupie — stringiem; czytamy tylko grupy.
data class HoldFormService(@SerializedName("groups-list-map") val groups: List<HoldFormGroup>?)

data class HoldFormGroup(
    val requestType: String?,
    val materialType: HoldFormKeyValue?,
    val pickupLocation: List<HoldFormKeyValue>?
)

data class HoldFormKeyValue(val key: String?, val value: String?)

/**
 * Pierwsza grupa formularza typu "hold" (brak typu = hold, jak w omnis-py). null = formularz nie
 * oferuje rezerwacji albo nie ma materialType, bez którego nie da się złożyć zamówienia.
 * Nieparsowalne klucze miejsc odbioru są pomijane — jeśli przez to nie zostanie żadne, UI zgłosi
 * brak miejsca odbioru zamiast zgadywać.
 */
fun HoldFormResponse.toOptions(item: HoldableItem): HoldRequestOptions? {
    val group =
        servicesArr
            ?.services
            ?.flatMap { it.groups ?: emptyList() }
            ?.firstOrNull { (it.requestType ?: "hold") == "hold" } ?: return null
    val materialType = group.materialType?.key?.takeIf { it.isNotBlank() } ?: return null
    val pickups =
        group.pickupLocation.orEmpty().mapNotNull { p ->
            p.key?.let { PickupLocation.fromKey(it, p.value ?: "") }
        }
    return HoldRequestOptions(item, group.requestType ?: "hold", materialType, pickups)
}

// --- Pozycje `ILSServices/holdings` z listą usług (rozszerzenie StatusItem) ---

data class ServiceList(val service: JsonElement? = null)

data class ItemService(
    val type: String? = null,
    val allowed: String? = null,
    @SerializedName("link-to-service") val linkToService: String? = null
)

/** `link-to-service` usługi AlmaItemRequest dozwolonej dla tego egzemplarza, albo null. */
fun StatusItem.holdRequestPath(gson: Gson): String? {
    val element = listofservices?.service ?: return null
    val services =
        when {
            element.isJsonArray -> element.asJsonArray.toList()
            element.isJsonObject -> listOf(element)
            else -> emptyList()
        }
    return services
        .filter { it.isJsonObject }
        .map { gson.fromJson(it, ItemService::class.java) }
        .firstOrNull {
            it.type == "AlmaItemRequest" && it.allowed == "Y" && !it.linkToService.isNullOrBlank()
        }
        ?.linkToService
}

fun HoldingsStatusResponse.holdableItems(gson: Gson, fallbackMmsid: String): List<HoldableItem> =
    data.itemInfo.locations.orEmpty().flatMap { loc ->
        loc.items.orEmpty().mapNotNull { item ->
            val path = item.holdRequestPath(gson) ?: return@mapNotNull null
            val itemId = item.itemid?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            HoldableItem(
                mmsid = item.mmsid?.takeIf { it.isNotBlank() } ?: fallbackMmsid,
                itemId = itemId,
                requestPath = path,
                statusName = item.itemstatusname.takeIf { it.isNotBlank() },
                category = item.itemcategoryname,
                mainLocation = item.mainlocationname ?: loc.mainLocation,
                subLocation = item.secondarylocationname ?: loc.subLocation
            )
        }
    }
