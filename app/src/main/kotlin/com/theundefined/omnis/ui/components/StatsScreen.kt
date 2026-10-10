package com.theundefined.omnis.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theundefined.omnis.R
import com.theundefined.omnis.data.model.Account
import com.theundefined.omnis.ui.CountEntry
import com.theundefined.omnis.ui.LoanDurationStats
import com.theundefined.omnis.ui.OmnisViewModel
import com.theundefined.omnis.ui.ReadingStats
import com.theundefined.omnis.ui.computeReadingStats
import java.time.LocalDate
import java.time.Month
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

// Paleta kategoryczna (kolejność sprawdzona pod kątem daltonizmu dla sąsiednich par) — osobne
// stopnie dla jasnego i ciemnego motywu. Kolor przypisany do konta wg jego pozycji na liście
// wszystkich kont, więc nie zmienia się po odfiltrowaniu innych kont.
private val ACCOUNT_COLORS_LIGHT =
    listOf(
            0xFF2A78D6,
            0xFFEB6834,
            0xFF1BAF7A,
            0xFFEDA100,
            0xFFE87BA4,
            0xFF008300,
            0xFF4A3AA7,
            0xFFE34948
        )
        .map { Color(it) }
private val ACCOUNT_COLORS_DARK =
    listOf(
            0xFF3987E5,
            0xFFD95926,
            0xFF199E70,
            0xFFC98500,
            0xFFD55181,
            0xFF008300,
            0xFF9085E9,
            0xFFE66767
        )
        .map { Color(it) }

private val CHART_HEIGHT = 160.dp

private data class ChartBar(
    val label: String,
    val detailLabel: String,
    val perAccount: Map<String, Int>
) {
    val total: Int
        get() = perAccount.values.sum()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(viewModel: OmnisViewModel, onBack: () -> Unit) {
    val state by viewModel.statsUiState.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) { viewModel.loadStats() }
    LaunchedEffect(state.error) { state.error?.let { snackbarHostState.showSnackbar(it) } }

    val palette = if (isSystemInDarkTheme()) ACCOUNT_COLORS_DARK else ACCOUNT_COLORS_LIGHT
    val fallbackColor = MaterialTheme.colorScheme.outline
    val allAccounts = uiState.accounts
    val accounts = allAccounts.filter { it.isEnabled }
    val colorOf: (String) -> Color = { id ->
        palette.getOrNull(allAccounts.indexOfFirst { it.id == id }) ?: fallbackColor
    }
    val nameOf: (String) -> String = { id ->
        allAccounts.find { it.id == id }?.let { it.displayName ?: it.username } ?: id
    }

    val today = remember { LocalDate.now() }
    var selectedYear by rememberSaveable { mutableStateOf<Int?>(today.year) }
    // null = wszystkie konta
    var selectedAccounts by remember { mutableStateOf<Set<String>?>(null) }
    val stats =
        remember(state.loans, selectedAccounts, selectedYear) {
            computeReadingStats(state.loans, selectedAccounts, selectedYear, today)
        }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.stats_title)) },
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
                        onClick = { viewModel.loadStats(forceRefresh = true) },
                        enabled = !state.isLoading,
                        modifier = Modifier.semantics { contentDescription = refreshDescription }
                    ) {
                        Icon(painterResource(R.drawable.ic_refresh), contentDescription = null)
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            state.progress?.let { progress ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Text(
                        stringResource(
                            R.string.stats_loading_progress,
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
            when {
                !state.hasLoadedOnce && state.isLoading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                !state.hasLoadedOnce && state.error != null -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(state.error ?: "", style = MaterialTheme.typography.bodyMedium)
                            Spacer(modifier = Modifier.height(8.dp))
                            TextButton(onClick = { viewModel.loadStats() }) {
                                Text(stringResource(R.string.retry))
                            }
                        }
                    }
                }
                state.loans.isEmpty() && !state.isLoading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.stats_empty))
                    }
                }
                else ->
                    StatsContent(
                        stats = stats,
                        today = today,
                        accounts = accounts,
                        selectedAccounts = selectedAccounts,
                        onSelectedAccountsChange = { selectedAccounts = it },
                        selectedYear = selectedYear,
                        onSelectedYearChange = { selectedYear = it },
                        colorOf = colorOf,
                        nameOf = nameOf
                    )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatsContent(
    stats: ReadingStats,
    today: LocalDate,
    accounts: List<Account>,
    selectedAccounts: Set<String>?,
    onSelectedAccountsChange: (Set<String>?) -> Unit,
    selectedYear: Int?,
    onSelectedYearChange: (Int?) -> Unit,
    colorOf: (String) -> Color,
    nameOf: (String) -> String
) {
    val locale = LocalConfiguration.current.locales[0]
    // Konta w kolejności stosu słupków — tylko te uwzględnione w filtrze.
    val stackOrder =
        accounts.map { it.id }.filter { selectedAccounts == null || it in selectedAccounts }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                stringResource(R.string.stats_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (accounts.size > 1) {
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    accounts.forEach { account ->
                        val selected = selectedAccounts == null || account.id in selectedAccounts
                        FilterChip(
                            selected = selected,
                            onClick = {
                                val current = selectedAccounts ?: accounts.map { it.id }.toSet()
                                val next =
                                    if (selected) current - account.id else current + account.id
                                // Nie da się odznaczyć ostatniego konta; komplet = brak filtra.
                                if (next.isNotEmpty()) {
                                    onSelectedAccountsChange(
                                        next.takeIf { it.size < accounts.size }
                                    )
                                }
                            },
                            label = { Text(nameOf(account.id)) },
                            leadingIcon = { ColorDot(colorOf(account.id)) }
                        )
                    }
                }
            }
        }

        item {
            val years = (stats.availableYears + today.year).distinct().sortedDescending()
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = selectedYear == null,
                    onClick = { onSelectedYearChange(null) },
                    label = { Text(stringResource(R.string.stats_all_years)) }
                )
                years.forEach { year ->
                    FilterChip(
                        selected = selectedYear == year,
                        onClick = { onSelectedYearChange(year) },
                        label = { Text(year.toString()) }
                    )
                }
            }
        }

        item { SummaryTiles(stats, selectedYear, today, locale) }

        item {
            val bars =
                if (selectedYear != null) {
                    stats.months.map { bucket ->
                        val month = Month.of(bucket.month)
                        ChartBar(
                            label = month.getDisplayName(TextStyle.SHORT_STANDALONE, locale),
                            detailLabel =
                                "${month.getDisplayName(TextStyle.FULL_STANDALONE, locale)} " +
                                    selectedYear,
                            perAccount = bucket.perAccount
                        )
                    }
                } else {
                    val compact = stats.years.size > 6
                    stats.years.map { bucket ->
                        ChartBar(
                            label =
                                if (compact) "'" + (bucket.year % 100).toString().padStart(2, '0')
                                else bucket.year.toString(),
                            detailLabel = bucket.year.toString(),
                            perAccount = bucket.perAccount
                        )
                    }
                }
            StatsCard(
                title =
                    if (selectedYear != null)
                        stringResource(R.string.stats_chart_months, selectedYear)
                    else stringResource(R.string.stats_chart_years)
            ) {
                StackedBarChart(bars, stackOrder, colorOf, nameOf)
            }
        }

        if (stats.years.isNotEmpty()) {
            item {
                StatsCard(title = stringResource(R.string.stats_years_table)) {
                    YearsTable(stats, stackOrder, nameOf)
                }
            }
        }

        if (stats.topAuthors.isNotEmpty()) {
            item {
                StatsCard(title = stringResource(R.string.stats_top_authors)) {
                    RankedList(stats.topAuthors)
                }
            }
        }

        if (stats.categories.isNotEmpty()) {
            item {
                StatsCard(title = stringResource(R.string.stats_categories)) {
                    RankedList(stats.categories)
                }
            }
        }

        if (stats.libraries.isNotEmpty()) {
            item {
                StatsCard(title = stringResource(R.string.stats_libraries)) {
                    RankedList(stats.libraries)
                }
            }
        }

        stats.duration?.let { duration ->
            item {
                StatsCard(title = stringResource(R.string.stats_duration)) {
                    DurationSummary(duration, locale)
                }
            }
        }
    }
}

@Composable
private fun SummaryTiles(
    stats: ReadingStats,
    selectedYear: Int?,
    today: LocalDate,
    locale: Locale
) {
    val first: Pair<String, String?> =
        if (selectedYear != null) {
            val delta =
                stats.previousYearTotal
                    ?.takeIf { it > 0 || stats.scopeTotal > 0 }
                    ?.let { previous ->
                        stringResource(
                            R.string.stats_vs_previous,
                            formatDelta(stats.scopeTotal - previous),
                            selectedYear - 1
                        )
                    }
            stringResource(R.string.stats_tile_year, selectedYear) to delta
        } else {
            stringResource(R.string.stats_tile_total) to null
        }
    val second =
        if (selectedYear != null) stringResource(R.string.stats_tile_all_time) to stats.allTimeTotal
        else stringResource(R.string.stats_tile_this_year) to stats.thisYearTotal

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile(first.first, stats.scopeTotal.toString(), first.second, Modifier.weight(1f))
            StatTile(second.first, second.second.toString(), null, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile(
                stringResource(R.string.stats_tile_average),
                String.format(locale, "%.1f", stats.averagePerMonth),
                null,
                Modifier.weight(1f)
            )
            StatTile(
                stringResource(R.string.stats_tile_best_month),
                stats.bestMonth?.second?.toString() ?: "—",
                stats.bestMonth?.first?.let { formatYearMonth(it, locale) },
                Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, caption: String?, modifier: Modifier) {
    Card(modifier = modifier) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(value, style = MaterialTheme.typography.headlineMedium)
            Text(
                caption ?: "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun StatsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StackedBarChart(
    bars: List<ChartBar>,
    stackOrder: List<String>,
    colorOf: (String) -> Color,
    nameOf: (String) -> String
) {
    var selected by remember(bars) { mutableStateOf<Int?>(null) }
    val max = bars.maxOfOrNull { it.total }?.takeIf { it > 0 } ?: 1
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant

    Row(Modifier.fillMaxWidth()) {
        bars.forEachIndexed { index, bar ->
            val dimmed = selected != null && selected != index
            Column(
                modifier =
                    Modifier.weight(1f)
                        .clickable { selected = if (selected == index) null else index }
                        .alpha(if (dimmed) 0.4f else 1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    if (bar.total > 0) bar.total.toString() else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = secondary,
                    maxLines = 1
                )
                Box(
                    Modifier.fillMaxWidth().height(CHART_HEIGHT),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    BarStack(bar, stackOrder, max, colorOf)
                }
                HorizontalDivider()
                Text(
                    bar.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = secondary,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }

    Spacer(Modifier.height(8.dp))
    val detail = selected?.let { bars.getOrNull(it) }
    if (detail != null) {
        val breakdown =
            stackOrder
                .mapNotNull { id -> detail.perAccount[id]?.takeIf { it > 0 }?.let { id to it } }
                .takeIf { stackOrder.size > 1 && it.isNotEmpty() }
                ?.joinToString(" · ") { (id, count) -> "${nameOf(id)} $count" }
        Text(
            "${detail.detailLabel}: ${detail.total}" + (breakdown?.let { " — $it" } ?: ""),
            style = MaterialTheme.typography.bodyMedium
        )
    } else {
        Text(
            stringResource(R.string.stats_chart_hint),
            style = MaterialTheme.typography.bodySmall,
            color = secondary
        )
    }

    if (stackOrder.size > 1) {
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            stackOrder.forEach { id ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ColorDot(colorOf(id))
                    Spacer(Modifier.width(4.dp))
                    Text(nameOf(id), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/** Słupek z segmentów kont: pierwsze konto na dole, 2 dp przerwy między segmentami. */
@Composable
private fun BarStack(
    bar: ChartBar,
    stackOrder: List<String>,
    max: Int,
    colorOf: (String) -> Color
) {
    val segments =
        stackOrder.mapNotNull { id -> bar.perAccount[id]?.takeIf { it > 0 }?.let { id to it } }
    if (segments.isEmpty()) return
    val gap = 2.dp
    val totalHeight: Dp = CHART_HEIGHT * (bar.total.toFloat() / max)
    val available = (totalHeight - gap * (segments.size - 1)).coerceAtLeast(1.dp * segments.size)
    Column(
        modifier = Modifier.fillMaxWidth(0.7f),
        verticalArrangement = Arrangement.spacedBy(gap)
    ) {
        segments.asReversed().forEachIndexed { index, (id, count) ->
            val shape =
                if (index == 0) RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)
                else RoundedCornerShape(0.dp)
            Box(
                Modifier.fillMaxWidth()
                    .height((available * (count.toFloat() / bar.total)).coerceAtLeast(1.dp))
                    .background(colorOf(id), shape)
            )
        }
    }
}

@Composable
private fun YearsTable(stats: ReadingStats, stackOrder: List<String>, nameOf: (String) -> String) {
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.stats_col_year),
            style = MaterialTheme.typography.labelMedium,
            color = secondary,
            modifier = Modifier.weight(1f)
        )
        Text(
            stringResource(R.string.stats_col_total),
            style = MaterialTheme.typography.labelMedium,
            color = secondary,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f)
        )
        Text(
            stringResource(R.string.stats_col_change),
            style = MaterialTheme.typography.labelMedium,
            color = secondary,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f)
        )
    }
    val byYear = stats.years.associateBy { it.year }
    stats.years.asReversed().forEach { bucket ->
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        Row(Modifier.fillMaxWidth()) {
            Text(bucket.year.toString(), Modifier.weight(1f))
            Text(bucket.total.toString(), Modifier.weight(1f), textAlign = TextAlign.End)
            Text(
                if (bucket.year == stats.years.first().year) "—"
                else formatDelta(bucket.total - (byYear[bucket.year - 1]?.total ?: 0)),
                Modifier.weight(1f),
                textAlign = TextAlign.End,
                color = secondary
            )
        }
        if (stackOrder.size > 1) {
            Text(
                stackOrder
                    .filter { (bucket.perAccount[it] ?: 0) > 0 }
                    .joinToString(" · ") { "${nameOf(it)} ${bucket.perAccount[it]}" },
                style = MaterialTheme.typography.bodySmall,
                color = secondary
            )
        }
    }
}

@Composable
private fun RankedList(entries: List<CountEntry>) {
    val max = entries.maxOfOrNull { it.count }?.takeIf { it > 0 } ?: 1
    val unknown = stringResource(R.string.stats_unknown_category)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        entries.forEach { entry ->
            Column {
                Row(Modifier.fillMaxWidth()) {
                    Text(
                        entry.label ?: unknown,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(entry.count.toString(), style = MaterialTheme.typography.bodyMedium)
                }
                Box(
                    Modifier.fillMaxWidth(entry.count.toFloat() / max)
                        .padding(top = 2.dp)
                        .height(6.dp)
                        .background(
                            MaterialTheme.colorScheme.primary,
                            RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp)
                        )
                )
            }
        }
    }
}

@Composable
private fun DurationSummary(duration: LoanDurationStats, locale: Locale) {
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.stats_duration_average),
                style = MaterialTheme.typography.labelMedium,
                color = secondary
            )
            Text(
                stringResource(
                    R.string.stats_days_decimal,
                    String.format(locale, "%.1f", duration.averageDays)
                ),
                style = MaterialTheme.typography.titleMedium
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.stats_duration_median),
                style = MaterialTheme.typography.labelMedium,
                color = secondary
            )
            Text(
                if (duration.medianDays % 1.0 == 0.0)
                    pluralStringResource(
                        R.plurals.stats_days,
                        duration.medianDays.toInt(),
                        duration.medianDays.toInt()
                    )
                else
                    stringResource(
                        R.string.stats_days_decimal,
                        String.format(locale, "%.1f", duration.medianDays)
                    ),
                style = MaterialTheme.typography.titleMedium
            )
        }
    }
    Spacer(Modifier.height(8.dp))
    Text(
        stringResource(R.string.stats_duration_longest),
        style = MaterialTheme.typography.labelMedium,
        color = secondary
    )
    Text(
        pluralStringResource(
            R.plurals.stats_days,
            duration.longestDays.toInt(),
            duration.longestDays.toInt()
        ) + " — " + duration.longestTitle,
        style = MaterialTheme.typography.bodyMedium
    )
    Spacer(Modifier.height(4.dp))
    Text(
        pluralStringResource(
            R.plurals.stats_duration_basis,
            duration.sampleSize,
            duration.sampleSize
        ),
        style = MaterialTheme.typography.bodySmall,
        color = secondary
    )
}

@Composable
private fun ColorDot(color: Color) {
    Box(Modifier.size(10.dp).background(color, CircleShape))
}

private fun formatDelta(delta: Int): String =
    when {
        delta > 0 -> "+$delta"
        delta < 0 -> "−${-delta}"
        else -> "0"
    }

private fun formatYearMonth(yearMonth: YearMonth, locale: Locale): String =
    "${yearMonth.month.getDisplayName(TextStyle.FULL_STANDALONE, locale)} ${yearMonth.year}"
