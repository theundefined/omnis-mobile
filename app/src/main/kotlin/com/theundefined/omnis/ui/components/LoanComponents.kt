package com.theundefined.omnis.ui.components

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theundefined.omnis.R
import com.theundefined.omnis.data.model.Loan
import com.theundefined.omnis.data.model.branchMapsQuery
import com.theundefined.omnis.data.model.isMapsLink
import com.theundefined.omnis.data.model.mapsSearchUrl
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
                                    Text("🔁")
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
                        onBranchClick = onBranchClick?.let { { it(listOf(loan)) } }
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
        loan.title,
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

@Composable
fun formatRelativeDate(dateStr: String): String {
    val date = parseFlexibleDate(dateStr) ?: return dateStr
    val today = LocalDate.now()
    val daysUntil = ChronoUnit.DAYS.between(today, date)

    val relative =
        when {
            daysUntil < 0 -> stringResource(R.string.days_overdue, Math.abs(daysUntil))
            daysUntil == 0L -> stringResource(R.string.today)
            daysUntil == 1L -> stringResource(R.string.tomorrow)
            else -> stringResource(R.string.in_days, daysUntil)
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
    onBranchClick: (() -> Unit)? = null
) {
    val context = LocalContext.current
    // Dla historii wypożyczenie jest już zakończone — kolorowanie "ile dni zostało" i opis
    // relatywny ("za 3 dni") nie mają sensu, pokazujemy samą datę.
    val dueDateColor =
        if (isHistory) MaterialTheme.colorScheme.onSurfaceVariant else getDueDateColor(loan.dueDate)
    val formattedDueDate =
        if (isHistory) formatPlainDate(loan.dueDate) else formatRelativeDate(loan.dueDate)

    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(loan.title, style = MaterialTheme.typography.titleMedium)
            Text(
                loan.author ?: stringResource(R.string.unknown_author),
                style = MaterialTheme.typography.bodyMedium
            )

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
                        stringResource(R.string.loaned_on, loan.loanDate),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Text(
                    loan.status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
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
                ShareButton { buildLoanShareText(context, loan, formattedDueDate, withLibrary) }

                IconButton(onClick = { openWebSearch(context, loan.title, loan.author) }) {
                    Text("🔍")
                }
                if (loan.renewable) {
                    Button(onClick = onRenew) { Text(stringResource(R.string.renew)) }
                }
            }
        }
    }
}

@Composable
private fun BranchButton(onClick: () -> Unit) {
    val description = stringResource(R.string.cd_branch_info)
    IconButton(
        onClick = onClick,
        modifier = Modifier.semantics { contentDescription = description }
    ) {
        Text("📍")
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
