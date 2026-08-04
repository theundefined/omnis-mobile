package com.theundefined.omnis.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theundefined.omnis.R
import com.theundefined.omnis.ui.GroupingMode
import com.theundefined.omnis.ui.HistoryUiState
import com.theundefined.omnis.ui.OmnisViewModel
import com.theundefined.omnis.ui.SortMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(viewModel: OmnisViewModel, onBack: () -> Unit) {
    val state by viewModel.historyUiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // hasLoadedOnce w ViewModelu robi za guard — wielokrotne wejście na ekran nie odpala
    // ponownego fetchu/re-loginu, dopóki użytkownik sam nie zażąda odświeżenia.
    LaunchedEffect(Unit) { viewModel.loadHistory() }
    LaunchedEffect(state.error) { state.error?.let { snackbarHostState.showSnackbar(it) } }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.history_title)) },
                navigationIcon = {
                    val backDescription = stringResource(R.string.cd_back)
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.semantics { contentDescription = backDescription }
                    ) {
                        Text("←", style = MaterialTheme.typography.headlineSmall)
                    }
                },
                actions = {
                    val groupingDescription =
                        stringResource(
                            if (state.groupingMode == GroupingMode.ACCOUNT)
                                R.string.cd_group_by_account
                            else R.string.cd_group_by_branch
                        )
                    IconButton(
                        onClick = {
                            val nextMode =
                                if (state.groupingMode == GroupingMode.ACCOUNT) GroupingMode.BRANCH
                                else GroupingMode.ACCOUNT
                            viewModel.setHistoryGroupingMode(nextMode)
                        },
                        modifier = Modifier.semantics { contentDescription = groupingDescription }
                    ) {
                        Text(if (state.groupingMode == GroupingMode.ACCOUNT) "👤" else "📍")
                    }

                    val refreshDescription = stringResource(R.string.cd_refresh)
                    IconButton(
                        onClick = { viewModel.loadHistory(forceRefresh = true) },
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
                                    viewModel.setHistorySortMode(SortMode.DUE_DATE)
                                    showSortMenu = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.sort_by_loan_date)) },
                                onClick = {
                                    viewModel.setHistorySortMode(SortMode.LOAN_DATE)
                                    showSortMenu = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.sort_by_title)) },
                                onClick = {
                                    viewModel.setHistorySortMode(SortMode.TITLE)
                                    showSortMenu = false
                                }
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when {
                state.isLoading && !state.hasLoadedOnce -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                // Nic się jeszcze nie udało załadować (brak włączonych kont albo wszystkie
                // fetche padły) — inny stan niż "sprawdziliśmy, historia jest pusta", więc nie
                // pokazujemy tu no_history: dawałoby to fałszywe wrażenie, że to potwierdzony
                // brak historii, zamiast błędu, który warto ponowić.
                !state.hasLoadedOnce && !state.isLoading && state.error != null -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(state.error ?: "", style = MaterialTheme.typography.bodyMedium)
                            Spacer(modifier = Modifier.height(8.dp))
                            TextButton(onClick = { viewModel.loadHistory() }) {
                                Text(stringResource(R.string.retry))
                            }
                        }
                    }
                }
                // Pokrywa zarówno "sprawdziliśmy, historia jest pusta", jak i "brak włączonych
                // kont" (state.loans jest wtedy trywialnie pustą mapą) — w obu przypadkach nie
                // ma czego pokazać i nie ma błędu do zgłoszenia, więc ten sam komunikat pasuje.
                !state.isLoading && state.loans.values.all { it.isEmpty() } -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.no_history))
                    }
                }
                else -> {
                    PullToRefreshBox(
                        isRefreshing = state.isLoading,
                        onRefresh = { viewModel.loadHistory(forceRefresh = true) },
                        modifier = Modifier.fillMaxSize()
                    ) {
                        LoanList(
                            groupedLoans = state.loans,
                            isHistory = true,
                            footer = {
                                HistoryFooter(state, onLoadMore = { viewModel.loadMoreHistory() })
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryFooter(state: HistoryUiState, onLoadMore: () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
        when {
            // Podczas pełnego (force)refreshu stopka nic nie pokazuje — spinner odświeżania
            // jest już widoczny wyżej w PullToRefreshBox, a klikalny przycisk "Załaduj więcej"
            // odpytałby o tę samą stronę, którą refresh właśnie pobiera (ViewModel i tak to
            // zablokuje, ale nie ma sensu pokazywać martwego przycisku).
            state.isLoading -> {}
            state.isLoadingMore -> CircularProgressIndicator()
            state.canLoadMore ->
                TextButton(onClick = onLoadMore) { Text(stringResource(R.string.load_more)) }
            else ->
                Text(
                    stringResource(R.string.history_end),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
        }
    }
}
