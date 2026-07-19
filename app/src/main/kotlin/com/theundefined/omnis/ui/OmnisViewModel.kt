package com.theundefined.omnis.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.theundefined.omnis.R
import com.theundefined.omnis.data.model.Account
import com.theundefined.omnis.data.model.Loan
import com.theundefined.omnis.data.model.Tenant
import com.theundefined.omnis.data.repository.OmnisRepository
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

class OmnisViewModel(application: Application, private val repository: OmnisRepository) :
    AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

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
            when (uiState.value.groupingMode) {
                GroupingMode.ACCOUNT -> {
                    allLoans
                        .groupBy { it.first.displayName ?: it.first.username }
                        .mapValues { entry -> sortLoans(entry.value.map { it.second }) }
                }
                GroupingMode.BRANCH -> {
                    allLoans
                        .groupBy { it.second.libraryName + " - " + it.second.locationName }
                        .mapValues { entry -> sortLoans(entry.value.map { it.second }) }
                }
            }
        _uiState.update { it.copy(loans = grouped, isLoading = isLoading) }
    }

    private fun sortLoans(loans: List<Loan>): List<Loan> {
        return when (uiState.value.sortMode) {
            SortMode.DUE_DATE -> loans.sortedBy { it.dueDate }
            SortMode.LOAN_DATE -> loans.sortedByDescending { it.loanDate }
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
    }

    fun addAccount(username: String, password: String, tenant: Tenant) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            repository
                .loginAndAddAccount(username, password, tenant)
                .onSuccess {
                    refreshAccounts()
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
    }

    class Factory(private val application: Application, private val repository: OmnisRepository) :
        ViewModelProvider.Factory {
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
            return OmnisViewModel(application, repository) as T
        }
    }
}
