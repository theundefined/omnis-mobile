package com.theundefined.omnis.guard

import com.theundefined.omnis.data.model.BookVersion
import com.theundefined.omnis.data.model.KNOWN_TENANTS
import com.theundefined.omnis.data.model.SearchBranchPrefs
import com.theundefined.omnis.data.model.SearchResult
import com.theundefined.omnis.data.model.Tenant
import com.theundefined.omnis.data.model.searchKey
import kotlinx.serialization.Serializable

// Czysta logika listy „Do przeczytania" (bez Androida): kto trafia na listę, jak z odpowiedzi
// katalogu wyliczyć dostępność z uwzględnieniem preferowanych filii i o czym powiadomić. Worker
// (DueDateWorker), ViewModel i ekran tylko wołają te funkcje.

/** Tyle tytułów można obserwować naraz — każdy to kilka rekordów w zapytaniu do katalogu. */
const val MAX_WATCHED = 30

/**
 * Obserwowany tytuł (na poziomie dzieła, nie wydania). Trwały: nowe pola zawsze z wartością
 * domyślną, żeby stary zapis się wczytał.
 */
@Serializable
data class WatchedTitle(
    /** Patrz [watchId] — klucz biblioteki + grupa FRBR. */
    val id: String,
    /** Cała biblioteka (też własna/custom) — wystarcza do anonimowego zapytania o dostępność. */
    val tenant: Tenant,
    val frbrgroupid: String? = null,
    /**
     * Id rekordów Primo (`control.recordid`, np. "alma99…") wydań z chwili dodania — wejście do
     * `pub/delivery`.
     */
    val recordIds: List<String> = emptyList(),
    /** Gołe mmsid tych wydań (BookVersion.mmsid) — do dopasowań z wynikami wyszukiwania. */
    val mmsids: List<String> = emptyList(),
    val title: String,
    val author: String? = null,
    val addedAt: Long = 0L,
    /** null = jeszcze nie sprawdzano (ani nie zasiano stanem z wyników wyszukiwania). */
    val lastCheckedAt: Long? = null,
    /**
     * Wszystkie filie (mainLocation), w których tytuł ma egzemplarze — bez względu na dostępność.
     */
    val knownBranches: List<String> = emptyList(),
    /**
     * Filie, w których egzemplarz jest dostępny (surowo, bez preferencji — te czytamy na bieżąco).
     */
    val availableBranches: List<String> = emptyList(),
    /**
     * Klucz deduplikacji powiadomień: true = od ostatniego powiadomienia (albo dodania) tytuł był
     * widziany jako niedostępny, więc jego pojawienie się na półce jest nowością. Gaśnie dopiero po
     * faktycznie wysłanym powiadomieniu — nie wysłane (np. brak zgody) ponawiamy przy następnym
     * przebiegu. Ponowne „uzbrojenie" następuje, gdy tytuł znów jest niedostępny.
     */
    val awaitingAvailability: Boolean = false
)

/** Stan jednego egzemplarza w jednej filii, wyczytany z odpowiedzi `pub/delivery`. */
data class BranchStatus(val branch: String, val available: Boolean)

/** Odpowiedź `pub/delivery` dla jednej biblioteki: id rekordu -> jego holdingi (puste = brak). */
typealias LibraryDelivery = Map<String, List<BranchStatus>>

/** Zapytanie o dostępność dla jednej biblioteki: wszystkie rekordy jej obserwowanych tytułów. */
data class LibraryRequest(val tenantKey: String, val tenant: Tenant, val recordIds: List<String>)

/**
 * Identyfikator obserwacji: biblioteka + grupa FRBR (to samo dzieło w różnych wydaniach). Bez grupy
 * — znormalizowany tytuł i autor. Nie zależy od filtrów wyniku (typ nośnika, filie), żeby karta
 * wyszukiwania i lista zawsze liczyły ten sam klucz.
 */
fun watchId(tenantKey: String, frbrgroupid: String?, title: String, author: String?): String {
    val item =
        if (!frbrgroupid.isNullOrBlank()) "g:$frbrgroupid"
        else "t:${title.trim().lowercase()}|${author.orEmpty().trim().lowercase()}"
    return "$tenantKey|$item"
}

fun watchIdOf(tenantKey: String, result: SearchResult): String =
    watchId(tenantKey, result.frbrgroupid, result.title, result.author)

/**
 * Wydania, które obserwujemy: gdy użytkownik filtruje typy nośnika — te z filtra; inaczej książki
 * drukowane, a jeśli ich nie ma — wszystko. Dzięki temu zawsze dostępne e-wydanie nie sprawia, że
 * tytuł „jest dostępny" na zawsze, a audiobook też można obserwować (filtrem).
 */
fun watchedVersions(result: SearchResult, mediaTypes: Set<String>): List<BookVersion> {
    if (mediaTypes.isNotEmpty()) {
        result.versions
            .filter { it.mediaType in mediaTypes }
            .takeIf { it.isNotEmpty() }
            ?.let {
                return it
            }
    }
    val print = result.versions.filter { it.isPrintBook }
    return print.ifEmpty { result.versions }
}

/**
 * Nowa obserwacja z wyniku wyszukiwania (najlepiej pełnego, nieprzefiltrowanego po filiach),
 * zasiana jego stanem — pierwsze sprawdzenie w tle ma więc z czym porównać. null, gdy żadne wydanie
 * nie ma id rekordu, więc nie dałoby się go potem sprawdzić.
 */
fun newWatchedTitle(
    tenant: Tenant,
    result: SearchResult,
    mediaTypes: Set<String>,
    now: Long,
    prefs: SearchBranchPrefs
): WatchedTitle? {
    val versions = watchedVersions(result, mediaTypes)
    val recordIds = versions.mapNotNull { it.almaId }.distinct()
    if (recordIds.isEmpty()) return null
    val key = tenant.searchKey()
    val branches = versions.flatMap { it.branches }
    val known = branches.map { it.libraryName }.filter { it.isNotBlank() }.distinct()
    val available = branches.filter { it.status == "available" }.map { it.libraryName }.distinct()
    val title =
        WatchedTitle(
            id = watchIdOf(key, result),
            tenant = tenant,
            frbrgroupid = result.frbrgroupid,
            recordIds = recordIds,
            mmsids = versions.map { it.mmsid }.distinct(),
            title = result.title,
            author = result.author,
            addedAt = now,
            lastCheckedAt = now,
            knownBranches = known,
            availableBranches = available
        )
    // Dodany jako niedostępny -> powiadomimy o jego pojawieniu się; dodany jako dostępny -> dopiero
    // po tym, jak zniknie i wróci.
    return title.copy(awaitingAvailability = countedBranches(title, prefs, known.toSet()).isEmpty())
}

enum class AddOutcome {
    ADDED,
    ALREADY_WATCHED,
    LIMIT_REACHED
}

/**
 * Dodaje obserwację z poszanowaniem limitu [MAX_WATCHED]; duplikat (to samo id) nic nie zmienia.
 */
fun addWatched(
    list: List<WatchedTitle>,
    item: WatchedTitle,
    limit: Int = MAX_WATCHED
): Pair<List<WatchedTitle>, AddOutcome> =
    when {
        list.any { it.id == item.id } -> list to AddOutcome.ALREADY_WATCHED
        list.size >= limit -> list to AddOutcome.LIMIT_REACHED
        else -> (list + item) to AddOutcome.ADDED
    }

/**
 * Filie, które liczą się jako „dostępne". Tak jak w wynikach wyszukiwania: wybór użytkownika
 * przecinamy ze znanymi filiami biblioteki ([libraryBranches] — nazwy faktycznie widziane w
 * holdingach jej obserwowanych tytułów), a filtr działa tylko przy niepustym przecięciu
 * (`!showAllBranches`). Wybór, którego żadna znana filia nie potwierdza (np. nazwa spoza
 * obserwowanych tytułów), nie wycina więc wszystkiego — liczą się wtedy wszystkie filie.
 */
fun countedBranches(
    title: WatchedTitle,
    prefs: SearchBranchPrefs,
    libraryBranches: Set<String>
): List<String> {
    val effective = prefs.selectedBranches.intersect(libraryBranches)
    val filter = !prefs.showAllBranches && effective.isNotEmpty()
    return if (filter) title.availableBranches.filter { it in effective }
    else title.availableBranches
}

/** Filie znane z holdingów obserwowanych tytułów danej biblioteki (po `searchKey`). */
fun libraryBranchesOf(titles: List<WatchedTitle>, tenantKey: String): Set<String> =
    titles
        .filter { it.tenant.searchKey() == tenantKey }
        .flatMapTo(mutableSetOf()) { it.knownBranches }

/** [countedBranches] z preferencjami i znanymi filiami biblioteki tytułu (z listy [all]). */
fun countedBranchesIn(
    title: WatchedTitle,
    prefs: Map<String, SearchBranchPrefs>,
    all: List<WatchedTitle>
): List<String> {
    val key = title.tenant.searchKey()
    return countedBranches(title, prefs[key] ?: SearchBranchPrefs(), libraryBranchesOf(all, key))
}

enum class WatchStatus {
    NOT_CHECKED,
    AVAILABLE,
    /** Są egzemplarze, ale (w wybranych filiach) wszystkie wypożyczone. */
    UNAVAILABLE,
    /** Katalog nie pokazuje żadnego egzemplarza (np. tylko wydanie elektroniczne). */
    NO_COPIES
}

/** Wiersz ekranu listy: tytuł, stan i filie, w których można go teraz wziąć. */
data class WatchRow(val title: WatchedTitle, val status: WatchStatus, val branches: List<String>)

fun watchRows(titles: List<WatchedTitle>, prefs: Map<String, SearchBranchPrefs>): List<WatchRow> =
    titles
        .map { title ->
            val branches = countedBranchesIn(title, prefs, titles)
            val status =
                when {
                    title.lastCheckedAt == null -> WatchStatus.NOT_CHECKED
                    branches.isNotEmpty() -> WatchStatus.AVAILABLE
                    title.knownBranches.isEmpty() -> WatchStatus.NO_COPIES
                    else -> WatchStatus.UNAVAILABLE
                }
            WatchRow(title, status, branches)
        }
        // Najpierw dostępne, potem najnowsze dodane.
        .sortedWith(
            compareByDescending<WatchRow> { it.status == WatchStatus.AVAILABLE }
                .thenByDescending { it.title.addedAt }
        )

/**
 * Zapytania do katalogu: JEDNO na bibliotekę (wszystkie rekordy jej tytułów razem). Bierzemy wpis z
 * KNOWN_TENANTS o tym samym searchKey, jeśli jest — świeższy baseUrl niż zapisany przy dodawaniu.
 */
fun libraryRequests(titles: List<WatchedTitle>): List<LibraryRequest> =
    titles
        .groupBy { it.tenant.searchKey() }
        .map { (key, group) ->
            LibraryRequest(
                tenantKey = key,
                tenant =
                    KNOWN_TENANTS.firstOrNull { it.searchKey() == key } ?: group.first().tenant,
                recordIds = group.flatMap { it.recordIds }.distinct()
            )
        }

private data class Snapshot(val known: List<String>, val available: List<String>)

/**
 * Stan tytułu z odpowiedzi biblioteki. null = żadnego z jego rekordów nie ma w odpowiedzi, więc nic
 * nie wiemy i stan zostaje bez zmian (nie „niedostępny").
 */
private fun snapshotOf(title: WatchedTitle, delivery: LibraryDelivery): Snapshot? {
    val present = title.recordIds.mapNotNull { delivery[it] }
    if (present.isEmpty()) return null
    val holdings = present.flatten().filter { it.branch.isNotBlank() }
    return Snapshot(
        known = holdings.map { it.branch }.distinct(),
        available = holdings.filter { it.available }.map { it.branch }.distinct()
    )
}

data class WatchCheckOutcome(
    val titles: List<WatchedTitle>,
    /** Tytuły, o których teraz trzeba powiadomić (ich flaga gaśnie dopiero w [markNotified]). */
    val toNotify: List<WatchedTitle>,
    /** Filie liczone dla każdego z [toNotify] (po id) — do treści powiadomienia. */
    val notifyBranches: Map<String, List<String>> = emptyMap()
)

/**
 * Nakłada wyniki sprawdzenia na AKTUALNĄ listę (czytaną tuż przed zapisem, więc dodania i usunięcia
 * zrobione w czasie pobierania nie przepadają, a usunięty tytuł nie wraca). `deliveries` ma wpisy
 * tylko dla bibliotek, które odpowiedziały — błąd sieci dla biblioteki = jej tytuły bez zmian i bez
 * powiadomienia.
 *
 * `notify = true` (przebieg w tle): tytuł dostępny w liczonych filiach, który był „uzbrojony",
 * trafia do `toNotify`. `notify = false` (ręczne odświeżenie): użytkownik właśnie widzi stan, więc
 * flaga gaśnie bez powiadomienia.
 */
fun applyChecks(
    current: List<WatchedTitle>,
    deliveries: Map<String, LibraryDelivery>,
    prefs: Map<String, SearchBranchPrefs>,
    now: Long,
    notify: Boolean
): WatchCheckOutcome {
    val observed = mutableSetOf<String>()
    val updated =
        current.map { title ->
            val delivery = deliveries[title.tenant.searchKey()] ?: return@map title
            val snapshot = snapshotOf(title, delivery) ?: return@map title
            observed += title.id
            title.copy(
                lastCheckedAt = now,
                knownBranches = snapshot.known,
                availableBranches = snapshot.available
            )
        }
    val toNotify = mutableListOf<WatchedTitle>()
    val notifyBranches = mutableMapOf<String, List<String>>()
    val result =
        updated.map { title ->
            if (title.id !in observed) return@map title
            val counted = countedBranchesIn(title, prefs, updated)
            when {
                counted.isEmpty() -> title.copy(awaitingAvailability = true)
                !title.awaitingAvailability -> title
                notify -> {
                    toNotify += title
                    notifyBranches[title.id] = counted
                    title
                }
                else -> title.copy(awaitingAvailability = false)
            }
        }
    return WatchCheckOutcome(result, toNotify, notifyBranches)
}

/** Po faktycznie wysłanym powiadomieniu: wygasza flagę wskazanych tytułów (po id). */
fun markNotified(current: List<WatchedTitle>, ids: Set<String>): List<WatchedTitle> =
    current.map { if (it.id in ids) it.copy(awaitingAvailability = false) else it }

/** Usuwa obserwację po id; nieistniejąca nic nie zmienia. */
fun removeWatched(current: List<WatchedTitle>, id: String): List<WatchedTitle> =
    current.filterNot { it.id == id }
