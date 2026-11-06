package com.theundefined.omnis.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.theundefined.omnis.R
import com.theundefined.omnis.ui.DueFilter
import com.theundefined.omnis.ui.GroupingMode
import com.theundefined.omnis.ui.SortMode

private fun GroupingMode.labelRes(): Int =
    when (this) {
        GroupingMode.ACCOUNT -> R.string.group_by_account
        GroupingMode.BRANCH -> R.string.group_by_branch
        GroupingMode.NONE -> R.string.group_none
    }

private fun SortMode.labelRes(): Int =
    when (this) {
        SortMode.DUE_DATE -> R.string.sort_by_due_date
        SortMode.LOAN_DATE -> R.string.sort_by_loan_date
        SortMode.TITLE -> R.string.sort_by_title
    }

/**
 * Linia nad listą pokazująca aktualne grupowanie i sortowanie, np. "Według konta · termin zwrotu
 * ▾". Stuknięcie otwiera menu wyboru — zastępuje osobne przyciski grupowania i sortowania w pasku
 * górnym, więc aktywne kryterium jest zawsze widoczne.
 */
@Composable
fun LoanViewBar(
    groupingMode: GroupingMode,
    sortMode: SortMode,
    onGroupingChange: (GroupingMode) -> Unit,
    onSortChange: (SortMode) -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        TextButton(onClick = { menuExpanded = true }) {
            Text(
                stringResource(
                    R.string.view_summary,
                    stringResource(groupingMode.labelRes()),
                    stringResource(sortMode.labelRes()).lowercase()
                ),
                style = MaterialTheme.typography.labelLarge
            )
        }
        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
            MenuSectionHeader(stringResource(R.string.view_menu_group))
            GroupingMode.entries.forEach { mode ->
                CheckedMenuItem(stringResource(mode.labelRes()), selected = mode == groupingMode) {
                    onGroupingChange(mode)
                    menuExpanded = false
                }
            }
            HorizontalDivider()
            MenuSectionHeader(stringResource(R.string.view_menu_sort))
            SortMode.entries.forEach { mode ->
                CheckedMenuItem(stringResource(mode.labelRes()), selected = mode == sortMode) {
                    onSortChange(mode)
                    menuExpanded = false
                }
            }
        }
    }
}

@Composable
private fun MenuSectionHeader(text: String) {
    Text(
        text,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary
    )
}

@Composable
private fun CheckedMenuItem(text: String, selected: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text) },
        onClick = onClick,
        leadingIcon = { Box(Modifier.width(24.dp)) { if (selected) Text("✓") } }
    )
}

private fun DueFilter.labelRes(): Int =
    when (this) {
        DueFilter.ALL -> R.string.group_all_header
        DueFilter.OVERDUE -> R.string.filter_overdue
        DueFilter.WITHIN_3 -> R.string.filter_within_3
        DueFilter.WITHIN_7 -> R.string.filter_within_7
    }

/**
 * Rząd chipów filtrujących listę po terminie zwrotu. Udostępnianie (nagłówek grupy) dotyczy
 * przefiltrowanej listy, więc "tylko te za 3 dni" to jeden chip + "udostępnij". Ponowne stuknięcie
 * w aktywny chip wyłącza filtr.
 */
@Composable
fun DueFilterBar(filter: DueFilter, onFilterChange: (DueFilter) -> Unit) {
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        DueFilter.entries
            .filter { it != DueFilter.ALL }
            .forEach { option ->
                FilterChip(
                    selected = option == filter,
                    onClick = { onFilterChange(if (option == filter) DueFilter.ALL else option) },
                    label = { Text(stringResource(option.labelRes())) }
                )
            }
    }
}
