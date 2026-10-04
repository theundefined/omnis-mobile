package com.theundefined.omnis.ui.components

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theundefined.omnis.R
import com.theundefined.omnis.data.model.BookVersion
import com.theundefined.omnis.data.model.BranchAvailability
import com.theundefined.omnis.data.model.MEDIA_TYPE_BOOK
import com.theundefined.omnis.data.model.SearchField
import com.theundefined.omnis.data.model.SearchHistoryEntry
import com.theundefined.omnis.data.model.SearchResult
import com.theundefined.omnis.data.model.Tenant
import com.theundefined.omnis.data.model.searchKey
import com.theundefined.omnis.data.model.seriesSearchTerm
import com.theundefined.omnis.ui.OmnisViewModel
import com.theundefined.omnis.ui.SearchLibrariesState
import com.theundefined.omnis.ui.SearchSortMode
import com.theundefined.omnis.ui.SearchTenantSection
import com.theundefined.omnis.ui.availableMediaTypes
import com.theundefined.omnis.ui.branchChipLabel
import com.theundefined.omnis.ui.checkboxBranches
import com.theundefined.omnis.ui.effectiveMediaTypes
import com.theundefined.omnis.ui.filteredResults
import com.theundefined.omnis.ui.hasMappableBranches
import java.text.Collator
import java.util.Locale
import kotlinx.coroutines.launch

private val polishCollator: Collator =
    Collator.getInstance(Locale("pl")).apply { strength = Collator.PRIMARY }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    viewModel: OmnisViewModel,
    onBack: () -> Unit,
    autoStartScan: Boolean = false,
    onAutoStartScanConsumed: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val state by viewModel.searchUiState.collectAsStateWithLifecycle()
    val history by viewModel.searchHistory.collectAsStateWithLifecycle()
    val libraries by viewModel.searchLibraries.collectAsStateWithLifecycle()
    var showLibraryPicker by rememberSaveable { mutableStateOf(false) }
    var queryInput by remember { mutableStateOf(state.query) }
    // Pole wyszukiwania dla NASTĘPNEGO wyszukania. Kliknięcie autora/serii ustawia AUTHOR/SERIES;
    // ręczna edycja tekstu albo zamknięcie chipa pola wraca do ANY (szukanie we wszystkich polach).
    var searchField by remember { mutableStateOf(state.field) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val noLibraries = libraries.selectedKeys.isEmpty()

    fun triggerSearch() {
        viewModel.runSearch(queryInput, searchField)
    }

    fun searchFor(query: String, field: SearchField) {
        queryInput = query
        searchField = field
        viewModel.runSearch(query, field)
    }

    val startScan =
        rememberIsbnScanner(
            onIsbn = { isbn -> searchFor(isbn, SearchField.ANY) },
            onError = { error ->
                val message =
                    when (error) {
                        IsbnScanError.NotIsbn -> R.string.scan_not_isbn
                        IsbnScanError.Unavailable -> R.string.scan_unavailable
                    }
                scope.launch { snackbarHostState.showSnackbar(context.getString(message)) }
            }
        )

    // Skan ze skrótu aplikacji. Bez wybranej biblioteki nie ma w czym szukać — zostaje zwykły
    // ekran z prośbą o wybór zamiast skanowania w ślepą uliczkę.
    LaunchedEffect(autoStartScan) {
        if (autoStartScan) {
            onAutoStartScanConsumed()
            if (!noLibraries) startScan()
        }
    }

    if (showLibraryPicker) {
        SearchLibraryPicker(
            libraries = libraries,
            accountTenantKeys = uiState.accounts.map { it.tenant.searchKey() }.toSet(),
            onToggle = { tenant, selected -> viewModel.setSearchLibrarySelected(tenant, selected) },
            onDismiss = { showLibraryPicker = false }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
                onValueChange = {
                    queryInput = it
                    searchField = SearchField.ANY
                },
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                placeholder = { Text(stringResource(R.string.search_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { triggerSearch() }),
                trailingIcon = {
                    Row {
                        if (queryInput.isNotEmpty() || state.hasSearched) {
                            val clearDescription = stringResource(R.string.cd_clear_search)
                            IconButton(
                                onClick = {
                                    queryInput = ""
                                    searchField = SearchField.ANY
                                    viewModel.clearSearch()
                                },
                                modifier =
                                    Modifier.semantics { contentDescription = clearDescription }
                            ) {
                                Text("✕")
                            }
                        }
                        IconButton(onClick = startScan, enabled = !noLibraries) {
                            Icon(
                                painterResource(R.drawable.ic_barcode_scan),
                                stringResource(R.string.cd_scan_isbn)
                            )
                        }
                        val searchDescription = stringResource(R.string.cd_search)
                        IconButton(
                            onClick = { triggerSearch() },
                            modifier = Modifier.semantics { contentDescription = searchDescription }
                        ) {
                            Icon(painterResource(R.drawable.ic_search), contentDescription = null)
                        }
                    }
                }
            )

            FlowRow(
                modifier = Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AssistChip(
                    onClick = { showLibraryPicker = true },
                    label = { Text(librariesSummary(libraries)) },
                    leadingIcon = { Text("📚") },
                    trailingIcon = { Text("▾") }
                )
                val fieldLabel =
                    when (searchField) {
                        SearchField.ANY -> null
                        SearchField.AUTHOR -> R.string.search_field_author
                        SearchField.SERIES -> R.string.search_field_series
                    }
                if (fieldLabel != null) {
                    InputChip(
                        selected = true,
                        onClick = { searchFor(queryInput, SearchField.ANY) },
                        label = { Text(stringResource(fieldLabel)) },
                        trailingIcon = { Text("✕") }
                    )
                }
            }

            val mediaTypes = state.effectiveMediaTypes()
            val availableMediaTypes = state.availableMediaTypes()
            // Chipy typów dopiero, gdy jest z czego wybierać — przy samych książkach to szum.
            if (!noLibraries && state.hasSearched && availableMediaTypes.size > 1) {
                MediaTypeFilter(
                    available = availableMediaTypes,
                    selected = mediaTypes,
                    onToggle = { type, selected -> viewModel.setMediaTypeSelected(type, selected) },
                    onClear = { viewModel.clearMediaTypeSelection() }
                )
            }

            val hasAnyRawResults = state.tenantSections.any { it.results.isNotEmpty() }
            val errorSections = state.tenantSections.filter { it.error != null }
            val allFilteredEmpty =
                state.tenantSections.isNotEmpty() &&
                    state.tenantSections.all {
                        it.filteredResults(mediaTypes).isEmpty() && !it.isLoading
                    }

            when {
                noLibraries -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                stringResource(R.string.search_no_libraries),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            TextButton(onClick = { showLibraryPicker = true }) {
                                Text(stringResource(R.string.search_libraries_choose))
                            }
                        }
                    }
                }
                !state.hasSearched && history.isNotEmpty() -> {
                    SearchHistoryList(
                        history = history,
                        onSelect = { searchFor(it.query, it.field) },
                        onRemove = { viewModel.removeSearchHistoryEntry(it) },
                        onClearAll = { viewModel.clearSearchHistory() }
                    )
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
                                    stringResource(
                                        if (mediaTypes.isNotEmpty())
                                            R.string.no_search_results_filtered_media
                                        else R.string.no_search_results_filtered
                                    ),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                if (mediaTypes.isNotEmpty()) {
                                    TextButton(onClick = { viewModel.clearMediaTypeSelection() }) {
                                        Text(stringResource(R.string.search_all_media))
                                    }
                                }
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
                    BranchMapDialogHost(viewModel)
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        state.tenantSections.forEach { section ->
                            item(key = section.tenantKey) {
                                SearchTenantSectionView(
                                    section = section,
                                    mediaTypes = mediaTypes,
                                    viewModel = viewModel,
                                    onAuthorClick = { searchFor(it, SearchField.AUTHOR) },
                                    onSeriesClick = { searchFor(it, SearchField.SERIES) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun librariesSummary(libraries: SearchLibrariesState): String {
    val names = libraries.selected.map { it.name }.sortedWith(compareBy(polishCollator) { it })
    return when (names.size) {
        0 -> stringResource(R.string.search_libraries_choose)
        1 -> names.first()
        else -> stringResource(R.string.search_libraries_summary, names.first(), names.size - 1)
    }
}

/**
 * Wybór bibliotek w dolnym arkuszu (a nie rozwijanej liście na ekranie) — lista ma kilkadziesiąt
 * pozycji. Kolejność ustalana raz przy otwarciu (wybrane na górze), żeby wiersze nie skakały pod
 * palcem przy zaznaczaniu.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchLibraryPicker(
    libraries: SearchLibrariesState,
    accountTenantKeys: Set<String>,
    onToggle: (Tenant, Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var filter by remember { mutableStateOf("") }
    val order = remember {
        libraries.available.sortedWith(
            compareBy<Tenant> { it.searchKey() !in libraries.selectedKeys }
                .thenBy(polishCollator) { it.name }
        )
    }
    val visible =
        order.filter { filter.isBlank() || it.name.contains(filter.trim(), ignoreCase = true) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.search_libraries_title),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium
                )
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.search_libraries_done))
                }
            }
            OutlinedTextField(
                value = filter,
                onValueChange = { filter = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                placeholder = { Text(stringResource(R.string.search_libraries_filter)) },
                singleLine = true
            )
            LazyColumn(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                items(visible, key = { it.searchKey() }) { tenant ->
                    val selected = tenant.searchKey() in libraries.selectedKeys
                    Row(
                        modifier =
                            Modifier.fillMaxWidth()
                                .clickable { onToggle(tenant, !selected) }
                                .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = selected, onCheckedChange = { onToggle(tenant, it) })
                        Column(modifier = Modifier.weight(1f)) {
                            Text(tenant.name, style = MaterialTheme.typography.bodyLarge)
                            if (tenant.searchKey() in accountTenantKeys) {
                                Text(
                                    stringResource(R.string.search_library_has_account),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Filtr typu nośnika — wspólny dla wszystkich bibliotek; brak zaznaczenia = wszystkie typy. */
@Composable
private fun MediaTypeFilter(
    available: List<String>,
    selected: Set<String>,
    onToggle: (String, Boolean) -> Unit,
    onClear: () -> Unit
) {
    val context = LocalContext.current
    FlowRow(
        modifier = Modifier.padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilterChip(
            selected = selected.isEmpty(),
            onClick = onClear,
            label = { Text(stringResource(R.string.search_all_media)) }
        )
        // Książka zawsze pierwsza, reszta w kolejności z wyników.
        available.sortedBy { it != MEDIA_TYPE_BOOK }.forEach { type ->
            FilterChip(
                selected = type in selected,
                onClick = { onToggle(type, type !in selected) },
                label = { Text(mediaTypeLabel(context, type)) }
            )
        }
    }
}

// Nie-@Composable (Context.getString) — używane też przez editionLabel w tekście do udostępnienia.
internal fun mediaTypeLabel(context: Context, type: String): String {
    val res =
        when (type) {
            MEDIA_TYPE_BOOK -> R.string.media_type_book
            "audiobook" -> R.string.media_type_audiobook
            "video" -> R.string.media_type_video
            "audio" -> R.string.media_type_audio
            "music" -> R.string.media_type_music
            "journal" -> R.string.media_type_journal
            "map" -> R.string.media_type_map
            "score" -> R.string.media_type_score
            // Nieznany typ z Primo — surowa nazwa.
            else -> return type.replaceFirstChar { it.titlecase(Locale.getDefault()) }
        }
    return context.getString(res)
}

@Composable
private fun SearchHistoryList(
    history: List<SearchHistoryEntry>,
    onSelect: (SearchHistoryEntry) -> Unit,
    onRemove: (SearchHistoryEntry) -> Unit,
    onClearAll: () -> Unit
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.search_history_title),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.secondary
                )
                TextButton(onClick = onClearAll) {
                    Text(stringResource(R.string.search_history_clear))
                }
            }
        }
        items(history, key = { "${it.field}:${it.query}" }) { entry ->
            Row(
                modifier =
                    Modifier.fillMaxWidth()
                        .clickable { onSelect(entry) }
                        .padding(start = 16.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("🕘", modifier = Modifier.padding(end = 12.dp))
                Text(
                    when (entry.field) {
                        SearchField.ANY -> entry.query
                        SearchField.AUTHOR ->
                            stringResource(R.string.search_history_author_entry, entry.query)
                        SearchField.SERIES ->
                            stringResource(R.string.search_history_series_entry, entry.query)
                    },
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge
                )
                val removeDescription = stringResource(R.string.cd_remove_search_history_entry)
                IconButton(
                    onClick = { onRemove(entry) },
                    modifier = Modifier.semantics { contentDescription = removeDescription }
                ) {
                    Text("✕", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun SearchTenantSectionView(
    section: SearchTenantSection,
    mediaTypes: Set<String>,
    viewModel: OmnisViewModel,
    onAuthorClick: (String) -> Unit,
    onSeriesClick: (String) -> Unit
) {
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

        section.filteredResults(mediaTypes).forEach { result ->
            SearchResultCard(
                result,
                onShowMap = { viewModel.showBranchMap(result, section.tenantLabel) },
                onAuthorClick = onAuthorClick,
                onSeriesClick = onSeriesClick
            )
        }

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
        FilterChip(
            selected = section.sortMode == SearchSortMode.SERIES,
            onClick = { viewModel.setSearchSortMode(section.tenantKey, SearchSortMode.SERIES) },
            label = { Text(stringResource(R.string.search_sort_series)) }
        )
    }
}

@Composable
private fun SearchResultCard(
    result: SearchResult,
    onShowMap: () -> Unit,
    onAuthorClick: (String) -> Unit,
    onSeriesClick: (String) -> Unit
) {
    val context = LocalContext.current

    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(result.title, style = MaterialTheme.typography.titleMedium)
            result.author?.let { author ->
                val authorClickLabel = stringResource(R.string.cd_search_by_author)
                Text(
                    author,
                    modifier =
                        Modifier.clickable(onClickLabel = authorClickLabel) {
                            onAuthorClick(author)
                        },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            result.versions
                .firstNotNullOfOrNull { it.series }
                ?.let { series ->
                    val seriesClickLabel = stringResource(R.string.cd_search_by_series)
                    Text(
                        stringResource(R.string.series_label, series),
                        modifier =
                            Modifier.clickable(onClickLabel = seriesClickLabel) {
                                onSeriesClick(seriesSearchTerm(series))
                            },
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
                                editionLabel(LocalContext.current, version),
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
                                        Icon(
                                            painterResource(R.drawable.ic_place),
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (hasMappableBranches(result)) {
                    val mapDescription = stringResource(R.string.cd_show_on_map)
                    IconButton(
                        onClick = onShowMap,
                        modifier = Modifier.semantics { contentDescription = mapDescription }
                    ) {
                        Icon(painterResource(R.drawable.ic_map), contentDescription = null)
                    }
                }
                ShareButton { buildSearchResultShareText(context, result) }
                val webSearchDescription = stringResource(R.string.cd_search_web)
                IconButton(
                    onClick = { openWebSearch(context, result.title, result.author) },
                    modifier = Modifier.semantics { contentDescription = webSearchDescription }
                ) {
                    Icon(painterResource(R.drawable.ic_public), contentDescription = null)
                }
            }
        }
    }
}

// Port omnis-py cli.py:339-340 — dopisek typu nośnika, gdy inny niż zwykła książka drukowana
// (np. "Audiobook"). Współdzielone przez widok karty i tekst do udostępnienia.
internal fun editionLabel(context: Context, version: BookVersion): String {
    val base = version.edition ?: "-"
    return if (!version.isPrintBook) "$base [${mediaTypeLabel(context, version.mediaType)}]"
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
            val formattedDate = formatPlainDate(branch.dueDate)
            if (branch.overdue) context.getString(R.string.status_overdue_since, formattedDate)
            else context.getString(R.string.status_borrowed_until, formattedDate)
        }
        branch.status == "unavailable" && branch.dueDatePending ->
            context.getString(R.string.status_borrowed_pending)
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
internal fun BranchStatusBadge(branch: BranchAvailability) {
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
                    editionLabel(context, version),
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
