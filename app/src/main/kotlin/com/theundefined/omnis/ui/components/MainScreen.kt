package com.theundefined.omnis.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theundefined.omnis.ui.GroupingMode
import com.theundefined.omnis.ui.SortMode
import com.theundefined.omnis.ui.OmnisViewModel

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
                title = { Text("Omnis") },
                actions = {
                    IconButton(onClick = {
                        val nextMode = if (uiState.groupingMode == GroupingMode.ACCOUNT) GroupingMode.BRANCH else GroupingMode.ACCOUNT
                        viewModel.setGroupingMode(nextMode)
                    }) {
                        Text(if (uiState.groupingMode == GroupingMode.ACCOUNT) "👤" else "📍")
                    }

                    var showSortMenu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { showSortMenu = true }) {
                            Text("🔃")
                        }
                        DropdownMenu(
                            expanded = showSortMenu,
                            onDismissRequest = { showSortMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Sortuj wg terminu zwrotu") },
                                onClick = { viewModel.setSortMode(SortMode.DUE_DATE); showSortMenu = false }
                            )
                            DropdownMenuItem(
                                text = { Text("Sortuj wg daty wypożyczenia") },
                                onClick = { viewModel.setSortMode(SortMode.LOAN_DATE); showSortMenu = false }
                            )
                            DropdownMenuItem(
                                text = { Text("Sortuj wg tytułu") },
                                onClick = { viewModel.setSortMode(SortMode.TITLE); showSortMenu = false }
                            )
                        }
                    }

                    IconButton(onClick = { currentScreen = "settings" }) {
                        Text("⚙️")
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            if (uiState.accounts.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                    Button(onClick = { currentScreen = "settings" }) {
                        Text("Dodaj pierwsze konto")
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
