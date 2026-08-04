package com.theundefined.omnis.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.theundefined.omnis.R
import com.theundefined.omnis.data.model.Account
import com.theundefined.omnis.data.model.HistoryCacheEntry
import com.theundefined.omnis.data.model.Loan
import com.theundefined.omnis.data.model.Tenant
import com.theundefined.omnis.data.repository.OmnisRepository
import com.theundefined.omnis.ui.components.parseFlexibleDate
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

    class Factory(private val application: Application, private val repository: OmnisRepository) :
        ViewModelProvider.Factory {
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
            return OmnisViewModel(application, repository) as T
        }
    }
}
