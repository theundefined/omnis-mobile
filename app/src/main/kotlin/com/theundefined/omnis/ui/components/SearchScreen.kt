package com.theundefined.omnis.ui.components

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theundefined.omnis.R
import com.theundefined.omnis.data.model.BookVersion
import com.theundefined.omnis.data.model.BranchAvailability
import com.theundefined.omnis.data.model.SearchResult
import com.theundefined.omnis.ui.OmnisViewModel
import com.theundefined.omnis.ui.SearchSortMode
import com.theundefined.omnis.ui.SearchTenantSection
import com.theundefined.omnis.ui.branchChipLabel
import com.theundefined.omnis.ui.checkboxBranches
import com.theundefined.omnis.ui.filteredResults
import java.text.Collator
import java.util.Locale

private val polishCollator: Collator =
    Collator.getInstance(Locale("pl")).apply { strength = Collator.PRIMARY }

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
            val errorSections = state.tenantSections.filter { it.error != null }
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
                            errorSections.forEach { section ->
                                Text(
                                    if (state.tenantSections.size > 1)
                                        "${section.tenantLabel}: ${section.error}"
                                    else section.error!!,
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                            if (errorSections.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(8.dp))
                            }
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
                            } else if (errorSections.size < state.tenantSections.size) {
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
                section.checkboxBranches().sortedWith(compareBy(polishCollator) { it }).forEach {
                    branch ->
                    FilterChip(
                        selected = branch in section.selectedBranches,
                        onClick = {
                            viewModel.setBranchSelection(
                                section.tenantKey,
                                branch,
                                branch !in section.selectedBranches
                            )
                        },
                        label = { Text(section.branchChipLabel(branch)) }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        SearchSortControl(section = section, viewModel = viewModel)

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

/**
 * Sortowanie wyników jest wyłącznie klient-side (SearchTenantSection.filteredResults()) i nie
 * przetrwałoby doładowania kolejnej strony bez utraty spójności kolejności, więc chip "Tytuł" jest
 * dostępny dopiero po załadowaniu wszystkich stron danej biblioteki (!canLoadMore) — patrz
 * OmnisViewModel.setSearchSortMode.
 */
@Composable
private fun SearchSortControl(section: SearchTenantSection, viewModel: OmnisViewModel) {
    if (section.results.isEmpty()) return

    if (section.canLoadMore || section.isLoadingMore) {
        Text(
            stringResource(R.string.search_sort_hint_load_all),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }

    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = section.sortMode == SearchSortMode.RELEVANCE,
            onClick = { viewModel.setSearchSortMode(section.tenantKey, SearchSortMode.RELEVANCE) },
            label = { Text(stringResource(R.string.search_sort_relevance)) }
        )
        FilterChip(
            selected = section.sortMode == SearchSortMode.TITLE,
            onClick = { viewModel.setSearchSortMode(section.tenantKey, SearchSortMode.TITLE) },
            label = { Text(stringResource(R.string.search_sort_title)) }
        )
    }
}

@Composable
private fun SearchResultCard(result: SearchResult) {
    val context = LocalContext.current

    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(result.title, style = MaterialTheme.typography.titleMedium)
            result.author?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            result.versions
                .firstNotNullOfOrNull { it.series }
                ?.let {
                    Text(
                        stringResource(R.string.series_label, it),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }

            result.versions.forEach { version ->
                // key() na mmsid — bez tego `remember` niżej wiąże się z pozycją w forEach, nie z
                // konkretnym wydaniem, więc np. zmiana sortowania/filtra filii (które przebudowują
                // filteredResults()) przesuwałaby stan "rozwinięty opis" na inne wydanie.
                key(version.mmsid) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                editionLabel(version),
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                version.publicationDate ?: "-",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        val metaParts =
                            listOfNotNull(
                                version.language?.let {
                                    stringResource(R.string.language_label, it)
                                },
                                version.physicalDescription
                            )
                        if (metaParts.isNotEmpty()) {
                            Text(
                                metaParts.joinToString(" • "),
                                modifier = Modifier.padding(top = 4.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        val tags = (version.genres + version.subjects).distinct()
                        if (tags.isNotEmpty()) {
                            Text(
                                tags.joinToString(", "),
                                modifier = Modifier.padding(top = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        val description = version.description
                        if (!description.isNullOrBlank()) {
                            var descriptionExpanded by remember { mutableStateOf(false) }
                            TextButton(
                                onClick = { descriptionExpanded = !descriptionExpanded },
                                contentPadding = PaddingValues(vertical = 4.dp)
                            ) {
                                Text(
                                    stringResource(
                                        if (descriptionExpanded) R.string.hide_description
                                        else R.string.show_description
                                    ),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                            if (descriptionExpanded) {
                                Text(
                                    description,
                                    modifier = Modifier.padding(bottom = 4.dp),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }

                        version.branches.forEach { branch ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    branchLabel(branch),
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodySmall
                                )
                                BranchStatusBadge(branch)
                                branch.mapsUrl?.let { url ->
                                    val shelfMapDescription = stringResource(R.string.cd_shelf_map)
                                    Box(
                                        modifier =
                                            Modifier.size(32.dp)
                                                .clickable { openUrl(context, url) }
                                                .semantics {
                                                    contentDescription = shelfMapDescription
                                                },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("📍", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                val shareDescription = stringResource(R.string.cd_share_result)
                IconButton(
                    onClick = {
                        sendShareIntent(context, buildSearchResultShareText(context, result))
                    },
                    modifier = Modifier.semantics { contentDescription = shareDescription }
                ) {
                    Text("📤")
                }
                val webSearchDescription = stringResource(R.string.cd_search_web)
                IconButton(
                    onClick = { openWebSearch(context, result.title, result.author) },
                    modifier = Modifier.semantics { contentDescription = webSearchDescription }
                ) {
                    Text("🔍")
                }
            }
        }
    }
}

// Port omnis-py cli.py:339-340 — dopisek typu nośnika, gdy inny niż zwykła książka drukowana
// (np. "Audiobook"). Współdzielone przez widok karty i tekst do udostępnienia.
private fun editionLabel(version: BookVersion): String {
    val base = version.edition ?: "-"
    return if (
        version.resourceType != null && !version.resourceType.equals("book", ignoreCase = true)
    )
        "$base [${version.resourceType}]"
    else base
}

private fun branchLabel(branch: BranchAvailability): String =
    branch.subLocation?.let { "${branch.libraryName} – $it" } ?: branch.libraryName

// Wersja nie-@Composable (Context.getString zamiast stringResource) — potrzebna, by
// buildSearchResultShareText mogła być budowana leniwie w onClick, tak jak buildLoanShareText w
// LoanComponents.kt, zamiast liczyć się przy każdej rekompozycji dla każdego wyniku na liście
// (SearchTenantSectionView renderuje wszystkie karty naraz, bez LazyColumn per wynik).
private fun branchStatusText(context: Context, branch: BranchAvailability): String =
    when {
        branch.status == "available" -> context.getString(R.string.status_available)
        branch.status == "unavailable" && branch.dueDate != null -> {
            // `dueDate` jest `var` (patrz BranchAvailability) — Kotlin nie smart-castuje
            // mutowalnych właściwości, stąd jednorazowe `!!` po już wykonanym null-checku wyżej.
            val formattedDate = formatPlainDate(branch.dueDate!!)
            if (branch.overdue) context.getString(R.string.status_overdue_since, formattedDate)
            else context.getString(R.string.status_borrowed_until, formattedDate)
        }
        branch.status == "unavailable" -> context.getString(R.string.status_borrowed_unknown)
        else -> context.getString(R.string.status_unknown)
    }

@Composable
private fun branchStatusColor(branch: BranchAvailability): Color =
    when {
        branch.status == "available" -> Color(0xFF388E3C)
        branch.status == "unavailable" && branch.dueDate != null ->
            if (branch.overdue) Color(0xFFD32F2F) else Color(0xFFFBC02D)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

@Composable
private fun BranchStatusBadge(branch: BranchAvailability) {
    val context = LocalContext.current
    Text(
        branchStatusText(context, branch),
        color = branchStatusColor(branch),
        style = MaterialTheme.typography.bodySmall
    )
}

// Świadomie pomija version.description (opis bywa wielozdaniowy) — udostępniana wiadomość ma
// zostać zwięzła, tak samo jak przy wypożyczeniach; pełny opis czyta się w aplikacji.
private fun buildSearchResultShareText(context: Context, result: SearchResult): String {
    val versionBlocks =
        result.versions.map { version ->
            val lines = mutableListOf<String>()
            lines.add(
                context.getString(
                    R.string.share_search_version_header,
                    editionLabel(version),
                    version.publicationDate ?: "-"
                )
            )
            listOfNotNull(
                    version.language?.let { context.getString(R.string.language_label, it) },
                    version.physicalDescription
                )
                .takeIf { it.isNotEmpty() }
                ?.let { lines.add(it.joinToString(" • ")) }
            (version.genres + version.subjects)
                .distinct()
                .takeIf { it.isNotEmpty() }
                ?.let { lines.add(it.joinToString(", ")) }
            version.branches.forEach { branch ->
                val branchLine = " • ${branchLabel(branch)}: ${branchStatusText(context, branch)}"
                lines.add(branch.mapsUrl?.let { "$branchLine ($it)" } ?: branchLine)
            }
            lines.joinToString("\n")
        }

    val headerLines =
        mutableListOf(
            context.getString(
                R.string.share_search_result_header,
                result.title,
                result.author ?: context.getString(R.string.unknown_author)
            )
        )
    result.versions
        .firstNotNullOfOrNull { it.series }
        ?.let { headerLines.add(context.getString(R.string.series_label, it)) }
    return (headerLines + versionBlocks).joinToString("\n\n")
}
