package com.theundefined.omnis.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.theundefined.omnis.data.model.displayTitle
import com.theundefined.omnis.guard.WatchRow
import com.theundefined.omnis.guard.WatchStatus
import com.theundefined.omnis.guard.WatchedTitle
import com.theundefined.omnis.ui.OmnisViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val CHECKED_FORMAT = DateTimeFormatter.ofPattern("dd.MM HH:mm")

/**
 * Lista „Do przeczytania": obserwowane tytuły ze stanem dostępności (wyliczanym z uwzględnieniem
 * preferowanych filii biblioteki). Dotknięcie otwiera wyszukiwarkę z tym tytułem, gdzie jest
 * „Zarezerwuj".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatchlistScreen(viewModel: OmnisViewModel, onBack: () -> Unit, onOpen: (WatchedTitle) -> Unit) {
    val state by viewModel.watchlistUiState.collectAsStateWithLifecycle()
    val guardState by viewModel.guardUiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = androidx.compose.ui.platform.LocalContext.current

    // Preferencje filii mogły się zmienić w wyszukiwarce, a strażnik — odświeżyć stan w tle.
    LaunchedEffect(Unit) { viewModel.loadWatchlist() }
    LaunchedEffect(state.failedLibraries, state.isRefreshing) {
        if (!state.isRefreshing && state.failedLibraries > 0) {
            snackbarHostState.showSnackbar(
                context.getString(
                    R.string.watch_refresh_failed,
                    state.failedLibraries,
                    state.libraries
                )
            )
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.watchlist_title)) },
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
                        onClick = { viewModel.refreshWatchlist() },
                        modifier = Modifier.semantics { contentDescription = refreshDescription }
                    ) {
                        Icon(painterResource(R.drawable.ic_refresh), contentDescription = null)
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            // Powiadomienia idą z przebiegu strażnika — bez niego lista działa tylko ręcznie.
            if (!guardState.settings.enabled || !guardState.settings.watchlistNotify) {
                Text(
                    stringResource(R.string.watch_hint_notifications),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            PullToRefreshBox(
                isRefreshing = state.isRefreshing,
                onRefresh = { viewModel.refreshWatchlist() },
                modifier = Modifier.fillMaxSize()
            ) {
                // PullToRefreshBox wymaga przewijalnej zawartości, także gdy lista jest pusta.
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement =
                        if (state.rows.isEmpty()) Arrangement.Center else Arrangement.Top
                ) {
                    if (state.rows.isEmpty()) {
                        item {
                            Text(
                                stringResource(R.string.watch_empty),
                                modifier = Modifier.padding(32.dp),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                    items(state.rows, key = { it.title.id }) { row ->
                        WatchItem(
                            row,
                            onOpen = { onOpen(row.title) },
                            onRemove = { viewModel.unwatch(row.title.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WatchItem(row: WatchRow, onOpen: () -> Unit, onRemove: () -> Unit) {
    val title = row.title
    Card(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(displayTitle(title.title), style = MaterialTheme.typography.titleMedium)
                title.author?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                Text(
                    title.tenant.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val available = row.status == WatchStatus.AVAILABLE
                Text(
                    when (row.status) {
                        WatchStatus.AVAILABLE ->
                            stringResource(
                                R.string.watch_status_available,
                                row.branches.joinToString(", ")
                            )
                        WatchStatus.UNAVAILABLE -> stringResource(R.string.watch_status_unavailable)
                        WatchStatus.NO_COPIES -> stringResource(R.string.watch_status_no_copies)
                        WatchStatus.NOT_CHECKED -> stringResource(R.string.watch_status_not_checked)
                    },
                    modifier = Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color =
                        if (available) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                )
                title.lastCheckedAt?.let {
                    Text(
                        stringResource(
                            R.string.watch_checked_at,
                            Instant.ofEpochMilli(it)
                                .atZone(ZoneId.systemDefault())
                                .format(CHECKED_FORMAT)
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            val removeDescription = stringResource(R.string.cd_watch_remove)
            IconButton(
                onClick = onRemove,
                modifier = Modifier.semantics { contentDescription = removeDescription }
            ) {
                Icon(painterResource(R.drawable.ic_delete), contentDescription = null)
            }
        }
    }
}
