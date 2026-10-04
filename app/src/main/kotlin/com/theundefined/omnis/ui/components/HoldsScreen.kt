package com.theundefined.omnis.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theundefined.omnis.R
import com.theundefined.omnis.data.model.Hold
import com.theundefined.omnis.data.model.displayTitle
import com.theundefined.omnis.ui.OmnisViewModel
import com.theundefined.omnis.ui.holdDeadline
import com.theundefined.omnis.ui.holdKey
import com.theundefined.omnis.ui.holdStatusDateMatch
import com.theundefined.omnis.ui.isHoldDeadlineUrgent
import kotlinx.coroutines.flow.collectLatest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HoldsScreen(viewModel: OmnisViewModel, onBack: () -> Unit) {
    val state by viewModel.holdsUiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    var confirmCancel by remember { mutableStateOf<Hold?>(null) }

    LaunchedEffect(Unit) { viewModel.loadHolds() }
    LaunchedEffect(state.error) { state.error?.let { snackbarHostState.showSnackbar(it) } }
    LaunchedEffect(Unit) {
        viewModel.events.collectLatest { event ->
            if (event is OmnisViewModel.UiEvent.HoldCancelFinished) {
                val title = displayTitle(event.title)
                snackbarHostState.showSnackbar(
                    if (event.error == null) context.getString(R.string.hold_cancelled, title)
                    else context.getString(R.string.hold_cancel_failed, title, event.error)
                )
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.holds_title)) },
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
                    val refreshDescription = stringResource(R.string.cd_refresh)
                    IconButton(
                        onClick = { viewModel.loadHolds(forceRefresh = true) },
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
                // Nic się nie udało pobrać — błąd do ponowienia, a nie "brak rezerwacji".
                !state.hasLoadedOnce && !state.isLoading && state.error != null -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(state.error ?: "", style = MaterialTheme.typography.bodyMedium)
                            Spacer(modifier = Modifier.height(8.dp))
                            TextButton(onClick = { viewModel.loadHolds() }) {
                                Text(stringResource(R.string.retry))
                            }
                        }
                    }
                }
                else -> {
                    PullToRefreshBox(
                        isRefreshing = state.isLoading,
                        onRefresh = { viewModel.loadHolds(forceRefresh = true) },
                        modifier = Modifier.fillMaxSize()
                    ) {
                        if (state.holds.values.all { it.isEmpty() }) {
                            // PullToRefreshBox wymaga przewijalnej zawartości, żeby gest działał.
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                item { Text(stringResource(R.string.no_holds)) }
                            }
                        } else {
                            HoldList(
                                groupedHolds = state.holds,
                                cancellingIds = state.cancellingIds,
                                onCancel = { confirmCancel = it },
                                onBranchClick = { viewModel.showBranchInfo(it) }
                            )
                        }
                    }
                }
            }
        }
    }

    BranchInfoDialogHost(viewModel)

    confirmCancel?.let { hold ->
        AlertDialog(
            onDismissRequest = { confirmCancel = null },
            title = { Text(stringResource(R.string.hold_cancel_confirm_title)) },
            text = {
                Text(stringResource(R.string.hold_cancel_confirm_message, displayTitle(hold.title)))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.cancelHold(hold)
                        confirmCancel = null
                    }
                ) {
                    Text(stringResource(R.string.hold_cancel))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmCancel = null }) {
                    Text(stringResource(R.string.hold_keep))
                }
            }
        )
    }
}

@Composable
private fun HoldList(
    groupedHolds: Map<String, List<Hold>>,
    cancellingIds: Set<String>,
    onCancel: (Hold) -> Unit,
    onBranchClick: (Hold) -> Unit
) {
    // Nazwę biblioteki pokazujemy tylko, gdy rezerwacje są z kilku — jak w LoanList.
    val withLibrary =
        remember(groupedHolds) {
            groupedHolds.values.flatten().distinctBy { it.tenantName }.size > 1
        }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        groupedHolds.forEach { (owner, holds) ->
            item {
                Text(
                    owner,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
            items(holds, key = { holdKey(it) }) { hold ->
                HoldItem(
                    hold,
                    withLibrary = withLibrary,
                    isCancelling = holdKey(hold) in cancellingIds,
                    onCancel = { onCancel(hold) },
                    onBranchClick = { onBranchClick(hold) }
                )
            }
            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun HoldItem(
    hold: Hold,
    withLibrary: Boolean,
    isCancelling: Boolean,
    onCancel: () -> Unit,
    onBranchClick: () -> Unit
) {
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        colors =
            if (hold.available)
                CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            else CardDefaults.cardColors()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(displayTitle(hold.title), style = MaterialTheme.typography.titleMedium)
            Text(
                hold.author ?: stringResource(R.string.unknown_author),
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(modifier = Modifier.height(8.dp))

            if (hold.status.isNotBlank()) {
                Text(
                    stringResource(R.string.hold_status_label, holdStatusText(hold.status)),
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                    color =
                        when {
                            // Ostatnie dni na odbiór. colorScheme.error, nie czerwień z wypożyczeń
                            // — ta ginie na
                            // fioletowym tle karty gotowej rezerwacji w ciemnym motywie.
                            holdDeadline(hold)?.let { isHoldDeadlineUrgent(it) } == true ->
                                MaterialTheme.colorScheme.error
                            hold.available -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurface
                        }
                )
            }
            hold.pickupLocation
                ?.takeIf { it.isNotBlank() }
                ?.let { location ->
                    // Jak filia przy wypożyczeniu: klik otwiera adres i nawigację.
                    Text(
                        stringResource(
                            R.string.hold_pickup_label,
                            if (withLibrary) "${hold.tenantName} - $location" else location
                        ),
                        modifier =
                            Modifier.clickable(
                                onClickLabel = stringResource(R.string.cd_branch_info),
                                onClick = onBranchClick
                            ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            hold.requestDate
                ?.takeIf { it.isNotBlank() }
                ?.let {
                    Text(
                        stringResource(R.string.hold_requested_on, formatPastRelativeDate(it)),
                        style = MaterialTheme.typography.bodySmall
                    )
                }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!hold.pickupLocation.isNullOrBlank()) {
                    IconButton(onClick = onBranchClick) {
                        Icon(
                            painterResource(R.drawable.ic_place),
                            stringResource(R.string.cd_branch_info)
                        )
                    }
                }
                IconButton(
                    onClick = { openWebSearch(context, displayTitle(hold.title), hold.author) }
                ) {
                    Icon(
                        painterResource(R.drawable.ic_public),
                        stringResource(R.string.cd_search_web)
                    )
                }
                if (hold.cancellable) {
                    if (isCancelling) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(horizontal = 16.dp).size(24.dp)
                        )
                    } else {
                        OutlinedButton(onClick = onCancel) {
                            Text(stringResource(R.string.hold_cancel))
                        }
                    }
                }
            }
        }
    }
}

/**
 * Status z Primo z datą w formacie dd/MM/yyyy ("Na półce rezerwacji do 08/10/2026") — podmieniamy
 * ją na format reszty aplikacji z odliczaniem: "do 2026.10.08 (za 4 dni)".
 */
@Composable
private fun holdStatusText(status: String): String {
    val match = holdStatusDateMatch(status) ?: return status
    return status.replaceRange(match.range, formatRelativeDate(match.value))
}
