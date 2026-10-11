package com.theundefined.omnis.ui.components

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theundefined.omnis.R
import com.theundefined.omnis.data.model.Account
import com.theundefined.omnis.data.model.CatalogLink
import com.theundefined.omnis.data.model.Tenant
import com.theundefined.omnis.data.model.sortedForSettings
import com.theundefined.omnis.guard.GuardNotifier
import com.theundefined.omnis.guard.THRESHOLD_CHOICES
import com.theundefined.omnis.ui.GuardUiState
import com.theundefined.omnis.ui.OmnisViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.collectLatest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: OmnisViewModel,
    accounts: List<Account>,
    onToggleAccount: (Account) -> Unit,
    onRemoveAccount: (Account) -> Unit,
    onAddAccount: (String, String, Tenant) -> Unit,
    onAddCustomAccount: (String, String, CatalogLink) -> Unit,
    onEnterDemoMode: () -> Unit,
    onExitDemoMode: () -> Unit,
    isLoading: Boolean,
    onBack: () -> Unit,
    errorMessage: String? = null
) {
    var showAddForm by remember { mutableStateOf(false) }
    var accountToRemove by remember { mutableStateOf<Account?>(null) }
    val context = LocalContext.current
    val guardState by viewModel.guardUiState.collectAsStateWithLifecycle()

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
                    onAddCustom = { u, p, l -> onAddCustomAccount(u, p, l) },
                    onCancel = { showAddForm = false },
                    isLoading = isLoading,
                    errorMessage = errorMessage
                )
            } else {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(accounts.sortedForSettings(), key = { it.id }) { account ->
                        AccountSettingsItem(
                            account = account,
                            onToggle = { onToggleAccount(account) },
                            onRemove = { accountToRemove = account },
                            showAutoRenew =
                                guardState.settings.enabled && account.isEnabled && !account.isDemo,
                            onAutoRenewChange = { viewModel.setAutoRenew(account, it) },
                            guardPaused = account.id in guardState.pausedAccountIds
                        )
                    }
                    item(key = "guard") {
                        GuardSettingsCard(
                            state = guardState,
                            onEnabledChange = { viewModel.setGuardEnabled(it) },
                            onThresholdChange = { viewModel.setGuardThreshold(it) },
                            onCheckNow = { viewModel.checkGuardNow() }
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
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) {
                    Text(
                        stringResource(R.string.about_app_source_link),
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                // Wymóg Google Play: polityka prywatności dostępna także w samej aplikacji.
                TextButton(
                    onClick = { openUrl(context, PRIVACY_POLICY_URL) },
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 16.dp)
                ) {
                    Text(
                        stringResource(R.string.privacy_policy_link),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

private const val PRIVACY_POLICY_URL = "https://theundefined.github.io/omnis-mobile/privacy.html"

@Composable
fun AccountSettingsItem(
    account: Account,
    onToggle: () -> Unit,
    onRemove: () -> Unit,
    showAutoRenew: Boolean = false,
    onAutoRenewChange: (Boolean) -> Unit = {},
    guardPaused: Boolean = false
) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
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

            val removeDescription = stringResource(R.string.remove_account)
            IconButton(
                onClick = onRemove,
                modifier = Modifier.semantics { contentDescription = removeDescription }
            ) {
                Icon(painterResource(R.drawable.ic_delete), contentDescription = null)
            }
        }
        if (guardPaused && account.isEnabled) {
            Text(
                stringResource(R.string.guard_paused),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }
        if (showAutoRenew) {
            Row(
                modifier =
                    Modifier.fillMaxWidth()
                        .toggleable(
                            value = account.autoRenew,
                            role = Role.Checkbox,
                            onValueChange = onAutoRenewChange
                        )
                        .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(checked = account.autoRenew, onCheckedChange = null)
                Text(
                    stringResource(R.string.guard_auto_renew),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
        Spacer(Modifier.height(if (showAutoRenew) 8.dp else 16.dp))
    }
}

/**
 * Przypomnienia (strażnik terminów, guard/). Domyślnie wyłączone; włączenie na Androidzie 13+
 * najpierw prosi o zgodę na powiadomienia — bez niej przełącznik zostaje wyłączony.
 */
@Composable
private fun GuardSettingsCard(
    state: GuardUiState,
    onEnabledChange: (Boolean) -> Unit,
    onThresholdChange: (Int) -> Unit,
    onCheckNow: () -> Unit
) {
    val context = LocalContext.current
    var permissionDenied by remember { mutableStateOf(false) }
    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            permissionDenied = !granted
            if (granted) onEnabledChange(true)
        }
    val notificationsBlocked = state.settings.enabled && !GuardNotifier(context).canNotify()

    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.guard_section_title),
                style = MaterialTheme.typography.titleMedium
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.guard_enable),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = state.settings.enabled,
                    onCheckedChange = { enabled ->
                        if (
                            enabled &&
                                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.POST_NOTIFICATIONS
                                ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            permissionDenied = false
                            onEnabledChange(enabled)
                        }
                    }
                )
            }
            Text(
                stringResource(R.string.guard_enable_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (permissionDenied || notificationsBlocked) {
                Text(
                    stringResource(R.string.guard_permission_denied),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp)
                )
                TextButton(
                    onClick = {
                        val intent =
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        context.startActivity(intent)
                    }
                ) {
                    Text(stringResource(R.string.guard_open_notification_settings))
                }
            }
            if (state.settings.enabled) {
                Text(
                    stringResource(R.string.guard_threshold),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 12.dp)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    THRESHOLD_CHOICES.forEach { days ->
                        FilterChip(
                            selected = state.settings.thresholdDays == days,
                            onClick = { onThresholdChange(days) },
                            label = { Text(days.toString()) }
                        )
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    Text(
                        state.lastRunAt?.let {
                            stringResource(
                                R.string.guard_last_run,
                                Instant.ofEpochMilli(it)
                                    .atZone(ZoneId.systemDefault())
                                    .format(LAST_RUN_FORMAT)
                            )
                        } ?: stringResource(R.string.guard_never_run),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    if (state.isChecking) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        TextButton(onClick = onCheckNow) {
                            Text(stringResource(R.string.guard_check_now))
                        }
                    }
                }
            }
        }
    }
}

private val LAST_RUN_FORMAT = DateTimeFormatter.ofPattern("dd.MM HH:mm")
