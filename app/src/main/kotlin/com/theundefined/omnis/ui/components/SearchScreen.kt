package com.theundefined.omnis.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theundefined.omnis.R
import com.theundefined.omnis.data.model.BranchAvailability
import com.theundefined.omnis.data.model.SearchResult
import com.theundefined.omnis.ui.OmnisViewModel
import com.theundefined.omnis.ui.SearchTenantSection
import com.theundefined.omnis.ui.checkboxBranches
import com.theundefined.omnis.ui.filteredResults

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(viewModel: OmnisViewModel, onBack: () -> Unit) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val state by viewModel.searchUiState.collectAsStateWithLifecycle()
    var queryInput by remember { mutableStateOf(state.query) }

    fun triggerSearch() {
        viewModel.runSearch(queryInput)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.search_title)) },
                navigationIcon = {
                    val backDescription = stringResource(R.string.cd_back)
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.semantics { contentDescription = backDescription }
                    ) {
                        Text("←", style = MaterialTheme.typography.headlineSmall)
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = queryInput,
                onValueChange = { queryInput = it },
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                placeholder = { Text(stringResource(R.string.search_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { triggerSearch() }),
                trailingIcon = {
                    val searchDescription = stringResource(R.string.cd_search)
                    IconButton(
                        onClick = { triggerSearch() },
                        modifier = Modifier.semantics { contentDescription = searchDescription }
                    ) {
                        Text("🔎")
                    }
                }
            )

            val noEnabledAccounts = uiState.accounts.none { it.isEnabled }
            val hasAnyRawResults = state.tenantSections.any { it.results.isNotEmpty() }
            val allFilteredEmpty =
                state.tenantSections.isNotEmpty() &&
                    state.tenantSections.all { it.filteredResults().isEmpty() && !it.isLoading }

            when {
                noEnabledAccounts -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.add_first_account))
                    }
                }
                !state.hasSearched -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            stringResource(R.string.search_before_hint),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
                state.isLoading && state.tenantSections.all { it.results.isEmpty() } -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                allFilteredEmpty -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            if (hasAnyRawResults) {
                                Text(
                                    stringResource(R.string.no_search_results_filtered),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                TextButton(
                                    onClick = {
                                        state.tenantSections.forEach {
                                            viewModel.setShowAllBranches(it.tenantKey, true)
                                        }
                                    }
                                ) {
                                    Text(stringResource(R.string.all_branches))
                                }
                            } else {
                                Text(
                                    stringResource(R.string.no_search_results),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                }
                else -> {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        state.tenantSections.forEach { section ->
                            item(key = section.tenantKey) {
                                SearchTenantSectionView(section = section, viewModel = viewModel)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchTenantSectionView(section: SearchTenantSection, viewModel: OmnisViewModel) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text(
            text = section.tenantLabel,
            modifier = Modifier.padding(vertical = 16.dp),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.secondary
        )

        FilterChip(
            selected = section.showAllBranches,
            onClick = { viewModel.setShowAllBranches(section.tenantKey, !section.showAllBranches) },
            label = { Text(stringResource(R.string.all_branches)) }
        )

        if (!section.showAllBranches && section.checkboxBranches().isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                section.checkboxBranches().forEach { branch ->
                    FilterChip(
                        selected = branch in section.selectedBranches,
                        onClick = {
                            viewModel.setBranchSelection(
                                section.tenantKey,
                                branch,
                                branch !in section.selectedBranches
                            )
                        },
                        label = { Text(branch) }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (section.isLoading) {
            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        section.error?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }

        section.filteredResults().forEach { result -> SearchResultCard(result) }

        // Paginacja per biblioteka — ten sam wzorzec co HistoryScreen.HistoryFooter, bez
        // trwałego cache'u (wyniki wyszukiwania są efemeryczne, patrz docs/plans/book-search.md
        // §7).
        Box(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            when {
                section.isLoading -> {}
                section.isLoadingMore -> CircularProgressIndicator()
                section.canLoadMore ->
                    TextButton(onClick = { viewModel.loadMoreResults(section.tenantKey) }) {
                        Text(stringResource(R.string.load_more))
                    }
                section.results.isNotEmpty() ->
                    Text(
                        stringResource(R.string.search_results_end),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
            }
        }
    }
}

@Composable
private fun SearchResultCard(result: SearchResult) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(result.title, style = MaterialTheme.typography.titleMedium)
            result.author?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }

            result.versions.forEach { version ->
                Spacer(modifier = Modifier.height(8.dp))
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            version.edition ?: "-",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            version.publicationDate ?: "-",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    version.branches.forEach { branch ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val label =
                                branch.subLocation?.let { "${branch.libraryName} – $it" }
                                    ?: branch.libraryName
                            Text(label, style = MaterialTheme.typography.bodySmall)
                            BranchStatusBadge(branch)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BranchStatusBadge(branch: BranchAvailability) {
    val available = Color(0xFF388E3C)
    val warning = Color(0xFFFBC02D)
    val overdueColor = Color(0xFFD32F2F)

    when {
        branch.status == "available" ->
            Text(
                stringResource(R.string.status_available),
                color = available,
                style = MaterialTheme.typography.bodySmall
            )
        branch.status == "unavailable" && branch.dueDate != null -> {
            // `dueDate` jest `var` (patrz BranchAvailability) — Kotlin nie smart-castuje
            // mutowalnych właściwości, stąd jednorazowe `!!` po już wykonanym null-checku wyżej.
            val formattedDate = formatPlainDate(branch.dueDate!!)
            Text(
                if (branch.overdue) stringResource(R.string.status_overdue_since, formattedDate)
                else stringResource(R.string.status_borrowed_until, formattedDate),
                color = if (branch.overdue) overdueColor else warning,
                style = MaterialTheme.typography.bodySmall
            )
        }
        branch.status == "unavailable" ->
            Text(
                stringResource(R.string.status_borrowed_unknown),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
        else ->
            Text(
                stringResource(R.string.status_unknown),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
    }
}
