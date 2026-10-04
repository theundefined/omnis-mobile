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
 * Dwa tenanty "są tą samą biblioteką" (ten sam katalog, te same filie) wtedy i tylko wtedy, gdy
 * mają ten sam institution i view — to one, nie baseUrl, definiują zakres wyszukiwania w Primo.
 * Klucz wyboru bibliotek do wyszukiwania (OmnisViewModel.buildSearchLibrariesState) i persystencji
 * preferencji filii (patrz SearchBranchPrefs/AccountManager).
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
    var ownerName: String? = null,
    // Nazwa biblioteki z KNOWN_TENANTS (Tenant.name) — w odróżnieniu od libraryName
    // (ilsinstitutionname), które dla wypożyczeń z sieci OMNIS (48OMNIS_NETWORK) jest etykietą z UI
    // Primo ("Sprawdź dostępność w innych bibliotekach"), a nie nazwą biblioteki. null dla
    // wypożyczeń z cache'u sprzed dodania tego pola — uzupełni się po odświeżeniu.
    var tenantName: String? = null,
    // Z rekordu katalogu (pub/pnxs/L/alma{mmsid}) — API wypożyczeń serii nie podaje, a autora daje
    // w formacie niekoniecznie zgodnym z polem `creator` wyszukiwarki. catalogFetched odróżnia
    // "brak
    // serii" od "jeszcze nie sprawdzone", żeby nie odpytywać katalogu przy każdym odświeżeniu.
    val series: String? = null,
    val catalogAuthor: String? = null,
    val catalogFetched: Boolean = false,
    // Pola pokazywane tylko w oknie szczegółów. Nullable z domyślnym null: starszy cache ich nie
    // ma, a nie każda biblioteka musi je zwracać (sprawdzone na żywo tylko w Raczyńskich).
    @SerializedName("callnumber2") @SerialName("callnumber2") val callNumber: String? = null,
    val year: String? = null,
    @SerializedName("itemcategoryname")
    @SerialName("itemcategoryname")
    val itemCategoryName: String? = null,
    // Najpóźniejsza data, do której da się przedłużać (yyyyMMdd); tylko aktywne wypożyczenia.
    @SerializedName("maxrenewdate") @SerialName("maxrenewdate") val maxRenewDate: String? = null,
    // Komunikaty z `renewstatuses.renewstatus`, np. dlaczego nie można teraz przedłużyć.
    val renewStatuses: List<String> = emptyList(),
    // Faktyczny zwrot — tylko w historii.
    @SerializedName("returndate") @SerialName("returndate") val returnDate: String? = null,
    @SerializedName("returnhour") @SerialName("returnhour") val returnHour: String? = null
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
    val renew: String?,
    // Wszystkie poniżej nullable — Gson omija konstruktor, więc brak klucza daje null niezależnie
    // od typu Kotlinowego.
    val callnumber2: String?,
    val year: String?,
    val itemcategoryname: String?,
    val maxrenewdate: String?,
    // {"renewstatus": [String]} — surowy JsonElement, bo Primo potrafi zamienić jednoelementową
    // tablicę na goły string (patrz pnxDeserializer); rozpakowuje renewStatusMessages.
    val renewstatuses: JsonElement?,
    val returndate: String?,
    val returnhour: String?
)

fun renewStatusMessages(element: JsonElement?): List<String> {
    val inner =
        when {
            element == null || element.isJsonNull -> return emptyList()
            element.isJsonObject -> element.asJsonObject.get("renewstatus") ?: return emptyList()
            else -> element
        }
    val items = if (inner.isJsonArray) inner.asJsonArray.toList() else listOf(inner)
    return items.filter { it.isJsonPrimitive }.map { it.asString.trim() }.filter { it.isNotEmpty() }
}

// --- Rezerwacje (myaccount/requests) ---
// Kształt pozycji `hold` zweryfikowany na żywo w omnis-py (Hold w client.py, test
// test_get_requests_parses_hold). Pozostałe kategorie (photocopies, bookings, cdls, ills, acqs)
// nigdy nie były widziane z danymi, więc ich nie modelujemy.

data class RequestsResponse(val data: RequestsData?)

// `hold` jako surowy JsonElement: kontrolnie widziana była tablica, ale Primo potrafi zamienić
// jednoelementową tablicę na goły obiekt (patrz pnxDeserializer) — rozpakowuje holdItems.
data class RequestsData(val holds: HoldsWrapper?)

data class HoldsWrapper(val hold: JsonElement?)

// Wszystkie pola nullable — Gson omija konstruktor, brak klucza daje null mimo typu Kotlinowego.
data class HoldResponseItem(
    val requestid: String?,
    val title: String?,
    val author: String?,
    val holdstatus: String?,
    val available: String?,
    val cancel: String?,
    val pickuplocationname: String?,
    val requestdate: String?,
    val mmsid: String?
)

fun RequestsResponse.holdItems(gson: Gson): List<HoldResponseItem> {
    val element = data?.holds?.hold ?: return emptyList()
    val items =
        when {
            element.isJsonArray -> element.asJsonArray.toList()
            element.isJsonObject -> listOf(element)
            else -> emptyList()
        }
    return items.filter { it.isJsonObject }.map { gson.fromJson(it, HoldResponseItem::class.java) }
}

/** Rezerwacja (hold) jednego konta — model wewnętrzny, mapowany z [HoldResponseItem]. */
data class Hold(
    val id: String,
    val title: String,
    val author: String?,
    // Już przetłumaczony przez Primo (lang=pl), np. "W realizacji" — pokazujemy dosłownie.
    val status: String,
    // available == "Y". Znaczenie wywnioskowane (egzemplarz czeka na półce odbiorów), nie
    // potwierdzone na żywo — w jedynej zweryfikowanej rezerwacji było "N".
    val available: Boolean,
    val cancellable: Boolean,
    val pickupLocation: String?,
    // yyyyMMdd
    val requestDate: String?,
    val mmsid: String?,
    val accountId: String,
    val ownerName: String,
    // Nazwa z KNOWN_TENANTS — `ilsinstitutionname` dla rezerwacji z sieci OMNIS to etykieta UI
    // Primo ("Sprawdź dostępność w innych bibliotekach"), nie nazwa biblioteki (jak w Loan).
    val tenantName: String
)

/** null, gdy pozycja nie ma ID — bez niego nie da się jej anulować ani jednoznacznie pokazać. */
fun HoldResponseItem.toHold(account: Account): Hold? {
    val id = requestid?.takeIf { it.isNotBlank() } ?: return null
    return Hold(
        id = id,
        title = title ?: "",
        author = author,
        status = holdstatus ?: "",
        available = available == "Y",
        cancellable = cancel == "Y",
        pickupLocation = pickuplocationname,
        requestDate = requestdate,
        mmsid = mmsid,
        accountId = account.id,
        ownerName = account.displayName ?: account.username,
        tenantName = account.tenant.name
    )
}

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

/** Pole katalogu, w którym szuka zapytanie — przekłada się na pierwszy człon Primo `q`. */
enum class SearchField(val primoField: String) {
    ANY("any"),
    AUTHOR("creator"),
    SERIES("series")
}

/**
 * Nazwa serii do wyszukania po polu SERIES, wycięta z `addata.seriestitle`, które niesie też tom i
 * często odpowiedzialność, np. "Garstka z Ustki / Aneta Jadowska ; [t. 1]", "Heksalogia o Dorze
 * Wilk ; 2", "Harry Potter ; T.8". `contains` w Primo wymaga obecności wszystkich słów, więc
 * zostawienie autora gubiłoby tomy, których opis serii go nie podaje. Ukośnik tylko otoczony
 * spacjami, żeby nie ciąć nazw typu "AC/DC".
 */
fun seriesSearchTerm(series: String): String =
    series
        .substringBefore(';')
        .split(Regex("""\s+/\s+"""), limit = 2)
        .first()
        .trim()
        .trimEnd('.', ',', ':')
        .trim()

/**
 * Numer tomu z `addata.seriestitle` — pierwsza liczba po średniku ("… ; [t. 1]" → 1, "… ; T.8" → 8,
 * "… ; rok 2" → 2). null, gdy opis serii nie podaje tomu.
 */
fun seriesVolume(series: String): Int? =
    series
        .substringAfter(';', missingDelimiterValue = "")
        .let { Regex("""\d+""").find(it) }
        ?.value
        ?.toIntOrNull()

/**
 * Autor z API wypożyczeń do wyszukania po polu AUTHOR (`creator`). Bywa w formie z katalogu haseł
 * wzorcowych, z datami i rolą ("Jadowska, Aneta (1981- ). Autor"), a `contains` wymaga wszystkich
 * słów — z datami „Jadowska, Aneta” w Raczyńskich znajduje 44 zamiast 108 pozycji.
 */
fun authorSearchTerm(author: String): String = author.substringBefore(" (").trim().trimEnd(',')

/**
 * Tytuł wypożyczenia bez oznaczenia odpowiedzialności: API podaje pełny opis z katalogu ("Lalka /
 * Bolesław Prus ; posłowie …"), a autor i tak jest w osobnej linii. Ukośnik tylko otoczony
 * spacjami, jak w seriesSearchTerm.
 */
fun displayTitle(title: String): String =
    title
        .split(Regex("""\s+/\s+"""), limit = 2)
        .first()
        .trim()
        .trimEnd('.', ',', ':', ';', '=')
        .trim()
        .ifEmpty { title }

// Status "zwykłego" wypożyczenia (Primo loanstatus, zależnie od języka) — nic nie mówi, więc go
// nie pokazujemy; inne (np. zgubione) zostają widoczne.
private val REGULAR_LOAN_STATUSES = setOf("zwykłe", "active", "normal", "aktywne")

fun isRegularLoanStatus(status: String): Boolean =
    status.trim().lowercase() in REGULAR_LOAN_STATUSES

/** Pozycja lokalnej historii wyszukiwań (AccountManager.getSearchHistory). */
@Serializable
data class SearchHistoryEntry(val query: String, val field: SearchField = SearchField.ANY)

// Wyniki wyszukiwania katalogu — dane efemeryczne (zależne od zapytania), NIE cache'owane
// trwale, więc zwykłe (nie @Serializable) data class'y, w odróżnieniu od Loan/Account.
data class BranchAvailability(
    val libraryName: String, // == Primo holding.mainLocation; to jest etykieta checkboxa filii
    val libraryCode: String,
    val subLocation: String?,
    val status: String, // "available" | "unavailable" | inne
    // Termin zwrotu przychodzi po wynikach (OmnisRepository.fetchDueDates, osobne zapytania per
    // niedostępna filia) — do tego czasu dueDatePending=true, potem kopia z datą (lub bez, gdy
    // Primo jej nie podało albo zapytanie się nie udało).
    val dueDate: String? = null,
    val overdue: Boolean = false,
    // Google Maps — fizyczna lokalizacja na regale. holding.stackMapUrl, patrz Holding.
    val mapsUrl: String? = null,
    val dueDatePending: Boolean = false
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
    val resourceType: String? = null,
    // Wszystkie poniższe pola pochodzą z tej samej odpowiedzi /pnxs, którą repozytorium już
    // pobiera dla title/author/edition powyżej — zero dodatkowych zapytań sieciowych. Port
    // 1:1 pól z client.py::search_books (BookVersion) w omnis-py.
    val series: String? = null,
    val genres: List<String> = emptyList(),
    val subjects: List<String> = emptyList(),
    val language: String? = null,
    val physicalDescription: String? = null,
    // pnx.addata["abstract"] (MARC 520) — bywa wielojęzyczne (kolejne elementy listy), bierzemy
    // pierwszy wpis bez próby rozpoznawania języka (Primo nie taguje tu jednoznacznie języka per
    // element, w odróżnieniu np. od "contributorfull").
    val description: String? = null
) {
    /**
     * Znormalizowany typ nośnika (małe litery) — klucz filtra typów w wyszukiwarce. Brak typu to
     * zwykła książka drukowana, patrz komentarz przy resourceType.
     */
    val mediaType: String
        get() = resourceType?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: MEDIA_TYPE_BOOK

    val isPrintBook: Boolean
        get() = mediaType == MEDIA_TYPE_BOOK
}

const val MEDIA_TYPE_BOOK = "book"

data class SearchResult(
    val frbrgroupid: String?,
    val title: String,
    val author: String?,
    val versions: List<BookVersion>
)

data class SearchPage(
    val results: List<SearchResult>,
    val hasMore: Boolean,
    // Egzemplarze wypożyczone, których termin zwrotu dociąga dopiero fetchDueDates — wyniki
    // pokazujemy wcześniej, bo te zapytania to przy popularnych tytułach większość czasu
    // wyszukiwania (Harry Potter w Raczyńskich: ~22 z ~29 s, pomiar 2026-09-28).
    val dueDateLookups: List<DueDateLookup> = emptyList(),
    val guestToken: String? = null
)

/** Filia `branchIndex` w wydaniu `bareMmsid` (BookVersion.mmsid), czekająca na termin zwrotu. */
data class DueDateLookup(val bareMmsid: String, val branchIndex: Int, val holding: Holding)

data class DueDate(val date: String, val overdue: Boolean)

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
    val holKey: String? = null,
    // Link do Google Maps z fizyczną lokalizacją na regale — pole już przychodzi w tej samej
    // odpowiedzi /pub/delivery, którą OmnisRepository.searchBooks i tak pobiera; do niedawna było
    // tu pomijane (brak pola w tej data class), mimo że omnis-py (client.py BranchAvailability)
    // już je parsuje jako maps_url.
    val stackMapUrl: String? = null
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
