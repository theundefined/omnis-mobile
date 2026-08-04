package com.theundefined.omnis.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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

    BackHandler(enabled = currentScreen == "settings" || currentScreen == "history") {
        currentScreen = "main"
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

    if (currentScreen == "history") {
        HistoryScreen(viewModel = viewModel, onBack = { currentScreen = "main" })
        return
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    val groupingDescription =
                        stringResource(
                            if (uiState.groupingMode == GroupingMode.ACCOUNT)
                                R.string.cd_group_by_account
                            else R.string.cd_group_by_branch
                        )
                    IconButton(
                        onClick = {
                            val nextMode =
                                if (uiState.groupingMode == GroupingMode.ACCOUNT)
                                    GroupingMode.BRANCH
                                else GroupingMode.ACCOUNT
                            viewModel.setGroupingMode(nextMode)
                        },
                        modifier = Modifier.semantics { contentDescription = groupingDescription }
                    ) {
                        Text(if (uiState.groupingMode == GroupingMode.ACCOUNT) "👤" else "📍")
                    }

                    val refreshDescription = stringResource(R.string.cd_refresh)
                    IconButton(
                        onClick = { viewModel.refreshAllLoans(isManual = true) },
                        modifier = Modifier.semantics { contentDescription = refreshDescription }
                    ) {
                        Text("🔄")
                    }

                    var showSortMenu by remember { mutableStateOf(false) }
                    val sortDescription = stringResource(R.string.cd_sort)
                    Box {
                        IconButton(
                            onClick = { showSortMenu = true },
                            modifier = Modifier.semantics { contentDescription = sortDescription }
                        ) {
                            Text("🔃")
                        }
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

                    var showMoreMenu by remember { mutableStateOf(false) }
                    val moreDescription = stringResource(R.string.cd_more_options)
                    Box {
                        IconButton(
                            onClick = { showMoreMenu = true },
                            modifier = Modifier.semantics { contentDescription = moreDescription }
                        ) {
                            Text("⋮")
                        }
                        DropdownMenu(
                            expanded = showMoreMenu,
                            onDismissRequest = { showMoreMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.history_title)) },
                                onClick = {
                                    showMoreMenu = false
                                    currentScreen = "history"
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.cd_settings)) },
                                onClick = {
                                    showMoreMenu = false
                                    currentScreen = "settings"
                                }
                            )
                        }
                    }
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
                PullToRefreshBox(
                    isRefreshing = uiState.isLoading,
                    onRefresh = { viewModel.refreshAllLoans(isManual = true) },
                    modifier = Modifier.fillMaxSize()
                ) {
                    LoanList(
                        groupedLoans = uiState.loans,
                        onRenew = { loan -> viewModel.renewLoan(loan) }
                    )
                }
            }
        }
    }
}
