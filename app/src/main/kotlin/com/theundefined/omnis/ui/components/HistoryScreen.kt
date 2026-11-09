package com.theundefined.omnis.ui.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theundefined.omnis.R
import com.theundefined.omnis.data.model.Loan
import com.theundefined.omnis.data.model.SearchField
import com.theundefined.omnis.ui.GroupingMode
import com.theundefined.omnis.ui.HistoryUiState
import com.theundefined.omnis.ui.OmnisViewModel
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    viewModel: OmnisViewModel,
    onBack: () -> Unit,
    onSearch: (Loan, String, SearchField) -> Unit
) {
    val state by viewModel.historyUiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // hasLoadedOnce w ViewModelu robi za guard — wielokrotne wejście na ekran nie odpala
    // ponownego fetchu/re-loginu, dopóki użytkownik sam nie zażąda odświeżenia.
    LaunchedEffect(Unit) { viewModel.loadHistory() }
    LaunchedEffect(state.error) { state.error?.let { snackbarHostState.showSnackbar(it) } }
    LaunchedEffect(state.exportMessage) {
        state.exportMessage?.let {
            viewModel.consumeExportMessage()
            snackbarHostState.showSnackbar(it)
        }
    }
    // SAF: użytkownik wybiera miejsce i nazwę pliku, aplikacja nie potrzebuje żadnych uprawnień.
    val exportLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri
            ->
            if (uri != null) viewModel.exportHistoryCsv(uri)
        }

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
                    val exportDescription = stringResource(R.string.cd_history_export)
                    IconButton(
                        onClick = {
                            exportLauncher.launch("omnis-historia-${LocalDate.now()}.csv")
                        },
                        enabled = state.hasLoadedOnce && !state.isLoading && !state.isExporting,
                        modifier = Modifier.semantics { contentDescription = exportDescription }
                    ) {
                        Icon(painterResource(R.drawable.ic_download), contentDescription = null)
                    }
                    val refreshDescription = stringResource(R.string.cd_refresh)
                    IconButton(
                        onClick = { viewModel.loadHistory(forceRefresh = true) },
                        modifier = Modifier.semantics { contentDescription = refreshDescription }
                    ) {
                        Icon(painterResource(R.drawable.ic_refresh), contentDescription = null)
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
                // Z aktywnym filtrem pusta lista to "nic nie pasuje" — zostajemy w widoku z polem
                // filtra, żeby dało się go zmienić.
                !state.isLoading &&
                    state.filter.isEmpty() &&
                    state.loans.values.all { it.isEmpty() } -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.no_history))
                    }
                }
                else -> {
                    Column {
                        LoanViewBar(
                            groupingMode = state.groupingMode,
                            sortMode = state.sortMode,
                            onGroupingChange = { viewModel.setHistoryGroupingMode(it) },
                            onSortChange = { viewModel.setHistorySortMode(it) }
                        )
                        HistoryFilterField(
                            state.filter,
                            onChange = { viewModel.setHistoryFilter(it) }
                        )
                        if (state.filter.isNotBlank()) {
                            Text(
                                stringResource(R.string.history_export_filter_note),
                                modifier = Modifier.padding(horizontal = 16.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (state.isExporting) {
                            Column(
                                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    stringResource(R.string.history_export_progress),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
                            }
                        }
                        PullToRefreshBox(
                            isRefreshing = state.isLoading,
                            onRefresh = { viewModel.loadHistory(forceRefresh = true) },
                            modifier = Modifier.fillMaxSize()
                        ) {
                            if (
                                state.filter.isNotEmpty() && state.loans.values.all { it.isEmpty() }
                            ) {
                                // Przewijalna kolumna, bo PullToRefreshBox reaguje tylko na
                                // przewijaną zawartość; stopka pozwala doładować resztę historii.
                                Column(
                                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        stringResource(R.string.history_filter_no_results),
                                        modifier = Modifier.padding(top = 32.dp)
                                    )
                                    HistoryFooter(
                                        state,
                                        onLoadMore = { viewModel.loadMoreHistory() }
                                    )
                                }
                            } else
                                LoanList(
                                    groupedLoans = state.loans,
                                    isHistory = true,
                                    onBranchClick = { loans -> viewModel.showBranchInfo(loans) },
                                    branchHeaders = state.groupingMode == GroupingMode.BRANCH,
                                    onSearch = onSearch,
                                    footer = {
                                        HistoryFooter(
                                            state,
                                            onLoadMore = { viewModel.loadMoreHistory() }
                                        )
                                    }
                                )
                            BranchInfoDialogHost(viewModel)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryFilterField(filter: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = filter,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        placeholder = { Text(stringResource(R.string.history_filter_hint)) },
        leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null) },
        trailingIcon = {
            if (filter.isNotEmpty()) {
                IconButton(onClick = { onChange("") }) {
                    Icon(
                        painterResource(R.drawable.ic_close),
                        contentDescription = stringResource(R.string.cd_clear_filter)
                    )
                }
            }
        },
        singleLine = true
    )
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
                // Podczas eksportu CSV (dociąga całą historię) doładowanie byłoby martwe/groźne.
                TextButton(onClick = onLoadMore, enabled = !state.isExporting) {
                    Text(stringResource(R.string.load_more))
                }
            else ->
                Text(
                    stringResource(R.string.history_end),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
        }
    }
}
