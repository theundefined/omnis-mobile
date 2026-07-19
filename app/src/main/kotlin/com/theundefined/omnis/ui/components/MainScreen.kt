package com.theundefined.omnis.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theundefined.omnis.R
import com.theundefined.omnis.ui.GroupingMode
import com.theundefined.omnis.ui.OmnisViewModel
import com.theundefined.omnis.ui.SortMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: OmnisViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var currentScreen by remember { mutableStateOf("main") }
    val snackbarHostState = remember { SnackbarHostState() }

    val error = uiState.error

    // Navigation and error handling logic
    LaunchedEffect(error) {
        if (error != null && currentScreen == "main") {
            snackbarHostState.showSnackbar(error)
        }
    }

    if (currentScreen == "settings") {
        SettingsScreen(
            viewModel = viewModel,
            accounts = uiState.accounts,
            onToggleAccount = { viewModel.toggleAccount(it) },
            onRemoveAccount = { viewModel.removeAccount(it) },
            onAddAccount = { user, pass, tenant -> viewModel.addAccount(user, pass, tenant) },
            isLoading = uiState.isLoading,
            onBack = { currentScreen = "main" },
            errorMessage = error
        )
        return
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(
                        onClick = {
                            val nextMode =
                                if (uiState.groupingMode == GroupingMode.ACCOUNT)
                                    GroupingMode.BRANCH
                                else GroupingMode.ACCOUNT
                            viewModel.setGroupingMode(nextMode)
                        }
                    ) {
                        Text(if (uiState.groupingMode == GroupingMode.ACCOUNT) "👤" else "📍")
                    }

                    IconButton(onClick = { viewModel.refreshAllLoans(isManual = true) }) {
                        Text("🔄")
                    }

                    var showSortMenu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { showSortMenu = true }) { Text("🔃") }
                        DropdownMenu(
                            expanded = showSortMenu,
                            onDismissRequest = { showSortMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.sort_by_due_date)) },
                                onClick = {
                                    viewModel.setSortMode(SortMode.DUE_DATE)
                                    showSortMenu = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.sort_by_loan_date)) },
                                onClick = {
                                    viewModel.setSortMode(SortMode.LOAN_DATE)
                                    showSortMenu = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.sort_by_title)) },
                                onClick = {
                                    viewModel.setSortMode(SortMode.TITLE)
                                    showSortMenu = false
                                }
                            )
                        }
                    }

                    IconButton(onClick = { currentScreen = "settings" }) { Text("⚙️") }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            if (uiState.accounts.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = androidx.compose.ui.Alignment.Center
                ) {
                    Button(onClick = { currentScreen = "settings" }) {
                        Text(stringResource(R.string.add_first_account))
                    }
                }
            } else {
                Column {
                    if (uiState.isLoading) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    LoanList(
                        groupedLoans = uiState.loans,
                        onRenew = { loan -> viewModel.renewLoan(loan) }
                    )
                }
            }
        }
    }
}
