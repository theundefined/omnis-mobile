package com.theundefined.omnis.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.theundefined.omnis.R
import com.theundefined.omnis.data.local.ViewPrefs
import com.theundefined.omnis.data.model.Account
import com.theundefined.omnis.data.model.BookVersion
import com.theundefined.omnis.data.model.BranchInfo
import com.theundefined.omnis.data.model.DueDate
import com.theundefined.omnis.data.model.DueDateLookup
import com.theundefined.omnis.data.model.HistoryCacheEntry
import com.theundefined.omnis.data.model.Hold
import com.theundefined.omnis.data.model.KNOWN_TENANTS
import com.theundefined.omnis.data.model.Loan
import com.theundefined.omnis.data.model.PickupLocation
import com.theundefined.omnis.data.model.SearchBranchPrefs
import com.theundefined.omnis.data.model.SearchField
import com.theundefined.omnis.data.model.SearchHistoryEntry
import com.theundefined.omnis.data.model.SearchPage
import com.theundefined.omnis.data.model.SearchResult
import com.theundefined.omnis.data.model.Tenant
import com.theundefined.omnis.data.model.pickHoldableItem
import com.theundefined.omnis.data.model.searchKey
import com.theundefined.omnis.data.model.seriesSearchTerm
import com.theundefined.omnis.data.model.seriesVolume
import com.theundefined.omnis.data.remote.PlaceGeocoder
import com.theundefined.omnis.data.repository.OmnisRepository
import java.text.Collator
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class UiState(
    val accounts: List<Account> = emptyList(),
    val loans: Map<String, List<Loan>> = emptyMap(), // groupKey -> loans
    val groupingMode: GroupingMode = GroupingMode.ACCOUNT,
    val sortMode: SortMode = SortMode.DUE_DATE,
    val isLoading: Boolean = false,
    val error: String? = null,
    val refreshProgress: RefreshProgress? = null
)

/**
 * Postęp odświeżania wypożyczeń (refreshAllLoans). Konta idą po kolei; dla każdego najpierw
 * logowanie i wypożyczenia, potem — tylko jeśli są książki jeszcze niesprawdzone w katalogu —
 * pobieranie serii (catalogTotal != null). Pasek wypełnia się osobno w każdym z tych etapów.
 */
data class RefreshProgress(
    val account: Int, // 1-based
    val accounts: Int,
    val accountName: String,
    val catalogDone: Int = 0,
    val catalogTotal: Int? = null
) {
    val fraction: Float
        get() =
            if (catalogTotal != null && catalogTotal > 0) catalogDone.toFloat() / catalogTotal
            else (account - 1).toFloat() / accounts
}

data class HistoryUiState(
    val loans: Map<String, List<Loan>> = emptyMap(), // groupKey -> loans
    val groupingMode: GroupingMode = GroupingMode.ACCOUNT,
    val sortMode: SortMode = SortMode.LOAN_DATE,
    val isLoading: Boolean = false, // initial load / forced full refresh
    val isLoadingMore: Boolean = false, // incremental "load more" page in flight
    val hasLoadedOnce: Boolean = false,
    val canLoadMore: Boolean = false,
    val error: String? = null
)

/**
 * Okienko z informacją o filii (po kliknięciu jej nazwy). `info == null` po zakończeniu ładowania =
 * Primo nic nie podało — UI i tak oferuje wyszukanie filii w mapach po nazwie.
 */
data class BranchDialogState(
    val branchName: String,
    val tenantName: String?,
    val isLoading: Boolean = false,
    val info: BranchInfo? = null
)

/**
 * Rezerwacje wszystkich włączonych kont. Trzymane tylko w pamięci (bez trwałego cache'u jak przy
 * wypożyczeniach): status rezerwacji zmienia się bez udziału użytkownika (np. "do odbioru"), więc
 * nieaktualna lista z dysku wprowadzałaby w błąd. `cancellingIds` to klucze [holdKey] pozycji, dla
 * których trwa anulowanie.
 */
data class HoldsUiState(
    val holds: Map<String, List<Hold>> = emptyMap(), // ownerName -> holds
    val isLoading: Boolean = false,
    val hasLoadedOnce: Boolean = false,
    val cancellingIds: Set<String> = emptySet(),
    val error: String? = null
)

/**
 * Grupuje rezerwacje po właścicielu; w grupie najpierw te gotowe do odbioru, potem od najnowszej
 * (requestDate to yyyyMMdd, więc porównanie tekstowe = chronologiczne).
 */
fun groupHolds(holds: List<Hold>): Map<String, List<Hold>> =
    holds
        .groupBy { it.ownerName }
        .toSortedMap(compareBy(Collator.getInstance(Locale("pl"))) { it })
        .mapValues { (_, group) ->
            group.sortedWith(
                compareByDescending<Hold> { it.available }.thenByDescending { it.requestDate ?: "" }
            )
        }

private val HOLD_CONFIRM_DELAYS_MS = listOf(1000L, 2000L, 3000L, 5000L)

/** Kursor paginacji historii pojedynczego konta — dokąd doszliśmy i czy jest więcej stron. */
private data class HistoryCursor(val nextOffset: Int, val hasMore: Boolean)

data class SearchUiState(
    val query: String = "",
    val field: SearchField = SearchField.ANY,
    val isLoading: Boolean = false,
    val hasSearched: Boolean = false,
    val tenantSections: List<SearchTenantSection> = emptyList(),
    // Zaznaczone typy nośnika (BookVersion.mediaType), wspólne dla wszystkich bibliotek i
    // zapamiętywane między wyszukiwaniami. Puste = wszystkie typy.
    val selectedMediaTypes: Set<String> = emptySet()
)

/** Typy nośnika obecne w wynikach — chipy filtra typów; kolejność pierwszego wystąpienia. */
fun SearchUiState.availableMediaTypes(): List<String> =
    tenantSections
        .flatMap { section -> section.results.flatMap { r -> r.versions.map { it.mediaType } } }
        .distinct()

/**
 * Zaznaczone typy, które faktycznie są w wynikach — jak przy filiach (patrz SearchTenantSection):
 * zapamiętany typ, którego w bieżących wynikach nie ma, nie może wyzerować listy, więc wtedy
 * efektywnie nie filtrujemy.
 */
fun SearchUiState.effectiveMediaTypes(): Set<String> =
    selectedMediaTypes.intersect(availableMediaTypes().toSet())

/** Biblioteki do wyboru na ekranie wyszukiwania + te, w których faktycznie szukamy. */
data class SearchLibrariesState(
    val available: List<Tenant> = emptyList(),
    val selectedKeys: Set<String> = emptySet()
) {
    val selected: List<Tenant>
        get() = available.filter { it.searchKey() in selectedKeys }
}

/**
 * Lista do wyboru: znane biblioteki + biblioteki kont spoza listy (np. demo). Przy tym samym
 * searchKey wygrywa wpis z KNOWN_TENANTS (świeższe baseUrl niż zapisany w starym koncie). Domyślny
 * wybór, dopóki użytkownik go nie zmieni: biblioteki włączonych kont.
 */
internal fun buildSearchLibrariesState(
    accounts: List<Account>,
    override: Set<String>?
): SearchLibrariesState {
    val available = (KNOWN_TENANTS + accounts.map { it.tenant }).distinctBy { it.searchKey() }
    val keys = available.map { it.searchKey() }.toSet()
    val selected =
        override ?: accounts.filter { it.isEnabled }.map { it.tenant.searchKey() }.toSet()
    return SearchLibrariesState(available, selected intersect keys)
}

/** Sortowanie wyników wyszukiwania w obrębie jednej biblioteki — czysto klient-side. */
enum class SearchSortMode {
    RELEVANCE, // kolejność zwrócona przez Primo (parametr sort=rank) — bez zmian
    TITLE,
    SERIES // nazwa serii, potem numer tomu; domyślne przy wyszukiwaniu po serii
}

/**
 * Wyniki + stan filtra filii dla JEDNEJ unikalnej biblioteki (Tenant.searchKey()) — patrz
 * docs/plans/book-search.md §8. `confirmedBranches` (realne holding.mainLocation z odpowiedzi
 * wyszukiwania) i `seededBranches` (podpowiedź z Loan.locationName w cache'u wypożyczeń) są celowo
 * rozdzielone: filtrowanie (filteredResults()) liczy się TYLKO po przecięciu selectedBranches z
 * confirmedBranches, więc niepotwierdzona (jeszcze) nazwa z seedu nigdy nie potrafi wyzerować
 * wyników — jeśli żadna zaznaczona filia nie została jeszcze potwierdzona przez realne
 * wyszukiwanie, efektywnie nie filtrujemy, zamiast pokazać pustą listę.
 */
data class SearchTenantSection(
    val tenantKey: String,
    val tenantLabel: String,
    val results: List<SearchResult> =
        emptyList(), // pełne, nieprzefiltrowane, bieżąca + doładowane strony
    val confirmedBranches: List<String> = emptyList(),
    val seededBranches: List<String> = emptyList(),
    // libraryName -> pierwszy napotkany subLocation dla tej filii (patrz branchAddressesOf) —
    // wyłącznie do WYŚWIETLENIA obok nazwy filii w chipie. Klucz filtrowania/persystencji
    // (selectedBranches, confirmedBranches) zostaje surową nazwą filii, żeby dołożenie adresu do
    // etykiety nie rozjechało `branch in selectedBranches` ani zapisanych wcześniej preferencji.
    val branchAddresses: Map<String, String> = emptyMap(),
    val selectedBranches: Set<String> = emptySet(),
    val showAllBranches: Boolean = true,
    val sortMode: SearchSortMode = SearchSortMode.RELEVANCE,
    val isLoading: Boolean = false,
    val error: String? = null,
    val nextOffset: Int = 0,
    val canLoadMore: Boolean = false,
    val isLoadingMore: Boolean = false
)

/** Suma potwierdzonych i sugerowanych nazw filii — do wyświetlenia jako checkboxy w UI. */
fun SearchTenantSection.checkboxBranches(): List<String> =
    (confirmedBranches + seededBranches).distinct()

/** Etykieta chipa filii: nazwa + adres (subLocation), gdy już go znamy z wyników. */
fun SearchTenantSection.branchChipLabel(branch: String): String =
    branchAddresses[branch]?.let { "$branch ($it)" } ?: branch

private val polishCollator: Collator =
    Collator.getInstance(Locale("pl")).apply { strength = Collator.PRIMARY }

/** `mediaTypes` — SearchUiState.effectiveMediaTypes(); puste = bez filtra typów. */
fun SearchTenantSection.filteredResults(mediaTypes: Set<String> = emptySet()): List<SearchResult> {
    val effectiveSelection = selectedBranches.intersect(confirmedBranches.toSet())
    val filterBranches = !showAllBranches && effectiveSelection.isNotEmpty()
    val filterTypes = mediaTypes.isNotEmpty()
    val base =
        if (!filterBranches && !filterTypes) results
        else
            results.mapNotNull { result ->
                val versions =
                    result.versions.mapNotNull { v ->
                        if (filterTypes && v.mediaType !in mediaTypes) return@mapNotNull null
                        if (!filterBranches) return@mapNotNull v
                        val branches = v.branches.filter { it.libraryName in effectiveSelection }
                        if (branches.isEmpty()) null else v.copy(branches = branches)
                    }
                if (versions.isEmpty()) null else result.copy(versions = versions)
            }
    return when (sortMode) {
        SearchSortMode.RELEVANCE -> base
        SearchSortMode.TITLE -> base.sortedWith(compareBy(polishCollator) { it.title })
        SearchSortMode.SERIES -> sortBySeries(base)
    }
}

/**
 * Wpisuje termin zwrotu (albo jego brak) w filię wskazaną przez `lookup` — wszędzie, gdzie w
 * wynikach występuje to wydanie. Pozostałe elementy zostają tymi samymi obiektami.
 */
internal fun List<SearchResult>.withDueDate(
    lookup: DueDateLookup,
    dueDate: DueDate?
): List<SearchResult> = map { result ->
    if (result.versions.none { it.mmsid == lookup.bareMmsid }) result
    else
        result.copy(
            versions =
                result.versions.map { version ->
                    if (version.mmsid != lookup.bareMmsid) version
                    else
                        version.copy(
                            branches =
                                version.branches.mapIndexed { index, branch ->
                                    if (index != lookup.branchIndex) branch
                                    else
                                        branch.copy(
                                            dueDate = dueDate?.date,
                                            overdue = dueDate?.overdue ?: false,
                                            dueDatePending = false
                                        )
                                }
                        )
                }
        )
}

/**
 * Seria (bez tomu i odpowiedzialności, jak w wyszukiwaniu po serii), potem numer tomu, potem tytuł.
 * Książki bez serii i tomy bez numeru trafiają na koniec swojej grupy.
 */
internal fun sortBySeries(results: List<SearchResult>): List<SearchResult> {
    fun seriesOf(result: SearchResult) = result.versions.firstNotNullOfOrNull { it.series }
    return results.sortedWith(
        compareBy<SearchResult> { seriesOf(it) == null }
            .thenBy(polishCollator) { seriesOf(it)?.let(::seriesSearchTerm).orEmpty() }
            .thenBy { seriesOf(it)?.let(::seriesVolume) ?: Int.MAX_VALUE }
            .thenBy(polishCollator) { it.title }
    )
}

/**
 * Czysta funkcja (bez coroutines/Context) licząca podsumowanie zbiorczego przedłużania — wydzielona
 * spoza `OmnisViewModel`, żeby dało się ją pokryć testem jednostkowym bez mockowania repozytorium.
 */
fun summarizeRenewResults(
    results: List<Pair<Loan, Result<Unit>>>
): OmnisViewModel.UiEvent.BulkRenewFinished {
    val succeeded = results.count { it.second.isSuccess }
    val failedTitles = results.filter { it.second.isFailure }.map { it.first.title }
    return OmnisViewModel.UiEvent.BulkRenewFinished(succeeded, failedTitles)
}

class OmnisViewModel(application: Application, private val repository: OmnisRepository) :
    AndroidViewModel(application) {

    private val viewPrefs = ViewPrefs(application)

    private val _uiState =
        MutableStateFlow(
            UiState(
                groupingMode = viewPrefs.get(PREF_LOANS_GROUPING, GroupingMode.ACCOUNT),
                sortMode = viewPrefs.get(PREF_LOANS_SORT, SortMode.DUE_DATE)
            )
        )
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _historyUiState =
        MutableStateFlow(
            HistoryUiState(
                groupingMode = viewPrefs.get(PREF_HISTORY_GROUPING, GroupingMode.ACCOUNT),
                sortMode = viewPrefs.get(PREF_HISTORY_SORT, SortMode.LOAN_DATE)
            )
        )
    val historyUiState: StateFlow<HistoryUiState> = _historyUiState.asStateFlow()

    // Płaska lista wszystkich dotąd pobranych/wczytanych z cache'u pozycji historii — trzymana
    // tylko w pamięci, żeby przegrupowanie/przesortowanie nie wymagało ponownego fetchu.
    private var historyFlatLoans: List<Loan> = emptyList()
    private var historyCursors: Map<String, HistoryCursor> = emptyMap()

    // Zwiększane przy każdym resetHistoryState() (zmiana zestawu kont). loadHistory()/
    // loadMoreHistory() zapamiętują generację przy starcie i porzucają wyniki, jeśli w
    // międzyczasie (np. użytkownik przełączył konto na ekranie Ustawień, gdy fetch historii
    // wciąż trwał w tle) zmieniła się ona pod nogami — inaczej wyniki policzone dla starego
    // zestawu kont mogłyby wylądować w świeżo wyczyszczonym stanie.
    private var historyGeneration = 0

    private val _holdsUiState = MutableStateFlow(HoldsUiState())
    val holdsUiState: StateFlow<HoldsUiState> = _holdsUiState.asStateFlow()
    private var holdsFlat: List<Hold> = emptyList()
    // Jak historyGeneration — porzuca wyniki policzone dla starego zestawu kont.
    private var holdsGeneration = 0

    // Rezerwacje do odbioru, dla których użytkownik zamknął baner na ekranie głównym — patrz
    // readyHoldBannerToken: nowa rezerwacja na półce albo zbliżający się termin pokazują go znowu.
    private val _dismissedReadyHolds =
        MutableStateFlow(viewPrefs.getStringSet(PREF_READY_HOLDS_DISMISSED))
    val dismissedReadyHolds: StateFlow<Set<String>> = _dismissedReadyHolds.asStateFlow()

    private val _searchUiState =
        MutableStateFlow(
            SearchUiState(selectedMediaTypes = viewPrefs.getStringSet(PREF_SEARCH_MEDIA_TYPES))
        )
    val searchUiState: StateFlow<SearchUiState> = _searchUiState.asStateFlow()

    // Biblioteki OSTATNIEGO wyszukania — loadMoreResults() doładowuje kolejne strony z tej samej
    // biblioteki, nawet jeśli w międzyczasie zmienił się wybór bibliotek.
    private var searchTargets: Map<String, Tenant> = emptyMap()

    // Zwiększane przy każdym runSearch() — analogicznie do historyGeneration (patrz wyżej),
    // chroni przed sytuacją, w której użytkownik odpala drugie wyszukanie zanim pierwsze
    // zdążyło wrócić z sieci: wynik "spóźnionego" pierwszego wyszukania jest wtedy porzucany
    // zamiast nadpisać świeżo ustawiony szkielet drugiego.
    private var searchGeneration = 0
    // Dociąganie terminów zwrotu po pokazaniu wyników — anulowane przy nowym wyszukaniu, żeby nie
    // obciążać serwera zapytaniami, których wyników nikt już nie zobaczy.
    private val dueDateJobs = mutableListOf<Job>()

    private fun cancelDueDateJobs() {
        dueDateJobs.forEach { it.cancel() }
        dueDateJobs.clear()
    }

    private fun launchDueDates(tenant: Tenant, page: SearchPage, generation: Int) {
        if (page.dueDateLookups.isEmpty()) return
        val tenantKey = tenant.searchKey()
        dueDateJobs +=
            viewModelScope.launch {
                repository.fetchDueDates(tenant, page) { lookup, dueDate ->
                    if (generation == searchGeneration) {
                        updateSearchSection(tenantKey) {
                            it.copy(results = it.results.withDueDate(lookup, dueDate))
                        }
                    }
                }
            }
    }

    private val _searchTenantOverride = MutableStateFlow(repository.getSearchTenantKeys())
    val searchLibraries: StateFlow<SearchLibrariesState> =
        combine(_uiState, _searchTenantOverride) { ui, override ->
                buildSearchLibrariesState(ui.accounts, override)
            }
            .stateIn(
                viewModelScope,
                SharingStarted.Eagerly,
                buildSearchLibrariesState(_uiState.value.accounts, _searchTenantOverride.value)
            )

    private val _searchHistory = MutableStateFlow(repository.getSearchHistory())
    val searchHistory: StateFlow<List<SearchHistoryEntry>> = _searchHistory.asStateFlow()

    private val _branchDialog = MutableStateFlow<BranchDialogState?>(null)
    val branchDialog: StateFlow<BranchDialogState?> = _branchDialog.asStateFlow()
    private var branchDialogJob: Job? = null

    // Mapa filii dla jednego wyniku wyszukiwania (SearchScreen).
    private val _branchMap = MutableStateFlow<BranchMapState?>(null)
    val branchMap: StateFlow<BranchMapState?> = _branchMap.asStateFlow()
    private var branchMapJob: Job? = null
    private val placeGeocoder by lazy { PlaceGeocoder(getApplication<Application>()) }

    private val _holdPlacement = MutableStateFlow<HoldPlacementState?>(null)
    val holdPlacement: StateFlow<HoldPlacementState?> = _holdPlacement.asStateFlow()
    private var holdSession: OmnisRepository.HoldSession? = null
    private var holdPrepareJob: Job? = null
    // Id rezerwacji konta sprzed złożenia — po nich poznajemy nową (odpowiedź Primo nie niesie ID).
    private var holdBaseline: Set<String> = emptySet()
    // Jak searchGeneration — wynik spóźnionego kroku nie trafi do okna innej rezerwacji.
    private var holdPlacementGeneration = 0

    private val _events = MutableSharedFlow<UiEvent>()
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

    sealed class UiEvent {
        object AccountAdded : UiEvent()

        /** `error == null` = rezerwacja anulowana. */
        data class HoldCancelFinished(val title: String, val error: String?) : UiEvent()

        data class BulkRenewFinished(val succeeded: Int, val failedTitles: List<String>) :
            UiEvent()
    }

    init {
        refreshAccounts()
        loadCachedLoans()
        refreshAllLoans(isManual = false)
    }

    fun refreshAccounts() {
        val accounts = repository.getAccounts()
        _uiState.update { it.copy(accounts = accounts) }
    }

    fun loadCachedLoans() {
        val currentAccounts = _uiState.value.accounts.filter { it.isEnabled }
        val allLoansList = mutableListOf<Pair<Account, Loan>>()

        currentAccounts.forEach { account ->
            val cachedLoans = repository.getCachedLoans(account.id)
            cachedLoans.forEach { allLoansList.add(account to it) }
        }
        updateGroupedLoans(allLoansList, false)
    }

    fun refreshAllLoans(isManual: Boolean = true) {
        viewModelScope.launch {
            if (isManual) {
                _uiState.update { it.copy(isLoading = true, error = null) }
            }

            val currentAccounts = _uiState.value.accounts.filter { it.isEnabled }
            val allLoansList = mutableListOf<Pair<Account, Loan>>()
            var hasError = false
            var errorMessage: String? = null

            currentAccounts.forEachIndexed { index, account ->
                val progress =
                    RefreshProgress(
                        account = index + 1,
                        accounts = currentAccounts.size,
                        accountName = account.displayName ?: account.username
                    )
                _uiState.update { it.copy(refreshProgress = progress) }
                if (account.displayName == null || account.displayName == account.username) {
                    repository.fetchAccountProfile(account)
                }

                repository
                    .getLoansForAccount(account)
                    .onSuccess { fetched ->
                        val loans =
                            repository.withCatalogDetails(
                                account,
                                fetched,
                                repository.getCachedLoans(account.id)
                            ) { done, total ->
                                _uiState.update {
                                    it.copy(
                                        refreshProgress =
                                            progress.copy(catalogDone = done, catalogTotal = total)
                                    )
                                }
                            }
                        repository.saveCachedLoans(account.id, loans)
                        loans.forEach { allLoansList.add(account to it) }
                    }
                    .onFailure {
                        hasError = true
                        errorMessage = it.message
                        // Use cached loans if network fails
                        val cachedLoans = repository.getCachedLoans(account.id)
                        cachedLoans.forEach { loan -> allLoansList.add(account to loan) }
                    }
            }

            val updatedAccounts = repository.getAccounts()
            _uiState.update { it.copy(accounts = updatedAccounts, refreshProgress = null) }

            updateGroupedLoans(allLoansList, false)

            if (hasError && isManual) {
                _uiState.update {
                    it.copy(
                        error =
                            errorMessage
                                ?: getApplication<Application>().getString(R.string.refresh_error)
                    )
                }
            }
        }
    }

    private fun updateGroupedLoans(allLoans: List<Pair<Account, Loan>>, isLoading: Boolean) {
        val grouped =
            groupAndSortLoans(
                allLoans.map { it.second },
                uiState.value.groupingMode,
                uiState.value.sortMode,
                allGroupLabel()
            )
        _uiState.update { it.copy(loans = grouped, isLoading = isLoading) }
    }

    private fun allGroupLabel(): String =
        getApplication<Application>().getString(R.string.group_all_header)

    fun setGroupingMode(mode: GroupingMode) {
        viewPrefs.put(PREF_LOANS_GROUPING, mode)
        _uiState.update { it.copy(groupingMode = mode) }
        reapplyGroupingAndSorting()
    }

    fun setSortMode(mode: SortMode) {
        viewPrefs.put(PREF_LOANS_SORT, mode)
        _uiState.update { it.copy(sortMode = mode) }
        reapplyGroupingAndSorting()
    }

    private fun reapplyGroupingAndSorting() {
        val currentAccounts = _uiState.value.accounts.filter { it.isEnabled }
        val allLoansList = mutableListOf<Pair<Account, Loan>>()

        currentAccounts.forEach { account ->
            // In a real app we might store current loaded loans, but we can also just use cached
            // loans
            // OR we can flatten current uiState.loans
            val loansForAccount =
                _uiState.value.loans.values.flatten().filter { it.accountId == account.id }
            if (loansForAccount.isNotEmpty()) {
                loansForAccount.forEach { allLoansList.add(account to it) }
            } else {
                val cachedLoans = repository.getCachedLoans(account.id)
                cachedLoans.forEach { allLoansList.add(account to it) }
            }
        }
        updateGroupedLoans(allLoansList, _uiState.value.isLoading)
    }

    /**
     * Otwiera okienko filii dla wypożyczeń z JEDNEJ filii (karta albo nagłówek grupy filii).
     * Rekordy próbujemy po kolei (max 3), bo nie każdy musi mieć holding w tej filii — np.
     * egzemplarz sprowadzony z innej biblioteki sieci.
     */
    fun showBranchInfo(loans: List<Loan>) {
        val first = loans.firstOrNull() ?: return
        openBranchInfo(
            branchName = first.locationName,
            tenantName = first.tenantName,
            accountId = first.accountId,
            mmsids = loans.map { it.mmsid }
        )
    }

    /**
     * Okienko filii odbioru rezerwacji. Adres bierzemy z holdingów zarezerwowanego rekordu — gdy
     * egzemplarz jedzie z innej filii, odbiorczej tam nie będzie i zostaje wyszukanie w mapach (o
     * ile adresu nie ma już w cache'u z wypożyczeń).
     */
    fun showBranchInfo(hold: Hold) {
        val branchName = hold.pickupLocation?.takeIf { it.isNotBlank() } ?: return
        openBranchInfo(
            branchName = branchName,
            tenantName = hold.tenantName,
            accountId = hold.accountId,
            mmsids = listOfNotNull(hold.mmsid?.takeIf { it.isNotBlank() })
        )
    }

    private fun openBranchInfo(
        branchName: String,
        tenantName: String?,
        accountId: String?,
        mmsids: List<String>
    ) {
        val account = _uiState.value.accounts.firstOrNull { it.id == accountId }
        // Serwer demo nie ma tego endpointu (i bywa wolny) — od razu pokazujemy wariant bez danych.
        val fetchAccount =
            account?.takeIf { !it.isDemo && !it.tenant.isDemo && mmsids.isNotEmpty() }
        branchDialogJob?.cancel()
        _branchDialog.value =
            BranchDialogState(
                branchName = branchName,
                tenantName = tenantName ?: account?.tenant?.name,
                isLoading = fetchAccount != null
            )
        if (fetchAccount == null) return
        branchDialogJob =
            viewModelScope.launch {
                var info: BranchInfo? = null
                for (mmsid in mmsids.distinct().take(3)) {
                    info =
                        repository
                            .getBranchInfo(
                                fetchAccount.tenant,
                                mmsid,
                                branchName,
                                fetchAccount.timeoutSeconds
                            )
                            .getOrNull()
                    // getBranchInfo łapie Exception, więc połyka też anulowanie — bez tego
                    // porzucony job (dialog zamknięty/otwarty dla innej filii) nadpisałby nowy
                    // stan.
                    ensureActive()
                    if (info != null) break
                }
                _branchDialog.update { it?.copy(isLoading = false, info = info) }
            }
    }

    fun dismissBranchInfo() {
        branchDialogJob?.cancel()
        _branchDialog.value = null
    }

    /**
     * Otwiera mapę wszystkich filii, w których jest [result] (już po filtrze filii z ekranu
     * wyszukiwania). Pinezki dochodzą w miarę ustalania położenia kolejnych filii.
     */
    fun showBranchMap(result: SearchResult, tenantName: String?) {
        val branches = mapBranchesOf(result, tenantName)
        branchMapJob?.cancel()
        _branchMap.value = BranchMapState(title = result.title, branches = branches)
        branchMapJob =
            viewModelScope.launch {
                repository.resolveBranchLocations(
                    branches.map { it.location },
                    placeGeocoder::locate
                ) { key, coordinates ->
                    // Porównanie tożsamości: wynik spóźnionego zapytania dla wcześniej
                    // otwartej mapy nie może trafić do nowej.
                    _branchMap.update { state ->
                        if (state?.branches === branches)
                            state.copy(coordinates = state.coordinates + (key to coordinates))
                        else state
                    }
                }
            }
    }

    fun dismissBranchMap() {
        branchMapJob?.cancel()
        _branchMap.value = null
    }

    fun toggleAccount(account: Account) {
        val updated = account.copy(isEnabled = !account.isEnabled)
        repository.updateAccount(updated)
        refreshAccounts()
        resetHistoryState()
        resetHoldsState()
        onAccountSetChanged()
    }

    /**
     * Ręczny numer karty bibliotecznej. Pusty albo równy loginowi kasuje nadpisanie — wtedy numer
     * znów wynika z loginu. Czytamy konto na świeżo z pamięci, żeby nie nadpisać zmian z
     * równoległego odświeżania profilu (kary, liczba wypożyczeń).
     */
    fun setCardNumber(account: Account, number: String) {
        val current = repository.getAccounts().find { it.id == account.id } ?: return
        val trimmed = number.trim()
        val override = trimmed.takeIf { it.isNotEmpty() && it != current.username }
        repository.updateAccount(current.copy(cardNumber = override))
        refreshAccounts()
    }

    fun addAccount(username: String, password: String, tenant: Tenant) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            repository
                .loginAndAddAccount(username, password, tenant)
                .onSuccess {
                    refreshAccounts()
                    resetHistoryState()
                    resetHoldsState()
                    onAccountSetChanged()
                    _events.emit(UiEvent.AccountAdded)
                }
                .onFailure { e ->
                    _uiState.update { it.copy(isLoading = false, error = e.message) }
                }
        }
    }

    /**
     * Zestaw włączonych kont się zmienił (toggle, dodanie/usunięcie konta, wejście/wyjście z trybu
     * demo) — lista wypożyczeń na ekranie głównym trzyma płaski, już przefiltrowany `Map<String,
     * List<Loan>>` (`updateGroupedLoans`), który inaczej zostałby ze starym zestawem kont aż do
     * następnego ręcznego odświeżenia. Najpierw pokazujemy natychmiast to, co jest w cache'u (bez
     * sieci), a potem dociągamy świeże dane w tle — tak samo jak przy starcie apki w `init{}`.
     */
    private fun onAccountSetChanged() {
        loadCachedLoans()
        refreshAllLoans(isManual = false)
    }

    fun renewLoan(loan: Loan) {
        val account = uiState.value.accounts.find { it.id == loan.accountId }
        if (account != null) {
            renewLoan(account, loan.id)
        }
    }

    fun renewLoan(account: Account, loanId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            renewLoanCore(account, loanId)
                .onSuccess { refreshAllLoans() }
                .onFailure { e ->
                    _uiState.update { it.copy(isLoading = false, error = e.message) }
                }
        }
    }

    private suspend fun renewLoanCore(account: Account, loanId: String): Result<Unit> =
        repository.renewLoan(account, loanId)

    /**
     * Przedłuża naraz wszystkie odnawialne wypożyczenia z jednej grupy (nagłówek w `LoanList` —
     * konto albo filia, w zależności od aktualnego `GroupingMode`). Sekwencyjnie, nie równolegle:
     * każde wywołanie loguje się od nowa na koncie właściciela wypożyczenia (patrz `renewLoanCore`
     * -> `OmnisRepository.renewLoan`), a grupy zwykle mają niewiele pozycji, więc nie ma potrzeby
     * ryzykować równoległych logowań na tym samym koncie. Odświeżenie sieciowe robimy raz, po całej
     * pętli, zamiast po każdej pozycji jak w pojedynczym `renewLoan`.
     */
    fun renewAllInGroup(loans: List<Loan>) {
        val renewable = loans.filter { it.renewable }
        if (renewable.isEmpty()) return

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val results =
                renewable.map { loan ->
                    val account = uiState.value.accounts.find { it.id == loan.accountId }
                    val result =
                        if (account != null) renewLoanCore(account, loan.id)
                        else Result.failure(Exception("Nieznane konto dla wypożyczenia"))
                    loan to result
                }
            refreshAllLoans(isManual = false)
            _uiState.update { it.copy(isLoading = false) }
            _events.emit(summarizeRenewResults(results))
        }
    }

    fun removeAccount(account: Account) {
        repository.removeAccount(account)
        refreshAccounts()
        resetHistoryState()
        resetHoldsState()
        onAccountSetChanged()
    }

    fun enterDemoMode() {
        repository.enterDemoMode()
        refreshAccounts()
        resetHistoryState()
        resetHoldsState()
        onAccountSetChanged()
    }

    fun exitDemoMode() {
        repository.exitDemoMode()
        refreshAccounts()
        resetHistoryState()
        resetHoldsState()
        onAccountSetChanged()
    }

    /**
     * Czyści historię trzymaną w pamięci (nie ruszając trwałego cache'u — patrz
     * `AccountManager`/`OmnisRepository`) i cofa `hasLoadedOnce`, żeby kolejne otwarcie ekranu
     * historii przeliczyło ją od nowa dla aktualnego zestawu włączonych kont. Bez tego zmiana
     * (włączenie/wyłączenie/dodanie/usunięcie konta) zostawiałaby stary/martwy widok historii aż do
     * ręcznego odświeżenia.
     */
    private fun resetHistoryState() {
        historyGeneration++
        historyFlatLoans = emptyList()
        historyCursors = emptyMap()
        _historyUiState.update {
            it.copy(
                loans = emptyMap(),
                isLoading = false,
                hasLoadedOnce = false,
                canLoadMore = false,
                isLoadingMore = false,
                error = null
            )
        }
    }

    /**
     * Ładuje historię wypożyczeń. Domyślnie no-op jeśli dane już wczytano raz w tej sesji
     * ViewModelu (ekran historii może być otwierany wielokrotnie — nie chcemy re-loginować się na
     * każde konto przy każdym wejściu). `forceRefresh` czyści cache i ładuje od nowa (pull-to-
     * -refresh / przycisk odświeżania).
     */
    fun loadHistory(forceRefresh: Boolean = false) {
        if (_historyUiState.value.isLoading) return
        if (_historyUiState.value.hasLoadedOnce && !forceRefresh) return
        val generation = historyGeneration
        viewModelScope.launch {
            _historyUiState.update { it.copy(isLoading = true, error = null) }

            val currentAccounts = _uiState.value.accounts.filter { it.isEnabled }

            if (forceRefresh) {
                currentAccounts.forEach { repository.clearCachedHistory(it.id) }
            }

            // Zbierane lokalnie, nie w historyFlatLoans/historyCursors — commitujemy je hurtem
            // na końcu, dopiero po sprawdzeniu, że w międzyczasie nikt nie zresetował stanu
            // (patrz `historyGeneration`). Gdyby pisać bezpośrednio do dzielonych pól w trakcie
            // pętli (a każde `getLoanHistoryPage` zawiesza korutynę), równoległy
            // resetHistoryState() mógłby podmienić je pod nogami i wymieszać dane starego i
            // nowego zestawu kont.
            val collectedLoans = mutableListOf<Loan>()
            val collectedCursors = mutableMapOf<String, HistoryCursor>()
            val failedAccountNames = mutableListOf<String>()
            var successCount = 0

            currentAccounts.forEach { account ->
                val cached =
                    if (forceRefresh) HistoryCacheEntry()
                    else repository.getCachedHistory(account.id)
                if (cached.loans.isNotEmpty() || !cached.hasMore) {
                    // Historia (całkowicie zwrócone wypożyczenia) się nie zmienia — jeśli
                    // mamy już pierwszą stronę w cache'u, serwujemy ją bez sięgania do API.
                    collectedLoans.addAll(cached.loans)
                    collectedCursors[account.id] = HistoryCursor(cached.nextOffset, cached.hasMore)
                    successCount++
                } else {
                    repository
                        .getLoanHistoryPage(account, offset = 1)
                        .onSuccess { (loans, hasMore) ->
                            collectedLoans.addAll(loans)
                            val nextOffset = 1 + OmnisRepository.HISTORY_PAGE_SIZE
                            collectedCursors[account.id] = HistoryCursor(nextOffset, hasMore)
                            repository.saveCachedHistory(
                                account.id,
                                HistoryCacheEntry(
                                    loans = loans,
                                    nextOffset = nextOffset,
                                    hasMore = hasMore
                                )
                            )
                            successCount++
                        }
                        .onFailure {
                            failedAccountNames.add(account.displayName ?: account.username)
                        }
                }
            }

            if (generation != historyGeneration)
                return@launch // zestaw kont zmienił się w trakcie — porzucamy wynik

            historyFlatLoans = collectedLoans
            historyCursors = collectedCursors

            // Nie oznaczamy jako "załadowane raz", jeśli nic się faktycznie nie udało (brak
            // włączonych kont albo wszystkie fetchy padły) — inaczej pusty/błędny stan
            // zatrzasnąłby się na stałe (guard w loadHistory() blokowałby kolejne próby, mimo że
            // np. użytkownik dopiero co dodał pierwsze konto albo odzyskał internet).
            finishHistoryUpdate(failedAccountNames, hasSuccess = successCount > 0)
        }
    }

    /** Doładowuje kolejną stronę historii dla każdego konta, które jeszcze ma co doładować. */
    fun loadMoreHistory() {
        // isLoading (nie tylko isLoadingMore/canLoadMore) blokuje doładowanie w trakcie pełnego
        // (force)refreshu — inaczej "Załaduj więcej" kliknięte w tym oknie odpytałoby o tę samą
        // stronę (offset=1), którą loadHistory() właśnie pobiera, i wygenerowało duplikaty
        // (a duplikaty to złamany klucz w LazyColumn — patrz `LoanList`).
        if (_historyUiState.value.isLoading || _historyUiState.value.isLoadingMore) return
        if (!_historyUiState.value.canLoadMore) return
        val generation = historyGeneration
        viewModelScope.launch {
            _historyUiState.update { it.copy(isLoadingMore = true) }

            val currentAccounts = _uiState.value.accounts.filter { it.isEnabled }
            val baseCursors = historyCursors
            val collectedLoans = mutableListOf<Loan>()
            val collectedCursors = mutableMapOf<String, HistoryCursor>()
            val failedAccountNames = mutableListOf<String>()

            currentAccounts.forEach { account ->
                val cursor = baseCursors[account.id] ?: HistoryCursor(1, true)
                if (!cursor.hasMore) return@forEach

                repository
                    .getLoanHistoryPage(account, offset = cursor.nextOffset)
                    .onSuccess { (loans, hasMore) ->
                        collectedLoans.addAll(loans)
                        val nextOffset = cursor.nextOffset + OmnisRepository.HISTORY_PAGE_SIZE
                        collectedCursors[account.id] = HistoryCursor(nextOffset, hasMore)
                        val existingCache = repository.getCachedHistory(account.id)
                        repository.saveCachedHistory(
                            account.id,
                            HistoryCacheEntry(
                                loans = existingCache.loans + loans,
                                nextOffset = nextOffset,
                                hasMore = hasMore
                            )
                        )
                    }
                    .onFailure { failedAccountNames.add(account.displayName ?: account.username) }
            }

            if (generation != historyGeneration)
                return@launch // zestaw kont zmienił się w trakcie — porzucamy wynik

            historyFlatLoans = historyFlatLoans + collectedLoans
            historyCursors = historyCursors + collectedCursors

            finishHistoryUpdate(failedAccountNames)
        }
    }

    private fun finishHistoryUpdate(failedAccountNames: List<String>, hasSuccess: Boolean = true) {
        val grouped =
            groupAndSortLoans(
                historyFlatLoans,
                _historyUiState.value.groupingMode,
                _historyUiState.value.sortMode,
                allGroupLabel()
            )
        _historyUiState.update {
            it.copy(
                loans = grouped,
                isLoading = false,
                isLoadingMore = false,
                hasLoadedOnce = it.hasLoadedOnce || hasSuccess,
                canLoadMore = historyCursors.values.any { cursor -> cursor.hasMore },
                error =
                    failedAccountNames
                        .takeIf { names -> names.isNotEmpty() }
                        ?.let { names ->
                            getApplication<Application>()
                                .getString(R.string.history_partial_error, names.joinToString(", "))
                        }
            )
        }
    }

    private fun resetHoldsState() {
        holdsGeneration++
        holdsFlat = emptyList()
        _holdsUiState.value = HoldsUiState()
    }

    /**
     * Ładuje rezerwacje włączonych kont — po kolei, jedno logowanie na konto. Jak w loadHistory():
     * bez `forceRefresh` no-op, jeśli w tej sesji już się udało (bez re-logowania przy każdym
     * wejściu na ekran).
     */
    fun loadHolds(forceRefresh: Boolean = false) {
        if (_holdsUiState.value.isLoading) return
        if (_holdsUiState.value.hasLoadedOnce && !forceRefresh) return
        val generation = holdsGeneration
        viewModelScope.launch {
            _holdsUiState.update { it.copy(isLoading = true, error = null) }
            val collected = mutableListOf<Hold>()
            val failedAccounts = mutableListOf<Account>()
            var successCount = 0
            _uiState.value.accounts
                .filter { it.isEnabled }
                .forEach { account ->
                    repository
                        .getHoldsForAccount(account)
                        .onSuccess {
                            collected.addAll(it)
                            successCount++
                        }
                        .onFailure { failedAccounts.add(account) }
                }
            if (generation != holdsGeneration) return@launch
            // Konto, którego nie udało się pobrać, zachowuje poprzednio pokazane rezerwacje.
            val failedIds = failedAccounts.map { it.id }.toSet()
            holdsFlat = holdsFlat.filter { it.accountId in failedIds } + collected
            _holdsUiState.update {
                it.copy(
                    holds = groupHolds(holdsFlat),
                    isLoading = false,
                    hasLoadedOnce = it.hasLoadedOnce || successCount > 0,
                    error =
                        failedAccounts
                            .takeIf { failed -> failed.isNotEmpty() }
                            ?.let { failed ->
                                getApplication<Application>()
                                    .getString(
                                        R.string.holds_partial_error,
                                        failed.joinToString(", ") { a ->
                                            a.displayName ?: a.username
                                        }
                                    )
                            }
                )
            }
        }
    }

    /**
     * Anuluje rezerwację. O wyniku przesądza lista odczytana zaraz po anulowaniu (patrz
     * OmnisRepository.cancelHold): jeśli rezerwacja wciąż na niej jest, zgłaszamy błąd mimo HTTP
     * 2xx. Gdy samego odczytu nie było, usuwamy pozycję lokalnie.
     */
    fun cancelHold(hold: Hold) {
        val account = _uiState.value.accounts.find { it.id == hold.accountId } ?: return
        val key = holdKey(hold)
        if (key in _holdsUiState.value.cancellingIds) return
        val generation = holdsGeneration
        viewModelScope.launch {
            _holdsUiState.update { it.copy(cancellingIds = it.cancellingIds + key) }
            val result = repository.cancelHold(account, hold.id)
            if (generation != holdsGeneration) return@launch
            val refreshed = result.getOrNull()
            val error =
                when {
                    result.isFailure -> result.exceptionOrNull()?.message ?: ""
                    refreshed != null && refreshed.any { it.id == hold.id } ->
                        getApplication<Application>().getString(R.string.hold_cancel_unconfirmed)
                    else -> null
                }
            if (result.isSuccess) {
                val others = holdsFlat.filter { it.accountId != account.id }
                holdsFlat =
                    others +
                        (refreshed
                            ?: holdsFlat.filter {
                                it.accountId == account.id && holdKey(it) != key
                            })
            }
            _holdsUiState.update {
                it.copy(holds = groupHolds(holdsFlat), cancellingIds = it.cancellingIds - key)
            }
            _events.emit(UiEvent.HoldCancelFinished(hold.title, error))
        }
    }

    fun startHoldPlacement(tenantKey: String, result: SearchResult, version: BookVersion) {
        val accounts = holdAccounts(_uiState.value.accounts, tenantKey)
        val branches = holdBranches(version)
        if (accounts.isEmpty() || branches.isEmpty()) return
        resetHoldPlacement()
        _holdPlacement.value =
            HoldPlacementState(
                title = result.title,
                version = version,
                accounts = accounts,
                branches = branches
            )
    }

    fun selectHoldAccount(accountId: String) = updateHoldSelection {
        it.copy(accountId = accountId)
    }

    fun selectHoldBranch(index: Int) = updateHoldSelection { it.copy(branchIndex = index) }

    fun selectHoldPickup(pickup: PickupLocation) {
        _holdPlacement.update {
            if (it?.phase == HoldPlacementPhase.CONFIRM) it.copy(pickup = pickup) else it
        }
    }

    private fun updateHoldSelection(change: (HoldPlacementState) -> HoldPlacementState) {
        _holdPlacement.update {
            if (it?.phase == HoldPlacementPhase.SELECT) change(it).copy(error = null) else it
        }
    }

    /** Z podsumowania z powrotem do wyboru konta/filii — sesja do wyrzucenia (inne konto?). */
    fun backToHoldSelection() {
        if (_holdPlacement.value?.phase != HoldPlacementPhase.CONFIRM) return
        holdSession = null
        _holdPlacement.update {
            it?.copy(
                phase = HoldPlacementPhase.SELECT,
                options = null,
                pickup = null,
                existingHold = null
            )
        }
    }

    /**
     * Wybrane konto+filia -> jedna sesja: logowanie, egzemplarze tej jednej filii, formularz
     * wybranego egzemplarza i bieżące rezerwacje konta (ostrzeżenie o duplikacie + punkt
     * odniesienia do potwierdzenia po złożeniu). Każdy błąd wraca do wyboru, a ponowna próba loguje
     * od nowa.
     */
    fun prepareHold() {
        val state = _holdPlacement.value ?: return
        if (state.phase != HoldPlacementPhase.SELECT) return
        val account = state.account ?: return
        val branch = state.branch ?: return
        val holding = branch.holding ?: return
        val generation = holdPlacementGeneration
        val app = getApplication<Application>()
        _holdPlacement.update { it?.copy(phase = HoldPlacementPhase.LOADING, error = null) }
        holdPrepareJob =
            viewModelScope.launch {
                fun fail(message: String) {
                    if (generation != holdPlacementGeneration) return
                    holdSession = null
                    _holdPlacement.update {
                        it?.copy(phase = HoldPlacementPhase.SELECT, error = message)
                    }
                }
                val session =
                    repository.openHoldSession(account).getOrElse {
                        return@launch fail(it.message ?: "")
                    }
                val items =
                    repository.getHoldableItems(session, state.version.mmsid, holding).getOrElse {
                        return@launch fail(it.message ?: "")
                    }
                val item =
                    pickHoldableItem(items)
                        ?: return@launch fail(
                            app.getString(R.string.hold_place_no_items, branch.libraryName)
                        )
                val options =
                    repository.getHoldOptions(session, item).getOrElse {
                        return@launch fail(it.message ?: "")
                    }
                if (options.pickupLocations.isEmpty()) {
                    return@launch fail(app.getString(R.string.hold_place_no_pickup))
                }
                val holds =
                    repository.getHolds(session).getOrElse {
                        return@launch fail(it.message ?: "")
                    }
                if (generation != holdPlacementGeneration) return@launch
                holdSession = session
                holdBaseline = holds.map { it.id }.toSet()
                val recordIds = holdRecordIds(state.version, item.mmsid)
                _holdPlacement.update {
                    it?.copy(
                        phase = HoldPlacementPhase.CONFIRM,
                        options = options,
                        pickup = options.pickupLocations.singleOrNull(),
                        existingHold = holds.firstOrNull { h -> h.mmsid in recordIds }
                    )
                }
            }
    }

    /**
     * Składa rezerwację w sesji z prepareHold(), potem w tej samej sesji czeka, aż nowa rezerwacja
     * pojawi się na liście konta (odstępy jak HOLD_CONFIRM_DELAYS w omnis-py). Potwierdzanie nie
     * jest przerywane zamknięciem okna — jego wynik i tak trafia na ekran rezerwacji.
     */
    fun confirmHold() {
        val state = _holdPlacement.value ?: return
        if (state.phase != HoldPlacementPhase.CONFIRM) return
        val options = state.options ?: return
        val pickup = state.pickup ?: return
        val session = holdSession ?: return
        val generation = holdPlacementGeneration
        val baseline = holdBaseline
        val recordIds = holdRecordIds(state.version, options.item.mmsid)
        val holdsGen = holdsGeneration
        _holdPlacement.update { it?.copy(phase = HoldPlacementPhase.PLACING, error = null) }
        viewModelScope.launch {
            val placed = repository.placeHold(session, options, pickup)
            if (placed.isFailure) {
                holdSession = null
                if (generation == holdPlacementGeneration) {
                    _holdPlacement.update {
                        it?.copy(
                            phase = HoldPlacementPhase.SELECT,
                            options = null,
                            pickup = null,
                            existingHold = null,
                            error = placed.exceptionOrNull()?.message ?: ""
                        )
                    }
                }
                return@launch
            }
            if (generation == holdPlacementGeneration) {
                _holdPlacement.update {
                    it?.copy(phase = HoldPlacementPhase.DONE, confirming = true)
                }
            }
            var latest: List<Hold>? = null
            var newHold: Hold? = null
            for (delayMs in HOLD_CONFIRM_DELAYS_MS) {
                delay(delayMs)
                latest = repository.getHolds(session).getOrNull() ?: latest
                newHold = latest?.let { findNewHold(baseline, it, recordIds) }
                if (newHold != null) break
            }
            if (generation == holdPlacementGeneration) {
                holdSession = null
                _holdPlacement.update { it?.copy(confirming = false, placedHold = newHold) }
            }
            if (holdsGen == holdsGeneration)
                latest?.let { replaceAccountHolds(session.account, it) }
        }
    }

    private fun replaceAccountHolds(account: Account, holds: List<Hold>) {
        holdsFlat = holdsFlat.filter { it.accountId != account.id } + holds
        _holdsUiState.update { it.copy(holds = groupHolds(holdsFlat)) }
    }

    /** W trakcie wysyłania zamówienia okna nie zamykamy — wynik musi do kogoś trafić. */
    fun dismissHoldPlacement() {
        if (_holdPlacement.value?.phase == HoldPlacementPhase.PLACING) return
        resetHoldPlacement()
    }

    private fun resetHoldPlacement() {
        holdPlacementGeneration++
        holdPrepareJob?.cancel()
        holdPrepareJob = null
        holdSession = null
        holdBaseline = emptySet()
        _holdPlacement.value = null
    }

    /**
     * Zapamiętuje tylko bieżące rezerwacje — stare tokeny same wypadają przy kolejnym zamknięciu.
     */
    fun dismissReadyHolds(ready: List<Hold>) {
        val tokens = ready.map { readyHoldBannerToken(it) }.toSet()
        viewPrefs.putStringSet(PREF_READY_HOLDS_DISMISSED, tokens)
        _dismissedReadyHolds.value = tokens
    }

    fun setHistoryGroupingMode(mode: GroupingMode) {
        viewPrefs.put(PREF_HISTORY_GROUPING, mode)
        _historyUiState.update { it.copy(groupingMode = mode) }
        reapplyHistoryGroupingAndSorting()
    }

    fun setHistorySortMode(mode: SortMode) {
        viewPrefs.put(PREF_HISTORY_SORT, mode)
        _historyUiState.update { it.copy(sortMode = mode) }
        reapplyHistoryGroupingAndSorting()
    }

    private fun reapplyHistoryGroupingAndSorting() {
        val grouped =
            groupAndSortLoans(
                historyFlatLoans,
                _historyUiState.value.groupingMode,
                _historyUiState.value.sortMode,
                allGroupLabel()
            )
        _historyUiState.update { it.copy(loans = grouped) }
    }

    private fun updateSearchSection(
        tenantKey: String,
        transform: (SearchTenantSection) -> SearchTenantSection
    ) {
        _searchUiState.update { state ->
            state.copy(
                tenantSections =
                    state.tenantSections.map {
                        if (it.tenantKey == tenantKey) transform(it) else it
                    }
            )
        }
    }

    private fun branchNamesOf(page: SearchPage): List<String> =
        page.results.flatMap { r -> r.versions.flatMap { v -> v.branches.map { it.libraryName } } }

    /**
     * Nazwa filii -> jej subLocation (np. "os. Bolesława Chrobrego 117a"), do wyświetlenia obok
     * nazwy w chipie filtra. Pierwsze napotkane wystąpienie wygrywa — jedna filia może mieć różne
     * subLocation dla różnych wydań (np. inny księgozbiór), a chip pokazuje tylko jedną etykietę.
     */
    private fun branchAddressesOf(page: SearchPage): Map<String, String> =
        page.results
            .flatMap { r -> r.versions.flatMap { v -> v.branches } }
            .mapNotNull { b -> b.subLocation?.let { b.libraryName to it } }
            .distinctBy { it.first } // pierwsze wystąpienie wygrywa, patrz komentarz wyżej
            .toMap()

    private fun formatSearchError(e: Throwable): String {
        val detail = e.message?.takeIf { it.isNotBlank() } ?: e::class.simpleName ?: "unknown"
        return getApplication<Application>().getString(R.string.search_error, detail)
    }

    fun runSearch(query: String, field: SearchField = SearchField.ANY) {
        if (query.isBlank()) return
        recordSearchHistory(SearchHistoryEntry(query.trim(), field))

        searchGeneration++
        cancelDueDateJobs()
        val generation = searchGeneration

        // Wybór czytany z tych samych źródeł co searchLibraries, a nie z jego .value — stateIn
        // przelicza się asynchronicznie, więc tuż po zmianie wyboru .value mógłby być nieaktualny.
        val targets =
            buildSearchLibrariesState(_uiState.value.accounts, _searchTenantOverride.value).selected
        searchTargets = targets.associateBy { it.searchKey() }

        val existingSections = _searchUiState.value.tenantSections.associateBy { it.tenantKey }
        val allAccounts = _uiState.value.accounts
        val skeleton =
            targets.map { tenant ->
                val tenantKey = tenant.searchKey()
                val siblingIds =
                    allAccounts.filter { it.tenant.searchKey() == tenantKey }.map { it.id }
                val seeded =
                    siblingIds
                        .flatMap { repository.getCachedLoans(it) }
                        .map { it.locationName }
                        .distinct()
                val prefs = repository.getSearchBranchPrefs(tenantKey)
                SearchTenantSection(
                    tenantKey = tenantKey,
                    tenantLabel = tenant.name,
                    confirmedBranches =
                        existingSections[tenantKey]?.confirmedBranches ?: emptyList(),
                    branchAddresses = existingSections[tenantKey]?.branchAddresses ?: emptyMap(),
                    seededBranches = seeded,
                    selectedBranches = prefs.selectedBranches,
                    showAllBranches = prefs.showAllBranches,
                    sortMode =
                        if (field == SearchField.SERIES) SearchSortMode.SERIES
                        else SearchSortMode.RELEVANCE,
                    isLoading = true
                )
            }

        _searchUiState.update {
            it.copy(
                query = query,
                field = field,
                hasSearched = true,
                isLoading = true,
                tenantSections = skeleton
            )
        }

        viewModelScope.launch {
            val outcomes = coroutineScope {
                targets
                    .map { tenant ->
                        async {
                            tenant to
                                repository.searchBooks(tenant, query, offset = 0, field = field)
                        }
                    }
                    .awaitAll()
            }

            if (generation != searchGeneration)
                return@launch // nowsze wyszukanie już wystartowało — porzucamy wynik

            outcomes.forEach { (tenant, result) ->
                val tenantKey = tenant.searchKey()
                result
                    .onSuccess { page ->
                        updateSearchSection(tenantKey) { section ->
                            section.copy(
                                results = page.results,
                                confirmedBranches =
                                    (section.confirmedBranches + branchNamesOf(page)).distinct(),
                                branchAddresses = branchAddressesOf(page) + section.branchAddresses,
                                nextOffset = page.results.size,
                                canLoadMore = page.hasMore,
                                isLoading = false,
                                error = null
                            )
                        }
                        launchDueDates(tenant, page, generation)
                    }
                    .onFailure { e ->
                        updateSearchSection(tenantKey) { section ->
                            section.copy(isLoading = false, error = formatSearchError(e))
                        }
                    }
            }
            _searchUiState.update { it.copy(isLoading = false) }
        }
    }

    /**
     * Doładowuje kolejną stronę wyników dla JEDNEJ biblioteki — bez trwałego cache'u (patrz §7).
     */
    fun loadMoreResults(tenantKey: String) {
        val section =
            _searchUiState.value.tenantSections.find { it.tenantKey == tenantKey } ?: return
        if (section.isLoadingMore || !section.canLoadMore) return
        val tenant = searchTargets[tenantKey] ?: return
        val query = _searchUiState.value.query
        val field = _searchUiState.value.field
        val generation = searchGeneration
        val offset = section.nextOffset

        updateSearchSection(tenantKey) { it.copy(isLoadingMore = true) }

        viewModelScope.launch {
            repository
                .searchBooks(tenant, query, offset = offset, field = field)
                .onSuccess { page ->
                    if (generation != searchGeneration) return@onSuccess
                    updateSearchSection(tenantKey) { s ->
                        s.copy(
                            results = s.results + page.results,
                            confirmedBranches =
                                (s.confirmedBranches + branchNamesOf(page)).distinct(),
                            branchAddresses = branchAddressesOf(page) + s.branchAddresses,
                            nextOffset = s.nextOffset + page.results.size,
                            canLoadMore = page.hasMore,
                            isLoadingMore = false
                        )
                    }
                    launchDueDates(tenant, page, generation)
                }
                .onFailure { e ->
                    if (generation != searchGeneration) return@onFailure
                    updateSearchSection(tenantKey) {
                        it.copy(isLoadingMore = false, error = formatSearchError(e))
                    }
                }
        }
    }

    /**
     * Powrót do stanu "przed wyszukaniem" (ekran pokazuje wtedy historię). Podbija generację, żeby
     * wynik trwającego jeszcze wyszukania nie wrócił na wyczyszczony ekran. Sekcje zostają, bo
     * runSearch przenosi z nich poznane filie (confirmedBranches/branchAddresses).
     */
    fun clearSearch() {
        searchGeneration++
        cancelDueDateJobs()
        _searchUiState.update {
            it.copy(query = "", field = SearchField.ANY, hasSearched = false, isLoading = false)
        }
    }

    // Duplikat (bez względu na wielkość liter, w obrębie tego samego pola) przesuwa się na
    // początek zamiast dublować wpis.
    private fun recordSearchHistory(entry: SearchHistoryEntry) {
        val updated =
            (listOf(entry) +
                    _searchHistory.value.filterNot {
                        it.field == entry.field && it.query.equals(entry.query, ignoreCase = true)
                    })
                .take(SEARCH_HISTORY_LIMIT)
        _searchHistory.value = updated
        repository.saveSearchHistory(updated)
    }

    fun removeSearchHistoryEntry(entry: SearchHistoryEntry) {
        val updated = _searchHistory.value - entry
        _searchHistory.value = updated
        repository.saveSearchHistory(updated)
    }

    fun clearSearchHistory() {
        _searchHistory.value = emptyList()
        repository.clearSearchHistory()
    }

    fun setBranchSelection(tenantKey: String, branch: String, selected: Boolean) {
        updateSearchSection(tenantKey) { section ->
            val newSet =
                if (selected) section.selectedBranches + branch
                else section.selectedBranches - branch
            repository.saveSearchBranchPrefs(
                tenantKey,
                SearchBranchPrefs(newSet, section.showAllBranches)
            )
            section.copy(selectedBranches = newSet)
        }
    }

    fun setShowAllBranches(tenantKey: String, showAll: Boolean) {
        updateSearchSection(tenantKey) { section ->
            repository.saveSearchBranchPrefs(
                tenantKey,
                SearchBranchPrefs(section.selectedBranches, showAll)
            )
            section.copy(showAllBranches = showAll)
        }
    }

    /** Wyłącznie klient-side (patrz filteredResults()) — nie odpytuje API ponownie. */
    fun setMediaTypeSelected(type: String, selected: Boolean) {
        // Z efektywnego wyboru, nie zapisanego — inaczej typ zapamiętany z innego wyszukiwania
        // (niewidoczny teraz jako chip) dalej by filtrował po odznaczeniu wszystkich widocznych.
        val current = _searchUiState.value.effectiveMediaTypes()
        setSelectedMediaTypes(if (selected) current + type else current - type)
    }

    fun clearMediaTypeSelection() = setSelectedMediaTypes(emptySet())

    private fun setSelectedMediaTypes(types: Set<String>) {
        _searchUiState.update { it.copy(selectedMediaTypes = types) }
        viewPrefs.putStringSet(PREF_SEARCH_MEDIA_TYPES, types)
    }

    /**
     * Wyłącznie klient-side (patrz filteredResults()) — nie odpytuje API ponownie. UI ogranicza
     * wywołanie do sytuacji, gdy wszystkie strony wyników danej biblioteki są już załadowane
     * (!canLoadMore), żeby sortowanie nie gubiło się przy kolejnym "Załaduj więcej".
     */
    fun setSearchSortMode(tenantKey: String, mode: SearchSortMode) {
        updateSearchSection(tenantKey) { section -> section.copy(sortMode = mode) }
    }

    /**
     * Wyszukanie autora/serii kliknięte na wypożyczeniu — w bibliotece, z której ono jest. Wybór
     * bibliotek zmienia się na stałe, tak jak przy ręcznej zmianie w wyszukiwarce.
     */
    fun searchFromLoan(loan: Loan, query: String, field: SearchField) {
        val tenant = _uiState.value.accounts.find { it.id == loan.accountId }?.tenant
        if (tenant != null) {
            val keys = setOf(tenant.searchKey())
            _searchTenantOverride.value = keys
            repository.saveSearchTenantKeys(keys)
        }
        runSearch(query, field)
    }

    fun setSearchLibrarySelected(tenant: Tenant, selected: Boolean) {
        // Z bieżących źródeł, nie z searchLibraries.value (patrz komentarz w runSearch) — szybkie
        // kolejne kliknięcia nie mogą zgubić poprzedniej zmiany.
        val current =
            buildSearchLibrariesState(_uiState.value.accounts, _searchTenantOverride.value)
                .selectedKeys
        val key = tenant.searchKey()
        val updated = if (selected) current + key else current - key
        _searchTenantOverride.value = updated
        repository.saveSearchTenantKeys(updated)
    }

    private companion object {
        const val PREF_LOANS_GROUPING = "loans_grouping"
        const val PREF_LOANS_SORT = "loans_sort"
        const val PREF_HISTORY_GROUPING = "history_grouping"
        const val PREF_HISTORY_SORT = "history_sort"
        const val PREF_SEARCH_MEDIA_TYPES = "search_media_types"
        const val PREF_READY_HOLDS_DISMISSED = "ready_holds_dismissed"
        const val SEARCH_HISTORY_LIMIT = 20
    }

    class Factory(private val application: Application, private val repository: OmnisRepository) :
        ViewModelProvider.Factory {
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
            return OmnisViewModel(application, repository) as T
        }
    }
}
