package com.theundefined.omnis.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.theundefined.omnis.R
import com.theundefined.omnis.data.model.Account
import com.theundefined.omnis.data.model.Tenant
import com.theundefined.omnis.ui.OmnisViewModel
import kotlinx.coroutines.flow.collectLatest

@Composable
fun SettingsScreen(
    viewModel: OmnisViewModel,
    accounts: List<Account>,
    onToggleAccount: (Account) -> Unit,
    onRemoveAccount: (Account) -> Unit,
    onAddAccount: (String, String, Tenant) -> Unit,
    isLoading: Boolean,
    onBack: () -> Unit,
    errorMessage: String? = null
) {
    var showAddForm by remember { mutableStateOf(false) }
    var accountToRemove by remember { mutableStateOf<Account?>(null) }

    // Listen for success events to close the form
    LaunchedEffect(Unit) {
        viewModel.events.collectLatest { event ->
            if (event is OmnisViewModel.UiEvent.AccountAdded) {
                showAddForm = false
            }
        }
    }

    if (accountToRemove != null) {
        val accountName = accountToRemove?.displayName ?: accountToRemove?.username ?: ""
        AlertDialog(
            onDismissRequest = { accountToRemove = null },
            title = { Text(stringResource(R.string.remove_account)) },
            text = { Text(stringResource(R.string.remove_account_confirm, accountName)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        accountToRemove?.let { onRemoveAccount(it) }
                        accountToRemove = null
                    }
                ) {
                    Text(stringResource(R.string.remove), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { accountToRemove = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.back_to_main)) }
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                stringResource(R.string.manage_accounts),
                style = MaterialTheme.typography.headlineSmall
            )
        }

        if (showAddForm) {
            AddAccountForm(
                onAdd = { u, p, t -> onAddAccount(u, p, t) },
                onCancel = { showAddForm = false },
                isLoading = isLoading,
                errorMessage = errorMessage
            )
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(accounts, key = { it.id }) { account ->
                    AccountSettingsItem(
                        account = account,
                        onToggle = { onToggleAccount(account) },
                        onRemove = { accountToRemove = account }
                    )
                }
            }

            Button(
                onClick = { showAddForm = true },
                modifier = Modifier.fillMaxWidth().padding(16.dp)
            ) {
                Text(stringResource(R.string.add_new_account))
            }

            Text(
                text =
                    stringResource(
                        R.string.version_label,
                        com.theundefined.omnis.BuildConfig.VERSION_NAME
                    ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 16.dp)
            )
        }
    }
}

@Composable
fun AccountSettingsItem(account: Account, onToggle: () -> Unit, onRemove: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                val accountTitle =
                    if (account.displayName != null && account.displayName != account.username) {
                        "${account.displayName} (${account.username})"
                    } else {
                        account.username
                    }
                Text(accountTitle, style = MaterialTheme.typography.titleMedium)
                Text(account.tenant.name, style = MaterialTheme.typography.bodySmall)
                Text(
                    stringResource(
                        R.string.fines_label,
                        account.finesAmount,
                        account.finesCurrency
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color =
                        if (account.finesAmount > 0) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurface
                )
            }

            Switch(checked = account.isEnabled, onCheckedChange = { onToggle() })

            IconButton(onClick = onRemove) { Text("🗑️") }
        }
    }
}
