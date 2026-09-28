package com.theundefined.omnis.ui.components

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theundefined.omnis.R
import com.theundefined.omnis.data.model.Loan
import com.theundefined.omnis.data.model.SearchField
import com.theundefined.omnis.data.model.authorSearchTerm
import com.theundefined.omnis.data.model.branchMapsQuery
import com.theundefined.omnis.data.model.displayTitle
import com.theundefined.omnis.data.model.isMapsLink
import com.theundefined.omnis.data.model.isRegularLoanStatus
import com.theundefined.omnis.data.model.mapsSearchUrl
import com.theundefined.omnis.data.model.seriesSearchTerm
import com.theundefined.omnis.ui.BranchDialogState
import com.theundefined.omnis.ui.OmnisViewModel
import com.theundefined.omnis.ui.branchLabel
import com.theundefined.omnis.ui.spansSeveralLibraries
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

@Composable
fun LoanList(
    groupedLoans: Map<String, List<Loan>>,
    onRenew: (Loan) -> Unit = {},
    onRenewAll: (List<Loan>) -> Unit = {},
    isHistory: Boolean = false,
    onBranchClick: ((List<Loan>) -> Unit)? = null,
    // true przy grupowaniu po filii — tylko wtedy nagłówek grupy jest nazwą filii.
    branchHeaders: Boolean = false,
    // Kliknięcie autora/serii — przejście do wyszukiwarki (OmnisViewModel.searchFromLoan).
    onSearch: ((Loan, String, SearchField) -> Unit)? = null,
    footer: (@Composable () -> Unit)? = null
) {
    var renewConfirmGroup by remember { mutableStateOf<Pair<String, List<Loan>>?>(null) }
    // Liczone po wszystkich grupach, nie per grupa — przy grupowaniu po koncie każda grupa ma
    // zwykle jedną bibliotekę, a nazwa i tak powinna się pojawić, skoro na liście jest ich kilka.
    val withLibrary =
        remember(groupedLoans) { spansSeveralLibraries(groupedLoans.values.flatten()) }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        groupedLoans.forEach { (groupKey, accountLoans) ->
            item {
                val renewableLoans = accountLoans.filter { it.renewable }
                // Nagłówek jest klikalny tylko przy grupowaniu po filii (przy grupowaniu po koncie
                // nagłówkiem jest osoba, nawet jeśli wszystko ma z jednej filii) i tylko, gdy grupa
                // to faktycznie jedna filia jednej biblioteki.
                val onHeaderClick =
                    onBranchClick?.takeIf {
                        branchHeaders &&
                            accountLoans.isNotEmpty() &&
                            accountLoans.distinctBy { it.tenantName to it.locationName }.size == 1
                    }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = groupKey,
                        modifier =
                            Modifier.weight(1f, fill = false)
                                .then(
                                    if (onHeaderClick != null)
                                        Modifier.clickable { onHeaderClick(accountLoans) }
                                    else Modifier
                                )
                                .padding(vertical = 16.dp),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    if (accountLoans.isNotEmpty()) {
                        val groupShareText = buildGroupShareText(groupKey, accountLoans, isHistory)
                        Row {
                            if (onHeaderClick != null) {
                                BranchButton { onHeaderClick(accountLoans) }
                            }
                            ShareButton { groupShareText }
                            if (!isHistory && renewableLoans.isNotEmpty()) {
                                IconButton(
                                    onClick = { renewConfirmGroup = groupKey to renewableLoans }
                                ) {
                                    Icon(
                                        painterResource(R.drawable.ic_autorenew),
                                        stringResource(R.string.cd_renew_all)
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (accountLoans.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.no_loans),
                        modifier = Modifier.padding(horizontal = 16.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            } else {
                // Klucz łączy accountId z loanid — samo loanid jest nadawane per instytucja,
                // więc przy wielu kontach/tenantach mogłoby się powtórzyć w obrębie jednej
                // LazyColumn (Compose wymaga unikalności kluczy globalnie, nie per grupa).
                items(accountLoans, key = { "${it.accountId}:${it.id}" }) { loan ->
                    LoanItem(
                        loan,
                        onRenew = { onRenew(loan) },
                        isHistory = isHistory,
                        withLibrary = withLibrary,
                        onBranchClick = onBranchClick?.let { { it(listOf(loan)) } },
                        onSearch = onSearch?.let { { query, field -> it(loan, query, field) } }
                    )
                }
            }
            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
        if (footer != null) {
            item { footer() }
        }
    }

    renewConfirmGroup?.let { (groupKey, renewableLoans) ->
        AlertDialog(
            onDismissRequest = { renewConfirmGroup = null },
            title = { Text(stringResource(R.string.renew_all_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.renew_all_confirm_message,
                        renewableLoans.size,
                        groupKey
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRenewAll(renewableLoans)
                        renewConfirmGroup = null
                    }
                ) {
                    Text(stringResource(R.string.renew))
                }
            },
            dismissButton = {
                TextButton(onClick = { renewConfirmGroup = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

private fun buildLoanShareText(
    context: Context,
    loan: Loan,
    formattedDueDate: String,
    withLibrary: Boolean
): String =
    context.getString(
        R.string.share_book,
        displayTitle(loan.title),
        loan.author ?: context.getString(R.string.unknown_author),
        formattedDueDate,
        branchLabel(loan, withLibrary),
        loan.barcode
    )

@Composable
private fun buildGroupShareText(groupKey: String, loans: List<Loan>, isHistory: Boolean): String {
    val context = LocalContext.current
    // `joinToString { ... }` nie jest inline w stdlibie, więc Compose nie pozwala wywoływać z jego
    // lambdy funkcji @Composable (formatRelativeDate) — zwykła pętla `for` działa, bo to
    // inline'owana kontrola przepływu, a nie osobna lambda.
    // Przy grupowaniu po filii albo bez grupowania nagłówek nie mówi, czyje to wypożyczenia —
    // wtedy (gdy w grupie jest więcej niż jedna osoba) dopisujemy właściciela do każdej pozycji.
    val showOwner = loans.mapNotNull { it.ownerName }.distinct().size > 1
    val withLibrary = spansSeveralLibraries(loans)
    val loanTexts = mutableListOf<String>()
    for (loan in loans) {
        val formattedDueDate =
            if (isHistory) formatPlainDate(loan.dueDate) else formatRelativeDate(loan.dueDate)
        val text = buildLoanShareText(context, loan, formattedDueDate, withLibrary)
        val owner = loan.ownerName
        loanTexts.add(
            if (showOwner && owner != null)
                text + "\n👤 " + context.getString(R.string.loaned_by, owner)
            else text
        )
    }
    return context.getString(R.string.share_loans_group_header, groupKey, loans.size) +
        "\n\n" +
        loanTexts.joinToString(separator = "\n\n")
}

@Composable
fun getDueDateColor(dueDateStr: String): Color {
    val date = parseFlexibleDate(dueDateStr) ?: return MaterialTheme.colorScheme.onSurface
    val today = LocalDate.now()
    val daysUntil = ChronoUnit.DAYS.between(today, date)

    return when {
        daysUntil <= 0 -> Color(0xFFD32F2F) // Bold Red
        daysUntil <= 7 -> Color(0xFFFBC02D) // Bold Yellow
        else -> Color(0xFF388E3C) // Green
    }
}

fun parseFlexibleDate(dateStr: String): LocalDate? {
    val formatters =
        listOf(
            DateTimeFormatter.ofPattern("dd/MM/yyyy"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd"),
            DateTimeFormatter.ofPattern("yyyyMMdd")
        )
    for (formatter in formatters) {
        try {
            return LocalDate.parse(dateStr, formatter)
        } catch (e: Exception) {
            continue
        }
    }
    return null
}

// Polski ma kilka form liczby mnogiej ("1 dzień", "2 dni", "5 dni") — stąd plurals, nie string.
@Composable
private fun daysPlural(id: Int, days: Long): String =
    LocalContext.current.resources.getQuantityString(id, days.toInt(), days.toInt())

@Composable
fun formatRelativeDate(dateStr: String): String {
    val date = parseFlexibleDate(dateStr) ?: return dateStr
    val today = LocalDate.now()
    val daysUntil = ChronoUnit.DAYS.between(today, date)

    val relative =
        when {
            daysUntil < 0 -> daysPlural(R.plurals.days_overdue, -daysUntil)
            daysUntil == 0L -> stringResource(R.string.today)
            daysUntil == 1L -> stringResource(R.string.tomorrow)
            else -> daysPlural(R.plurals.in_days, daysUntil)
        }

    return "${date.format(DateTimeFormatter.ofPattern("yyyy.MM.dd"))} ($relative)"
}

/** Jak formatRelativeDate, ale dla dat z przeszłości (data wypożyczenia): "(12 dni temu)". */
@Composable
fun formatPastRelativeDate(dateStr: String): String {
    val date = parseFlexibleDate(dateStr) ?: return dateStr
    val daysAgo = ChronoUnit.DAYS.between(date, LocalDate.now())

    val relative =
        when {
            daysAgo <= 0L -> stringResource(R.string.today)
            daysAgo == 1L -> stringResource(R.string.yesterday)
            else -> daysPlural(R.plurals.days_ago, daysAgo)
        }

    return "${date.format(DateTimeFormatter.ofPattern("yyyy.MM.dd"))} ($relative)"
}

fun formatPlainDate(dateStr: String): String {
    val date = parseFlexibleDate(dateStr) ?: return dateStr
    return date.format(DateTimeFormatter.ofPattern("yyyy.MM.dd"))
}

@Composable
fun LoanItem(
    loan: Loan,
    onRenew: () -> Unit,
    isHistory: Boolean = false,
    withLibrary: Boolean = false,
    onBranchClick: (() -> Unit)? = null,
    onSearch: ((String, SearchField) -> Unit)? = null
) {
    val context = LocalContext.current
    // Dla historii wypożyczenie jest już zakończone — kolorowanie "ile dni zostało" i opis
    // relatywny ("za 3 dni") nie mają sensu, pokazujemy samą datę.
    val dueDateColor =
        if (isHistory) MaterialTheme.colorScheme.onSurfaceVariant else getDueDateColor(loan.dueDate)
    val formattedDueDate =
        if (isHistory) formatPlainDate(loan.dueDate) else formatRelativeDate(loan.dueDate)
    var showDetails by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                displayTitle(loan.title),
                modifier =
                    Modifier.clickable(onClickLabel = stringResource(R.string.cd_loan_details)) {
                        showDetails = true
                    },
                style = MaterialTheme.typography.titleMedium
            )
            // Do wyszukania autor z katalogu (format pola `creator`), a gdy go nie ma (np.
            // historia,
            // której nie uzupełniamy z katalogu) — z API wypożyczeń; wyświetlamy zawsze ten drugi.
            val searchAuthor =
                (loan.catalogAuthor ?: loan.author?.let(::authorSearchTerm))?.takeIf {
                    it.isNotBlank() && onSearch != null
                }
            val authorClickLabel = stringResource(R.string.cd_search_by_author)
            Text(
                loan.author ?: stringResource(R.string.unknown_author),
                modifier =
                    if (searchAuthor != null)
                        Modifier.clickable(onClickLabel = authorClickLabel) {
                            onSearch?.invoke(searchAuthor, SearchField.AUTHOR)
                        }
                    else Modifier,
                style = MaterialTheme.typography.bodyMedium,
                color =
                    if (searchAuthor != null) MaterialTheme.colorScheme.primary
                    else Color.Unspecified
            )
            loan.series?.let { series ->
                val seriesClickLabel = stringResource(R.string.cd_search_by_series)
                Text(
                    stringResource(R.string.series_label, series),
                    modifier =
                        if (onSearch != null)
                            Modifier.clickable(onClickLabel = seriesClickLabel) {
                                onSearch(seriesSearchTerm(series), SearchField.SERIES)
                            }
                        else Modifier,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.return_label, formattedDueDate),
                        color = dueDateColor,
                        style =
                            MaterialTheme.typography.bodySmall.copy(
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                            )
                    )
                    Text(
                        stringResource(
                            R.string.loaned_on,
                            if (isHistory) formatPlainDate(loan.loanDate)
                            else formatPastRelativeDate(loan.loanDate)
                        ),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (!isRegularLoanStatus(loan.status)) {
                    Text(
                        loan.status,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                stringResource(R.string.location_label, branchLabel(loan, withLibrary)),
                modifier =
                    if (onBranchClick != null) Modifier.clickable(onClick = onBranchClick)
                    else Modifier,
                style = MaterialTheme.typography.bodySmall,
                color =
                    if (onBranchClick != null) MaterialTheme.colorScheme.primary
                    else Color.Unspecified
            )

            loan.ownerName?.let {
                Text(
                    stringResource(R.string.loaned_by, it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }

            Text(
                stringResource(R.string.barcode_label, loan.barcode),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { showDetails = true }) {
                    Icon(
                        painterResource(R.drawable.ic_info),
                        stringResource(R.string.cd_loan_details)
                    )
                }
                ShareButton { buildLoanShareText(context, loan, formattedDueDate, withLibrary) }

                IconButton(
                    onClick = { openWebSearch(context, displayTitle(loan.title), loan.author) }
                ) {
                    Icon(
                        painterResource(R.drawable.ic_public),
                        stringResource(R.string.cd_search_web)
                    )
                }
                if (loan.renewable) {
                    Button(onClick = onRenew) { Text(stringResource(R.string.renew)) }
                }
            }
        }
    }
    if (showDetails) {
        LoanDetailsDialog(loan, isHistory, withLibrary, onDismiss = { showDetails = false })
    }
}

/** Wszystko, co wiemy o wypożyczeniu — łącznie z polami API, których nie ma na karcie. */
@Composable
private fun LoanDetailsDialog(
    loan: Loan,
    isHistory: Boolean,
    withLibrary: Boolean,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(displayTitle(loan.title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                // Pełny opis tylko, gdy różni się od nagłówka (zawiera tłumacza, ilustratora itp.).
                if (displayTitle(loan.title) != loan.title) {
                    DetailRow(stringResource(R.string.detail_full_title), loan.title)
                }
                DetailRow(stringResource(R.string.detail_author), loan.author)
                DetailRow(stringResource(R.string.detail_series), loan.series)
                DetailRow(stringResource(R.string.detail_year), loan.year?.trimEnd('.'))
                DetailRow(stringResource(R.string.detail_call_number), loan.callNumber)
                DetailRow(stringResource(R.string.detail_category), loan.itemCategoryName)
                DetailRow(
                    stringResource(R.string.detail_branch),
                    listOfNotNull(branchLabel(loan, withLibrary), loan.subLocationName)
                        .filter { it.isNotBlank() }
                        .joinToString("\n")
                )
                DetailRow(stringResource(R.string.detail_loaned), formatPlainDate(loan.loanDate))
                DetailRow(
                    stringResource(R.string.detail_due),
                    withHour(
                        if (isHistory) formatPlainDate(loan.dueDate)
                        else formatRelativeDate(loan.dueDate),
                        loan.dueHour
                    )
                )
                loan.returnDate?.let {
                    DetailRow(
                        stringResource(R.string.detail_returned),
                        withHour(formatPlainDate(it), loan.returnHour)
                    )
                }
                DetailRow(stringResource(R.string.detail_status), loan.status)
                if (!isHistory) {
                    val renewal =
                        listOfNotNull(
                            loan.maxRenewDate?.let {
                                stringResource(R.string.detail_renew_until, formatPlainDate(it))
                            }
                        ) + loan.renewStatuses
                    DetailRow(stringResource(R.string.detail_renewal), renewal.joinToString("\n"))
                }
                DetailRow(stringResource(R.string.detail_barcode), loan.barcode)
                DetailRow(stringResource(R.string.detail_owner), loan.ownerName)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } }
    )
}

@Composable
private fun DetailRow(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Column(modifier = Modifier.padding(bottom = 8.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

// Godzina z API to "2359" (Raczyńskie) albo "23:59" (mock). Domyślnego końca dnia nie pokazujemy —
// nic nie wnosi; inne godziny (np. faktyczny zwrot "1749") tak.
private fun withHour(date: String, hour: String?): String {
    val digits = hour?.filter { it.isDigit() }
    if (digits == null || digits.length != 4 || digits == "2359") return date
    return "$date, ${digits.substring(0, 2)}:${digits.substring(2)}"
}

@Composable
private fun BranchButton(onClick: () -> Unit) {
    val description = stringResource(R.string.cd_branch_info)
    IconButton(
        onClick = onClick,
        modifier = Modifier.semantics { contentDescription = description }
    ) {
        Icon(painterResource(R.drawable.ic_place), contentDescription = null)
    }
}

/** Okienko filii nad listą wypożyczeń — stan trzyma OmnisViewModel.branchDialog. */
@Composable
fun BranchInfoDialogHost(viewModel: OmnisViewModel) {
    val state by viewModel.branchDialog.collectAsStateWithLifecycle()
    state?.let { BranchInfoDialog(it, onDismiss = { viewModel.dismissBranchInfo() }) }
}

@Composable
fun BranchInfoDialog(state: BranchDialogState, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val info = state.info
    // stackMapUrl nie zawsze prowadzi do mapy (Łódź podaje tam stronę z listą filii) — wtedy
    // nawigujemy przez wyszukanie w mapach, a link pokazujemy osobno jako stronę filii.
    val mapsUrl = info?.mapsUrl?.takeIf { isMapsLink(it) }
    val websiteUrl = info?.mapsUrl?.takeIf { !isMapsLink(it) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(state.branchName) },
        text = {
            Column {
                state.tenantName?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }
                val address = info?.address
                when {
                    state.isLoading ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(stringResource(R.string.branch_info_loading))
                        }
                    address != null -> Text(stringResource(R.string.branch_address_label, address))
                    else ->
                        Text(
                            stringResource(R.string.branch_info_no_address),
                            style = MaterialTheme.typography.bodySmall
                        )
                }
                if (websiteUrl != null) {
                    TextButton(onClick = { openUrl(context, websiteUrl) }) {
                        Text(stringResource(R.string.branch_website))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !state.isLoading,
                onClick = {
                    openUrl(
                        context,
                        mapsUrl
                            ?: mapsSearchUrl(
                                branchMapsQuery(state.tenantName, state.branchName, info?.address)
                            )
                    )
                }
            ) {
                Text(
                    stringResource(
                        if (mapsUrl != null) R.string.branch_navigate
                        else R.string.branch_search_on_map
                    )
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } }
    )
}
