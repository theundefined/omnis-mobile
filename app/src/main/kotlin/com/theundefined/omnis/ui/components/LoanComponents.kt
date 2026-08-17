package com.theundefined.omnis.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.compose.ui.unit.dp
import com.theundefined.omnis.R
import com.theundefined.omnis.data.model.Loan
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

@Composable
fun LoanList(
    groupedLoans: Map<String, List<Loan>>,
    onRenew: (Loan) -> Unit = {},
    onRenewAll: (List<Loan>) -> Unit = {},
    isHistory: Boolean = false,
    footer: (@Composable () -> Unit)? = null
) {
    val context = LocalContext.current
    var renewConfirmGroup by remember { mutableStateOf<Pair<String, List<Loan>>?>(null) }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        groupedLoans.forEach { (groupKey, accountLoans) ->
            item {
                val renewableLoans = accountLoans.filter { it.renewable }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = groupKey,
                        modifier = Modifier.padding(vertical = 16.dp),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    if (accountLoans.isNotEmpty()) {
                        val groupShareText = buildGroupShareText(groupKey, accountLoans, isHistory)
                        Row {
                            IconButton(
                                onClick = {
                                    val sendIntent =
                                        Intent().apply {
                                            action = Intent.ACTION_SEND
                                            putExtra(Intent.EXTRA_TEXT, groupShareText)
                                            type = "text/plain"
                                        }
                                    context.startActivity(Intent.createChooser(sendIntent, null))
                                }
                            ) {
                                Text("📤")
                            }
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
                    LoanItem(loan, onRenew = { onRenew(loan) }, isHistory = isHistory)
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

private fun buildLoanShareText(context: Context, loan: Loan, formattedDueDate: String): String =
    context.getString(
        R.string.share_book,
        loan.title,
        loan.author ?: context.getString(R.string.unknown_author),
        formattedDueDate,
        "${loan.libraryName} - ${loan.locationName}",
        loan.barcode
    )

@Composable
private fun buildGroupShareText(groupKey: String, loans: List<Loan>, isHistory: Boolean): String {
    val context = LocalContext.current
    // `joinToString { ... }` nie jest inline w stdlibie, więc Compose nie pozwala wywoływać z jego
    // lambdy funkcji @Composable (formatPlainDate/formatRelativeDate) — zwykła pętla `for` działa,
    // bo to inline'owana kontrola przepływu, a nie osobna lambda.
    val loanTexts = mutableListOf<String>()
    for (loan in loans) {
        val formattedDueDate =
            if (isHistory) formatPlainDate(loan.dueDate) else formatRelativeDate(loan.dueDate)
        loanTexts.add(buildLoanShareText(context, loan, formattedDueDate))
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

@Composable
fun formatPlainDate(dateStr: String): String {
    val date = parseFlexibleDate(dateStr) ?: return dateStr
    return date.format(DateTimeFormatter.ofPattern("yyyy.MM.dd"))
}

@Composable
fun LoanItem(loan: Loan, onRenew: () -> Unit, isHistory: Boolean = false) {
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
                stringResource(R.string.location_label, loan.libraryName, loan.locationName),
                style = MaterialTheme.typography.bodySmall
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
                IconButton(
                    onClick = {
                        val shareText = buildLoanShareText(context, loan, formattedDueDate)
                        val sendIntent: Intent =
                            Intent().apply {
                                action = Intent.ACTION_SEND
                                putExtra(Intent.EXTRA_TEXT, shareText)
                                type = "text/plain"
                            }
                        val shareIntent = Intent.createChooser(sendIntent, null)
                        context.startActivity(shareIntent)
                    }
                ) {
                    Text("📤")
                }

                IconButton(
                    onClick = {
                        val query = Uri.encode("${loan.title} ${loan.author ?: ""}")
                        val intent =
                            Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("https://www.google.com/search?q=$query")
                            )
                        context.startActivity(intent)
                    }
                ) {
                    Text("🔍")
                }
                if (loan.renewable) {
                    Button(onClick = onRenew) { Text(stringResource(R.string.renew)) }
                }
            }
        }
    }
}
