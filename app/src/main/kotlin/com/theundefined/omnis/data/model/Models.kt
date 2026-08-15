package com.theundefined.omnis.data.model

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Tenant(
    val name: String,
    val baseUrl: String,
    val institution: String,
    val view: String,
    val isDemo: Boolean = false,
    val defaultTimeoutSeconds: Long? = null
)

/**
 * Dwa konta "są tą samą biblioteką" (ten sam katalog, te same filie) wtedy i tylko wtedy, gdy mają
 * ten sam institution i view — to one, nie baseUrl, definiują zakres wyszukiwania w Primo. Używane
 * do grupowania kont na potrzeby wyszukiwania (patrz OmnisViewModel.pickSearchAccounts) i jako
 * klucz persystencji preferencji filii (patrz SearchBranchPrefs/AccountManager).
 */
fun Tenant.searchKey(): String = "$institution|$view"

data class UserInfo(
    val displayName: String,
    val userName: String,
    val loansCount: Int = 0,
    val requestsCount: Int = 0,
    val finesAmount: Double = 0.0,
    val finesCurrency: String = "PLN"
)

@Serializable
data class Loan(
    @SerializedName("loanid") @SerialName("loanid") val id: String,
    val mmsid: String,
    val title: String,
    val author: String?,
    @SerializedName("duedate") @SerialName("duedate") val dueDate: String,
    @SerializedName("duehour") @SerialName("duehour") val dueHour: String,
    @SerializedName("loandate") @SerialName("loandate") val loanDate: String,
    @SerializedName("loanstatus") @SerialName("loanstatus") val status: String,
    @SerializedName("ilsinstitutionname") @SerialName("ilsinstitutionname") val libraryName: String,
    @SerializedName("mainlocationname") @SerialName("mainlocationname") val locationName: String,
    @SerializedName("secondarylocationname")
    @SerialName("secondarylocationname")
    val subLocationName: String?,
    @SerializedName("itembarcode") @SerialName("itembarcode") val barcode: String,
    val renewable: Boolean = false,
    var accountId: String? = null,
    var ownerName: String? = null
)

data class LoanResponse(val data: LoanData)

data class LoanData(val loans: LoansList)

data class LoansList(
    @SerializedName("loan") val loan: List<LoanResponseItem>,
    val showmore: List<String>?
)

data class LoanResponseItem(
    @SerializedName("loanid") val id: String,
    val mmsid: String,
    val title: String,
    val author: String?,
    @SerializedName("duedate") val dueDate: String,
    @SerializedName("duehour") val dueHour: String,
    @SerializedName("loandate") val loanDate: String,
    @SerializedName("loanstatus") val status: String,
    @SerializedName("ilsinstitutionname") val libraryName: String,
    @SerializedName("mainlocationname") val locationName: String,
    @SerializedName("secondarylocationname") val subLocationName: String?,
    @SerializedName("itembarcode") val barcode: String,
    val renew: String?
)

@Serializable
data class HistoryCacheEntry(
    val loans: List<Loan> = emptyList(),
    val nextOffset: Int = 1,
    val hasMore: Boolean = true
)

@Serializable
data class SearchBranchPrefs(
    val selectedBranches: Set<String> = emptySet(),
    val showAllBranches: Boolean = true
)

// Wyniki wyszukiwania katalogu — dane efemeryczne (zależne od zapytania), NIE cache'owane
// trwale, więc zwykłe (nie @Serializable) data class'y, w odróżnieniu od Loan/Account.
data class BranchAvailability(
    val libraryName: String, // == Primo holding.mainLocation; to jest etykieta checkboxa filii
    val libraryCode: String,
    val subLocation: String?,
    val status: String, // "available" | "unavailable" | inne
    // var (nie val) celowo — OmnisRepository.searchBooks dopisuje termin zwrotu in-place po
    // fakcie (osobny request per niedostępna filia), analogicznie do mutowalnego modelu
    // Pydantic w client.py (branch.due_date = due_date).
    var dueDate: String? = null,
    var overdue: Boolean = false
)

data class BookVersion(
    val mmsid: String,
    val title: String,
    val author: String?,
    val edition: String?,
    val publisher: String?,
    val publicationDate: String?,
    val isbns: List<String>,
    val frbrgroupid: String?,
    val branches: List<BranchAvailability>,
    // Primo pnx.display["type"], np. "Audiobook" — null lub "book" (bez rozróżnienia
    // wielkości liter) oznacza zwykłą książkę drukowaną. Port 1:1 client.py:688
    // (resource_type=self._display_first(v, "type")) z omnis-py.
    val resourceType: String? = null
)

data class SearchResult(
    val frbrgroupid: String?,
    val title: String,
    val author: String?,
    val versions: List<BookVersion>
)

data class SearchPage(val results: List<SearchResult>, val hasMore: Boolean)

data class LoginResponse(val jwtData: String?)

data class CountersResponse(val data: CountersData)

data class CountersData(val listofactions: ListOfActions)

data class ListOfActions(val action: List<CounterAction>)

data class CounterAction(val type: String, val value: String)

// --- Wyszukiwanie katalogu (Primo pnxs/delivery/holdings) ---
// Gson (nie kotlinx) — spójne z resztą surowych DTO API tego pliku (LoanResponse,
// CountersResponse, ...), które są zawsze Gson; te nie są nigdy persystowane, więc reguła
// "wyłącznie kotlinx" z GEMINI.md (dotycząca modeli trwałych) ich nie obejmuje.
//
// display/addata/facets/control w Primo to mapa o dowolnych kluczach (nazwa pola ->
// List<String>, konwencja "pierwszy element"), nie ustalony z góry zestaw pól — stąd
// Map<String, List<String>> zamiast klas z polami na sztywno. Pola poniższych DTO
// (Delivery/Holding/HoldingsStatus*) są wywnioskowane z dostępu przez .get("klucz") w
// omnis-py (client.py), NIE z przechwyconej realnej odpowiedzi HTTP — patrz zastrzeżenie w
// docs/plans/book-search.md §6. Błąd w nazwie pola nie wywali się w runtime (Gson zostawia
// null/wartość domyślną), tylko cicho da puste wyniki — zweryfikować przez
// HttpLoggingInterceptor przed uznaniem za ostateczne.

data class PnxsSearchResponse(val docs: List<PnxDoc> = emptyList(), val info: PnxInfo? = null)

data class PnxInfo(
    val total: Int? = null
) // pole do zweryfikowania — patrz OmnisRepository.searchBooks

data class PnxDoc(val pnx: Pnx = Pnx())

data class Pnx(
    val display: Map<String, List<String>> = emptyMap(),
    val addata: Map<String, List<String>> = emptyMap(),
    val facets: Map<String, List<String>> = emptyMap(),
    val control: Map<String, List<String>> = emptyMap()
)

// Primo nie jest spójne w typowaniu wartości wewnątrz display/addata/facets/control: zwykle to
// lista stringów, ale bywa, że dla pojedynczej wartości pole przychodzi jako goły string zamiast
// jednoelementowej tablicy (zweryfikowane empirycznie — błąd zgłaszany w aplikacji: "Expected
// BEGIN_ARRAY but was STRING at ... path $.docs[0].pnx.control", odtworzony i potwierdzony przez
// scratch-test na Gson 2.10.1 dla JSON-a {"control":{"recordid":"ALMA123"}}). Deserializer musi
// być zarejestrowany na Pnx::class.java, NIE na Type z TypeToken<Map<String, List<String>>>() —
// Kotlin generuje dla `List<String>` (interfejs z `out`-wariancją) sygnaturę z wildcardem
// (`? extends String`) w miejscu, gdzie typ pojawia się jako argument typu w publicznej
// supertypie (tu: wewnątrz anonimowej klasy `object : TypeToken<...>() {}`), podczas gdy
// zreflektowany typ pola `Pnx.control` takiego wildcardu nie ma — Gson dopasowuje adaptery po
// dokładnej równości Type, więc rejestracja po Type z TypeToken nigdy by nie trafiła w realne
// pole i cicho spadała z powrotem na domyślne (psujące się) parsowanie mapy. Zweryfikowane
// empirycznie: scratch-test w Kotlinie (nie w Javie, gdzie problem wariancji nie występuje)
// odtworzył ten sam błąd mimo zarejestrowanego adaptera po Type, i przestał go rzucać dopiero
// po przejściu na rejestrację po klasie.
private fun JsonElement?.toLenientStringListMap(): Map<String, List<String>> {
    val result = mutableMapOf<String, List<String>>()
    if (this != null && isJsonObject) {
        for ((key, value) in asJsonObject.entrySet()) {
            result[key] =
                when {
                    value.isJsonArray ->
                        value.asJsonArray.mapNotNull { if (it.isJsonNull) null else it.asString }
                    value.isJsonPrimitive -> listOf(value.asString)
                    else -> emptyList()
                }
        }
    }
    return result
}

val pnxDeserializer =
    JsonDeserializer<Pnx> { json, _, _ ->
        val obj = json?.asJsonObject
        Pnx(
            display = obj?.get("display").toLenientStringListMap(),
            addata = obj?.get("addata").toLenientStringListMap(),
            facets = obj?.get("facets").toLenientStringListMap(),
            control = obj?.get("control").toLenientStringListMap()
        )
    }

// Jedyne miejsce budujące Gson dla API Primo — używane zarówno przez
// OmnisRepository.createClient, jak i przez testy (ModelsTest), żeby cofnięcie rejestracji
// pnxDeserializer w jednym z tych miejsc nie mogło ukryć się za zielonymi testami.
fun createPrimoGson(): Gson =
    GsonBuilder().registerTypeAdapter(Pnx::class.java, pnxDeserializer).create()

// Port 1:1 statycznych metod client.py:340-368 (_display_first/_addata_first/
// _extract_frbrgroupid/_alma_id/_bare_mmsid) jako extension functions na Pnx — wspólny typ
// pola `pnx` zarówno w PnxDoc, jak i w DeliveryItem, więc te same helpery działają na obu.
fun Pnx.displayFirst(field: String): String? = display[field]?.firstOrNull()

fun Pnx.addataFirst(field: String): String? = addata[field]?.firstOrNull()

fun Pnx.frbrgroupid(): String? = facets["frbrgroupid"]?.firstOrNull()

fun Pnx.almaId(): String? = control["recordid"]?.firstOrNull()

fun Pnx.bareMmsid(): String {
    control["sourcerecordid"]?.firstOrNull()?.let {
        return it
    }
    val alma = almaId()
    return if (alma != null && alma.startsWith("alma")) alma.substring(4) else alma ?: ""
}

data class DeliveryItem(val pnx: Pnx = Pnx(), val delivery: Delivery? = null)

data class Delivery(val holding: List<Holding>? = null)

data class Holding(
    val mainLocation: String = "",
    val libraryCode: String = "",
    val subLocation: String? = null,
    val availabilityStatus: String = "unknown",
    val holdId: String? = null,
    // Zweryfikowane empirycznie (docs/api-verification-response.md, przez bisekcję pól na
    // żywym koncie): bez tego pola ILSServices/holdings/{id} zwraca 200 OK, ale z pustym
    // wynikiem (brak due_date) — reszta pól surowego holding (21 kluczy z API) nie ma na to
    // żadnego wpływu. Format wartości (np. "HoldingResultKey [mid=..., libraryId=...,
    // locationCode=..., callNumber=...]") nie jest udokumentowany i nie próbujemy go budować
    // ręcznie — to pole jest tylko przekazywane 1:1 tak, jak przyszło z /pub/delivery.
    val holKey: String? = null
)

data class PhysicalServiceResponse(val physicalServiceId: String? = null)

data class HoldingsStatusRequest(
    val filters: HoldingsFilters,
    val locations: List<Holding>,
    val hideResourceSharing: Boolean = false
)

data class HoldingsFilters(
    val noItem: Int = 10,
    val sublibrary: String,
    val collection: String = "",
    val callnumber: String = "",
    // String (nie String?) celowo — client.py robi holding.get("holdId", ""), czyli zawsze
    // wysyła klucz "holid" jako string (pusty, gdy brak), nigdy nie pomija go w body. Gson
    // domyślnie POMIJA pola o wartości null przy serializacji requestu, więc String? tutaj
    // wysyłałby zupełnie inny JSON (brakujący klucz) niż referencja przy braku holdId.
    val holid: String = "",
    val sublibs: String,
    val ilsRecordList: List<IlsRecordRef>,
    val vid: String,
    val filterCall: Boolean = true
)

data class IlsRecordRef(val institution: String, val recordId: String)

data class HoldingsStatusResponse(val data: HoldingsStatusData = HoldingsStatusData())

data class HoldingsStatusData(val itemInfo: ItemInfo = ItemInfo())

data class ItemInfo(val locations: List<StatusLocation>? = null)

data class StatusLocation(val items: List<StatusItem>? = null)

data class StatusItem(val itemstatusname: String = "")
