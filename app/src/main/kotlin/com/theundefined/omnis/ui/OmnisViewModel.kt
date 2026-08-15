package com.theundefined.omnis.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.theundefined.omnis.R
import com.theundefined.omnis.data.model.Account
import com.theundefined.omnis.data.model.HistoryCacheEntry
import com.theundefined.omnis.data.model.Loan
import com.theundefined.omnis.data.model.SearchBranchPrefs
import com.theundefined.omnis.data.model.SearchPage
import com.theundefined.omnis.data.model.SearchResult
import com.theundefined.omnis.data.model.Tenant
import com.theundefined.omnis.data.model.searchKey
import com.theundefined.omnis.data.repository.OmnisRepository
import com.theundefined.omnis.ui.components.parseFlexibleDate
import java.text.Collator
import java.util.Locale
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class GroupingMode {
    ACCOUNT,
    BRANCH
}

enum class SortMode {
    DUE_DATE,
    LOAN_DATE,
    TITLE
}

data class UiState(
    val accounts: List<Account> = emptyList(),
    val loans: Map<String, List<Loan>> = emptyMap(), // groupKey -> loans
    val groupingMode: GroupingMode = GroupingMode.ACCOUNT,
    val sortMode: SortMode = SortMode.DUE_DATE,
    val isLoading: Boolean = false,
    val error: String? = null
)

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

/** Kursor paginacji historii pojedynczego konta — dokąd doszliśmy i czy jest więcej stron. */
private data class HistoryCursor(val nextOffset: Int, val hasMore: Boolean)

data class SearchUiState(
    val query: String = "",
    val isLoading: Boolean = false,
    val hasSearched: Boolean = false,
    val tenantSections: List<SearchTenantSection> = emptyList()
)

/** Sortowanie wyników wyszukiwania w obrębie jednej biblioteki — czysto klient-side. */
enum class SearchSortMode {
    RELEVANCE, // kolejność zwrócona przez Primo (parametr sort=rank) — bez zmian
    TITLE
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

fun SearchTenantSection.filteredResults(): List<SearchResult> {
    val effectiveSelection = selectedBranches.intersect(confirmedBranches.toSet())
    val base =
        if (showAllBranches || effectiveSelection.isEmpty()) results
        else
            results.mapNotNull { result ->
                val versions =
                    result.versions.mapNotNull { v ->
                        val branches = v.branches.filter { it.libraryName in effectiveSelection }
                        if (branches.isEmpty()) null else v.copy(branches = branches)
                    }
                if (versions.isEmpty()) null else result.copy(versions = versions)
            }
    return when (sortMode) {
        SearchSortMode.RELEVANCE -> base
        SearchSortMode.TITLE -> base.sortedWith(compareBy(polishCollator) { it.title })
    }
}

class OmnisViewModel(application: Application, private val repository: OmnisRepository) :
    AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _historyUiState = MutableStateFlow(HistoryUiState())
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

    private val _searchUiState = MutableStateFlow(SearchUiState())
    val searchUiState: StateFlow<SearchUiState> = _searchUiState.asStateFlow()

    // Konto użyte do wykonania OSTATNIEGO wyszukania per biblioteka — loadMoreResults() musi
    // doładowywać kolejne strony tym samym kontem/loginem, nawet jeśli w międzyczasie zmienił
    // się zestaw kont/flaga preferowania (patrz docs/plans/book-search.md §8.3a).
    private var searchRepresentatives: Map<String, Account> = emptyMap()

    // Zwiększane przy każdym runSearch() — analogicznie do historyGeneration (patrz wyżej),
    // chroni przed sytuacją, w której użytkownik odpala drugie wyszukanie zanim pierwsze
    // zdążyło wrócić z sieci: wynik "spóźnionego" pierwszego wyszukania jest wtedy porzucany
    // zamiast nadpisać świeżo ustawiony szkielet drugiego.
    private var searchGeneration = 0

    private val _events = MutableSharedFlow<UiEvent>()
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

    sealed class UiEvent {
        object AccountAdded : UiEvent()
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

            currentAccounts.forEach { account ->
                if (account.displayName == null || account.displayName == account.username) {
                    repository.fetchAccountProfile(account)
                }

                repository
                    .getLoansForAccount(account)
                    .onSuccess { loans ->
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
            _uiState.update { it.copy(accounts = updatedAccounts) }

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
                uiState.value.sortMode
            )
        _uiState.update { it.copy(loans = grouped, isLoading = isLoading) }
    }

    private fun groupAndSortLoans(
        loans: List<Loan>,
        groupingMode: GroupingMode,
        sortMode: SortMode
    ): Map<String, List<Loan>> {
        val grouped =
            when (groupingMode) {
                // ownerName jest ustawiane w OmnisRepository jako account.displayName ?:
                // account.username, więc grupowanie po nim jest równoważne grupowaniu po Account.
                GroupingMode.ACCOUNT -> loans.groupBy { it.ownerName ?: "?" }
                GroupingMode.BRANCH -> loans.groupBy { it.libraryName + " - " + it.locationName }
            }
        return grouped.mapValues { entry -> sortLoans(entry.value, sortMode) }
    }

    private fun sortLoans(loans: List<Loan>, sortMode: SortMode): List<Loan> {
        // Daty z API są tekstowe (dd/MM/yyyy) — sortowanie leksykograficzne po Stringu
        // porównywałoby de facto tylko dzień miesiąca, ignorując rok. Parsujemy więc raz na
        // element do LocalDate (nie w każdym porównaniu); wpisy z datą, której nie da się
        // sparsować, lądują na końcu.
        fun byDate(selector: (Loan) -> String, ascending: Boolean): List<Loan> {
            val withKeys = loans.map { it to parseFlexibleDate(selector(it)) }
            val (withDate, withoutDate) = withKeys.partition { it.second != null }
            val sorted =
                if (ascending) withDate.sortedBy { it.second }
                else withDate.sortedByDescending { it.second }
            return sorted.map { it.first } + withoutDate.map { it.first }
        }

        return when (sortMode) {
            SortMode.DUE_DATE -> byDate({ it.dueDate }, ascending = true)
            SortMode.LOAN_DATE -> byDate({ it.loanDate }, ascending = false)
            SortMode.TITLE -> loans.sortedBy { it.title.lowercase() }
        }
    }

    fun setGroupingMode(mode: GroupingMode) {
        _uiState.update { it.copy(groupingMode = mode) }
        reapplyGroupingAndSorting()
    }

    fun setSortMode(mode: SortMode) {
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

    fun toggleAccount(account: Account) {
        val updated = account.copy(isEnabled = !account.isEnabled)
        repository.updateAccount(updated)
        refreshAccounts()
        resetHistoryState()
    }

    fun addAccount(username: String, password: String, tenant: Tenant) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            repository
                .loginAndAddAccount(username, password, tenant)
                .onSuccess {
                    refreshAccounts()
                    resetHistoryState()
                    _events.emit(UiEvent.AccountAdded)
                }
                .onFailure { e ->
                    _uiState.update { it.copy(isLoading = false, error = e.message) }
                }
        }
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
            repository
                .renewLoan(account, loanId)
                .onSuccess { refreshAllLoans() }
                .onFailure { e ->
                    _uiState.update { it.copy(isLoading = false, error = e.message) }
                }
        }
    }

    fun removeAccount(account: Account) {
        repository.removeAccount(account)
        refreshAccounts()
        resetHistoryState()
    }

    fun enterDemoMode() {
        repository.enterDemoMode()
        refreshAccounts()
        resetHistoryState()
    }

    fun exitDemoMode() {
        repository.exitDemoMode()
        refreshAccounts()
        resetHistoryState()
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
                _historyUiState.value.sortMode
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

    fun setHistoryGroupingMode(mode: GroupingMode) {
        _historyUiState.update { it.copy(groupingMode = mode) }
        reapplyHistoryGroupingAndSorting()
    }

    fun setHistorySortMode(mode: SortMode) {
        _historyUiState.update { it.copy(sortMode = mode) }
        reapplyHistoryGroupingAndSorting()
    }

    private fun reapplyHistoryGroupingAndSorting() {
        val grouped =
            groupAndSortLoans(
                historyFlatLoans,
                _historyUiState.value.groupingMode,
                _historyUiState.value.sortMode
            )
        _historyUiState.update { it.copy(loans = grouped) }
    }

    /**
     * Wybiera JEDNO konto reprezentujące każdą unikalną bibliotekę (tenant.searchKey()) wśród
     * włączonych kont — kilka kont dzielących ten sam katalog dałoby identyczne wyniki, więc
     * wyszukujemy raz, nie N razy. Preferuje konto oznaczone `preferredForSearch`; w przeciwnym
     * razie pierwsze wg kolejności z AccountManager.getAccounts() (kolejność dodania) —
     * deterministyczny fallback, patrz docs/plans/book-search.md §8.1.
     */
    private fun pickSearchAccounts(accounts: List<Account>): List<Account> =
        accounts
            .filter { it.isEnabled }
            .groupBy { it.tenant.searchKey() }
            .values
            .map { group -> group.firstOrNull { it.preferredForSearch } ?: group.first() }

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

    fun runSearch(query: String) {
        if (query.isBlank()) return

        searchGeneration++
        val generation = searchGeneration

        val targets = pickSearchAccounts(_uiState.value.accounts)
        searchRepresentatives = targets.associateBy { it.tenant.searchKey() }

        val existingSections = _searchUiState.value.tenantSections.associateBy { it.tenantKey }
        val allAccounts = _uiState.value.accounts
        val skeleton =
            targets.map { account ->
                val tenantKey = account.tenant.searchKey()
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
                    tenantLabel = account.tenant.name,
                    confirmedBranches =
                        existingSections[tenantKey]?.confirmedBranches ?: emptyList(),
                    branchAddresses = existingSections[tenantKey]?.branchAddresses ?: emptyMap(),
                    seededBranches = seeded,
                    selectedBranches = prefs.selectedBranches,
                    showAllBranches = prefs.showAllBranches,
                    isLoading = true
                )
            }

        _searchUiState.update {
            it.copy(query = query, hasSearched = true, isLoading = true, tenantSections = skeleton)
        }

        viewModelScope.launch {
            val outcomes = coroutineScope {
                targets
                    .map { account ->
                        async { account to repository.searchBooks(account, query, offset = 0) }
                    }
                    .awaitAll()
            }

            if (generation != searchGeneration)
                return@launch // nowsze wyszukanie już wystartowało — porzucamy wynik

            outcomes.forEach { (account, result) ->
                val tenantKey = account.tenant.searchKey()
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
        val account = searchRepresentatives[tenantKey] ?: return
        val query = _searchUiState.value.query
        val generation = searchGeneration
        val offset = section.nextOffset

        updateSearchSection(tenantKey) { it.copy(isLoadingMore = true) }

        viewModelScope.launch {
            repository
                .searchBooks(account, query, offset = offset)
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
                }
                .onFailure { e ->
                    if (generation != searchGeneration) return@onFailure
                    updateSearchSection(tenantKey) {
                        it.copy(isLoadingMore = false, error = formatSearchError(e))
                    }
                }
        }
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

    /**
     * Wyłącznie klient-side (patrz filteredResults()) — nie odpytuje API ponownie. UI ogranicza
     * wywołanie do sytuacji, gdy wszystkie strony wyników danej biblioteki są już załadowane
     * (!canLoadMore), żeby sortowanie nie gubiło się przy kolejnym "Załaduj więcej".
     */
    fun setSearchSortMode(tenantKey: String, mode: SearchSortMode) {
        updateSearchSection(tenantKey) { section -> section.copy(sortMode = mode) }
    }

    fun togglePreferredForSearch(account: Account) {
        repository.updateAccount(account.copy(preferredForSearch = !account.preferredForSearch))
        refreshAccounts()
    }

    class Factory(private val application: Application, private val repository: OmnisRepository) :
        ViewModelProvider.Factory {
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
            return OmnisViewModel(application, repository) as T
        }
    }
}
