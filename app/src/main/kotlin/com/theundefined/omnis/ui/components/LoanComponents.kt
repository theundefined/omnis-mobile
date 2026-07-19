package com.theundefined.omnis.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
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
fun LoanList(groupedLoans: Map<String, List<Loan>>, onRenew: (Loan) -> Unit) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        groupedLoans.forEach { (groupKey, accountLoans) ->
            item {
                Text(
                    text = groupKey,
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.secondary
                )
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
                items(accountLoans, key = { it.id }) { loan ->
                    LoanItem(loan, onRenew = { onRenew(loan) })
                }
            }
            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }
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
fun LoanItem(loan: Loan, onRenew: () -> Unit) {
    val context = LocalContext.current
    val dueDateColor = getDueDateColor(loan.dueDate)
    val formattedDueDate = formatRelativeDate(loan.dueDate)

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
                        val shareText =
                            context.getString(
                                R.string.share_book,
                                loan.title,
                                loan.author ?: context.getString(R.string.unknown_author),
                                formattedDueDate,
                                "${loan.libraryName} - ${loan.locationName}",
                                loan.barcode
                            )
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
