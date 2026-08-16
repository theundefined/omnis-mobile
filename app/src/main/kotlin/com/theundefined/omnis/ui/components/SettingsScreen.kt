package com.theundefined.omnis.ui.components

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.theundefined.omnis.R
import com.theundefined.omnis.data.model.Account
import com.theundefined.omnis.data.model.Tenant
import com.theundefined.omnis.ui.OmnisViewModel
import kotlinx.coroutines.flow.collectLatest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: OmnisViewModel,
    accounts: List<Account>,
    onToggleAccount: (Account) -> Unit,
    onTogglePreferredForSearch: (Account) -> Unit,
    onRemoveAccount: (Account) -> Unit,
    onAddAccount: (String, String, Tenant) -> Unit,
    onEnterDemoMode: () -> Unit,
    onExitDemoMode: () -> Unit,
    isLoading: Boolean,
    onBack: () -> Unit,
    errorMessage: String? = null
) {
    var showAddForm by remember { mutableStateOf(false) }
    var accountToRemove by remember { mutableStateOf<Account?>(null) }
    val context = LocalContext.current

    // Closing the add-account form takes priority over leaving the settings screen; when the
    // form isn't shown, the enclosing MainScreen's BackHandler (currentScreen == "settings")
    // takes over and navigates back to the main screen.
    BackHandler(enabled = showAddForm) { showAddForm = false }

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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.manage_accounts)) },
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
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
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
                            onTogglePreferredForSearch = { onTogglePreferredForSearch(account) },
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

                val isDemoModeActive = accounts.any { it.isDemo && it.isEnabled }
                OutlinedButton(
                    onClick = { if (isDemoModeActive) onExitDemoMode() else onEnterDemoMode() },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                ) {
                    Text(
                        stringResource(
                            if (isDemoModeActive) R.string.demo_mode_disable
                            else R.string.demo_mode_enable
                        )
                    )
                }
                if (!isDemoModeActive) {
                    Text(
                        stringResource(R.string.demo_mode_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }

                Text(
                    text =
                        stringResource(
                            R.string.version_label,
                            com.theundefined.omnis.BuildConfig.VERSION_NAME
                        ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )

                TextButton(
                    onClick = {
                        val intent =
                            Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("https://github.com/theundefined/omnis-mobile")
                            )
                        context.startActivity(intent)
                    },
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 16.dp)
                ) {
                    Text(
                        stringResource(R.string.about_app_source_link),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@Composable
fun AccountSettingsItem(
    account: Account,
    onToggle: () -> Unit,
    onTogglePreferredForSearch: () -> Unit,
    onRemove: () -> Unit
) {
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

            val preferredForSearchDescription = stringResource(R.string.cd_preferred_for_search)
            IconButton(
                onClick = onTogglePreferredForSearch,
                modifier = Modifier.semantics { contentDescription = preferredForSearchDescription }
            ) {
                Text(if (account.preferredForSearch) "⭐" else "☆")
            }

            val removeDescription = stringResource(R.string.remove_account)
            IconButton(
                onClick = onRemove,
                modifier = Modifier.semantics { contentDescription = removeDescription }
            ) {
                Text("🗑️")
            }
        }
    }
}
