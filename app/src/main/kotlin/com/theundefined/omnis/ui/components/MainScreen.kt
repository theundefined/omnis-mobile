package com.theundefined.omnis.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theundefined.omnis.R
import com.theundefined.omnis.data.model.Hold
import com.theundefined.omnis.ui.GroupingMode
import com.theundefined.omnis.ui.OmnisViewModel
import com.theundefined.omnis.ui.RefreshProgress
import com.theundefined.omnis.ui.holdDeadline
import com.theundefined.omnis.ui.isHoldDeadlineUrgent
import com.theundefined.omnis.ui.isReadyHoldsBannerVisible
import com.theundefined.omnis.ui.readyHolds
import kotlinx.coroutines.flow.collectLatest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: OmnisViewModel, scanRequests: Int = 0) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val holdsState by viewModel.holdsUiState.collectAsStateWithLifecycle()
    val dismissedReadyHolds by viewModel.dismissedReadyHolds.collectAsStateWithLifecycle()
    var currentScreen by remember { mutableStateOf("main") }
    // Skan zlecony skrótem aplikacji, konsumowany jednorazowo przez SearchScreen — dzięki temu
    // powrót do wyszukiwarki z listy nie odpala skanera ponownie.
    var pendingScan by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    val error = uiState.error

    // Navigation and error handling logic
    LaunchedEffect(error) {
        if (error != null && currentScreen == "main") {
            snackbarHostState.showSnackbar(error)
        }
    }

    // Rezerwacje w tle, żeby ekran główny mógł pokazać te do odbioru. Po wczytaniu w tej sesji
    // loadHolds() bez forceRefresh nic nie robi, więc zmiany listy kont (np. odświeżone kary) nie
    // powodują ponownych logowań; zmiana zestawu kont i tak kasuje stan rezerwacji.
    LaunchedEffect(uiState.accounts) { if (uiState.accounts.isNotEmpty()) viewModel.loadHolds() }

    LaunchedEffect(scanRequests) {
        if (scanRequests > 0) {
            currentScreen = "search"
            pendingScan = true
        }
    }

    LaunchedEffect(Unit) {
        viewModel.events.collectLatest { event ->
            if (event is OmnisViewModel.UiEvent.BulkRenewFinished) {
                val message =
                    if (event.failedTitles.isEmpty()) {
                        context.getString(R.string.bulk_renew_success, event.succeeded)
                    } else {
                        context.getString(
                            R.string.bulk_renew_partial,
                            event.succeeded,
                            event.succeeded + event.failedTitles.size,
                            event.failedTitles.joinToString(", ")
                        )
                    }
                snackbarHostState.showSnackbar(message)
            }
        }
    }

    BackHandler(
        enabled =
            currentScreen == "settings" ||
                currentScreen == "history" ||
                currentScreen == "stats" ||
                currentScreen == "holds" ||
                currentScreen == "search" ||
                currentScreen == "card"
    ) {
        currentScreen = "main"
    }

    if (currentScreen == "settings") {
        SettingsScreen(
            viewModel = viewModel,
            accounts = uiState.accounts,
            onToggleAccount = { viewModel.toggleAccount(it) },
            onRemoveAccount = { viewModel.removeAccount(it) },
            onAddAccount = { user, pass, tenant -> viewModel.addAccount(user, pass, tenant) },
            onAddCustomAccount = { user, pass, link ->
                viewModel.addCustomAccount(user, pass, link)
            },
            onEnterDemoMode = { viewModel.enterDemoMode() },
            onExitDemoMode = { viewModel.exitDemoMode() },
            isLoading = uiState.isLoading,
            onBack = { currentScreen = "main" },
            errorMessage = error
        )
        return
    }

    if (currentScreen == "history") {
        HistoryScreen(
            viewModel = viewModel,
            onBack = { currentScreen = "main" },
            onSearch = { loan, query, field ->
                viewModel.searchFromLoan(loan, query, field)
                currentScreen = "search"
            }
        )
        return
    }

    if (currentScreen == "stats") {
        StatsScreen(viewModel = viewModel, onBack = { currentScreen = "main" })
        return
    }

    if (currentScreen == "holds") {
        HoldsScreen(viewModel = viewModel, onBack = { currentScreen = "main" })
        return
    }

    if (currentScreen == "card") {
        LibraryCardScreen(
            accounts = uiState.accounts,
            onSetCardNumber = { account, number -> viewModel.setCardNumber(account, number) },
            onBack = { currentScreen = "main" }
        )
        return
    }

    if (currentScreen == "search") {
        SearchScreen(
            viewModel = viewModel,
            onBack = { currentScreen = "main" },
            autoStartScan = pendingScan,
            onAutoStartScanConsumed = { pendingScan = false }
        )
        return
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    val searchDescription = stringResource(R.string.cd_search)
                    IconButton(
                        onClick = { currentScreen = "search" },
                        modifier = Modifier.semantics { contentDescription = searchDescription }
                    ) {
                        Icon(painterResource(R.drawable.ic_search), contentDescription = null)
                    }

                    val refreshDescription = stringResource(R.string.cd_refresh)
                    IconButton(
                        onClick = {
                            viewModel.refreshAllLoans(isManual = true)
                            viewModel.loadHolds(forceRefresh = true)
                        },
                        modifier = Modifier.semantics { contentDescription = refreshDescription }
                    ) {
                        Icon(painterResource(R.drawable.ic_refresh), contentDescription = null)
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
                                text = { Text(stringResource(R.string.library_card_title)) },
                                onClick = {
                                    showMoreMenu = false
                                    currentScreen = "card"
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.holds_title)) },
                                onClick = {
                                    showMoreMenu = false
                                    currentScreen = "holds"
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.history_title)) },
                                onClick = {
                                    showMoreMenu = false
                                    currentScreen = "history"
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.stats_title)) },
                                onClick = {
                                    showMoreMenu = false
                                    currentScreen = "stats"
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
                Column {
                    uiState.refreshProgress?.let { RefreshProgressBar(it) }
                    val ready = remember(holdsState.holds) { readyHolds(holdsState.holds) }
                    if (isReadyHoldsBannerVisible(ready, dismissedReadyHolds)) {
                        ReadyHoldsBanner(
                            ready,
                            onClick = { currentScreen = "holds" },
                            onDismiss = { viewModel.dismissReadyHolds(ready) }
                        )
                    }
                    LoanViewBar(
                        groupingMode = uiState.groupingMode,
                        sortMode = uiState.sortMode,
                        onGroupingChange = { viewModel.setGroupingMode(it) },
                        onSortChange = { viewModel.setSortMode(it) }
                    )
                    PullToRefreshBox(
                        isRefreshing = uiState.isLoading,
                        onRefresh = {
                            viewModel.refreshAllLoans(isManual = true)
                            viewModel.loadHolds(forceRefresh = true)
                        },
                        modifier = Modifier.fillMaxSize()
                    ) {
                        LoanList(
                            groupedLoans = uiState.loans,
                            onRenew = { loan -> viewModel.renewLoan(loan) },
                            onRenewAll = { loans -> viewModel.renewAllInGroup(loans) },
                            onBranchClick = { loans -> viewModel.showBranchInfo(loans) },
                            branchHeaders = uiState.groupingMode == GroupingMode.BRANCH,
                            onSearch = { loan, query, field ->
                                viewModel.searchFromLoan(loan, query, field)
                                currentScreen = "search"
                            }
                        )
                        BranchInfoDialogHost(viewModel)
                    }
                }
            }
        }
    }
}

/** Rezerwacje czekające na odbiór — z najbliższym terminem; czerwony, gdy zostały ostatnie dni. */
@Composable
private fun ReadyHoldsBanner(holds: List<Hold>, onClick: () -> Unit, onDismiss: () -> Unit) {
    val deadline = holds.firstNotNullOfOrNull { holdDeadline(it) }
    val urgent = deadline?.let { isHoldDeadlineUrgent(it) } == true
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (urgent) MaterialTheme.colorScheme.errorContainer
                    else MaterialTheme.colorScheme.primaryContainer
            )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    LocalContext.current.resources.getQuantityString(
                        R.plurals.holds_ready,
                        holds.size,
                        holds.size
                    ),
                    style = MaterialTheme.typography.titleSmall
                )
                deadline?.let {
                    Text(
                        stringResource(
                            R.string.holds_ready_deadline,
                            formatRelativeDate(it.toString())
                        ),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            Text("›", style = MaterialTheme.typography.headlineSmall)
            val dismissDescription = stringResource(R.string.cd_dismiss_ready_holds)
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.semantics { contentDescription = dismissDescription }
            ) {
                Icon(painterResource(R.drawable.ic_close), contentDescription = null)
            }
        }
    }
}

@Composable
private fun RefreshProgressBar(progress: RefreshProgress) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(
            if (progress.catalogTotal != null)
                stringResource(
                    R.string.refresh_progress_catalog,
                    progress.accountName,
                    progress.catalogDone,
                    progress.catalogTotal
                )
            else
                stringResource(
                    R.string.refresh_progress_account,
                    progress.account,
                    progress.accounts,
                    progress.accountName
                ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        LinearProgressIndicator(
            progress = { progress.fraction },
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
        )
    }
}
