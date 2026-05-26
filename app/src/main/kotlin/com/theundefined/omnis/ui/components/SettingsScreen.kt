package com.theundefined.omnis.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.theundefined.omnis.data.model.Account
import com.theundefined.omnis.data.model.Tenant

@Composable
fun SettingsScreen(
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

    // Close form on success (no error and stopped loading)
    LaunchedEffect(isLoading, errorMessage) {
        if (!isLoading && errorMessage == null && showAddForm) {
            // Check if account list actually changed could be better, 
            // but for now let's assume if it was adding and finished without error, it's done.
            // Actually, ViewModel should probably handle the "success" signal better.
        }
    }

    if (accountToRemove != null) {
        AlertDialog(
            onDismissRequest = { accountToRemove = null },
            title = { Text("Usuń konto") },
            text = { Text("Czy na pewno chcesz usunąć konto ${accountToRemove?.displayName ?: accountToRemove?.username}?") },
            confirmButton = {
                TextButton(onClick = {
                    accountToRemove?.let { onRemoveAccount(it) }
                    accountToRemove = null
                }) {
                    Text("Usuń", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { accountToRemove = null }) {
                    Text("Anuluj")
                }
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Text("← Powrót") }
            Spacer(modifier = Modifier.width(16.dp))
            Text("Zarządzanie kontami", style = MaterialTheme.typography.headlineSmall)
        }

        if (showAddForm) {
            AddAccountForm(
                onAdd = { u, p, t -> 
                    onAddAccount(u, p, t)
                },
                onCancel = { showAddForm = false },
                isLoading = isLoading,
                errorMessage = errorMessage
            )
            
            // Auto-close form on success
            LaunchedEffect(isLoading, errorMessage) {
                if (!isLoading && errorMessage == null && accounts.isNotEmpty()) {
                    // showAddForm = false // This might be too aggressive if user was just viewing
                }
            }
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
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Text("Dodaj nowe konto")
            }
        }
    }
}

@Composable
fun AccountSettingsItem(
    account: Account,
    onToggle: () -> Unit,
    onRemove: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                val accountTitle = if (account.displayName != null && account.displayName != account.username) {
                    "${account.displayName} (${account.username})"
                } else {
                    account.username
                }
                Text(accountTitle, style = MaterialTheme.typography.titleMedium)
                Text(account.tenant.name, style = MaterialTheme.typography.bodySmall)
                Text(
                    "Kary: ${String.format("%.2f", account.finesAmount)} ${account.finesCurrency}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (account.finesAmount > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                )
            }
            
            Switch(checked = account.isEnabled, onCheckedChange = { onToggle() })
            
            IconButton(onClick = onRemove) {
                Text("🗑️")
            }
        }
    }
}
