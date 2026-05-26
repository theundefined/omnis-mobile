package com.theundefined.omnis.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
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
    ACCOUNT, BRANCH
}

enum class SortMode {
    DUE_DATE, LOAN_DATE, TITLE
}

data class UiState(
    val accounts: List<Account> = emptyList(),
    val loans: Map<String, List<Loan>> = emptyMap(), // groupKey -> loans
    val groupingMode: GroupingMode = GroupingMode.ACCOUNT,
    val sortMode: SortMode = SortMode.DUE_DATE,
    val isLoading: Boolean = false,
    val error: String? = null
)

class OmnisViewModel(private val repository: OmnisRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<UiEvent>()
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

    sealed class UiEvent {
        object AccountAdded : UiEvent()
    }

    init {
        refreshAccounts()
    }

    fun refreshAccounts() {
        val accounts = repository.getAccounts()
        _uiState.update { it.copy(accounts = accounts) }
        refreshAllLoans()
    }

    fun refreshAllLoans() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            val currentAccounts = uiState.value.accounts.filter { it.isEnabled }
            val allLoansList = mutableListOf<Pair<Account, Loan>>()
            
            currentAccounts.forEach { account ->
                // Migration/Auto-update: if displayName is missing or is just the username, fetch profile
                if (account.displayName == null || account.displayName == account.username) {
                    repository.fetchAccountProfile(account)
                }

                repository.getLoansForAccount(account).onSuccess { loans ->
                    loans.forEach { allLoansList.add(account to it) }
                }
            }
            
            // After potential profile updates, refresh the account list in state
            val updatedAccounts = repository.getAccounts()
            _uiState.update { it.copy(accounts = updatedAccounts) }
            
            updateGroupedLoans(allLoansList)
        }
    }

    private fun updateGroupedLoans(allLoans: List<Pair<Account, Loan>>) {
        val grouped = when (uiState.value.groupingMode) {
            GroupingMode.ACCOUNT -> {
                allLoans.groupBy { it.first.displayName ?: it.first.username }
                    .mapValues { entry -> sortLoans(entry.value.map { it.second }) }
            }
            GroupingMode.BRANCH -> {
                allLoans.groupBy { it.second.libraryName + " - " + it.second.locationName }
                    .mapValues { entry -> sortLoans(entry.value.map { it.second }) }
            }
        }
        _uiState.update { it.copy(loans = grouped, isLoading = false) }
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
        refreshAllLoans()
    }

    fun setSortMode(mode: SortMode) {
        _uiState.update { it.copy(sortMode = mode) }
        refreshAllLoans()
    }

    fun toggleAccount(account: Account) {
        val updated = account.copy(isEnabled = !account.isEnabled)
        repository.updateAccount(updated)
        refreshAccounts()
    }

    fun addAccount(username: String, password: String, tenant: Tenant) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            repository.loginAndAddAccount(username, password, tenant)
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
            repository.renewLoan(account, loanId)
                .onSuccess {
                    refreshAllLoans()
                }
                .onFailure { e ->
                    _uiState.update { it.copy(isLoading = false, error = e.message) }
                }
        }
    }

    fun removeAccount(account: Account) {
        repository.removeAccount(account)
        refreshAccounts()
    }

    class Factory(private val repository: OmnisRepository) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return OmnisViewModel(repository) as T
        }
    }
}
