package com.theundefined.omnis.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.theundefined.omnis.R
import com.theundefined.omnis.data.model.Account
import com.theundefined.omnis.data.model.sortedForSettings
import com.theundefined.omnis.ui.encodeCode128
import com.theundefined.omnis.ui.isCode128Encodable
import kotlin.math.floor

/**
 * Karta biblioteczna jako kod kreskowy Code 128 — do pokazania przy ladzie zamiast plastikowej
 * karty. Na czas wyświetlania ekran świeci z pełną jasnością (czytniki gorzej czytają ciemny
 * ekran). Dotknięcie karty na liście pokazuje tylko ją, z większym kodem — żeby przy ladzie czytnik
 * nie złapał kodu innego konta.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryCardScreen(
    accounts: List<Account>,
    onSetCardNumber: (Account, String) -> Unit,
    onBack: () -> Unit
) {
    FullBrightness()
    var editedAccount by remember { mutableStateOf<Account?>(null) }
    val shown = accounts.filter { it.isEnabled }.ifEmpty { accounts }.sortedForSettings()
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = shown.find { it.id == selectedId }

    // Pierwszeństwo przed BackHandlerem MainScreen: wstecz z pojedynczej karty wraca do listy.
    BackHandler(enabled = selected != null) { selectedId = null }

    editedAccount?.let { account ->
        CardNumberDialog(
            initial = account.libraryCardNumber ?: "",
            onSave = {
                onSetCardNumber(account, it)
                editedAccount = null
            },
            onDismiss = { editedAccount = null }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.library_card_title)) },
                navigationIcon = {
                    val backDescription = stringResource(R.string.cd_back)
                    IconButton(
                        onClick = { if (selected != null) selectedId = null else onBack() },
                        modifier = Modifier.semantics { contentDescription = backDescription }
                    ) {
                        Text("←", style = MaterialTheme.typography.headlineSmall)
                    }
                }
            )
        }
    ) { padding ->
        if (shown.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.library_card_no_accounts))
            }
            return@Scaffold
        }
        if (selected != null) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.Center
            ) {
                LibraryCardItem(
                    account = selected,
                    barcodeHeight = 200.dp,
                    onEdit = { editedAccount = selected }
                )
            }
            return@Scaffold
        }
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            item {
                Text(
                    stringResource(R.string.library_card_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
            items(shown, key = { it.id }) { account ->
                LibraryCardItem(
                    account = account,
                    onEdit = { editedAccount = account },
                    onClick = { selectedId = account.id }
                )
            }
        }
    }
}

@Composable
private fun LibraryCardItem(
    account: Account,
    onEdit: () -> Unit,
    barcodeHeight: Dp = 120.dp,
    onClick: (() -> Unit)? = null
) {
    Card(
        modifier =
            Modifier.fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                account.displayName ?: account.username,
                style = MaterialTheme.typography.titleMedium
            )
            Text(account.tenant.name, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(12.dp))

            val number = account.libraryCardNumber
            val modules = number?.let { encodeCode128(it) }
            if (number != null && modules != null) {
                // Zawsze czarne na białym, także w ciemnym motywie — tak jak na karcie.
                Surface(color = Color.White, shape = MaterialTheme.shapes.small) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Barcode(
                            modules = modules,
                            modifier = Modifier.fillMaxWidth().height(barcodeHeight)
                        )
                        Text(
                            number,
                            color = Color.Black,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 22.sp,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
                TextButton(onClick = onEdit, modifier = Modifier.align(Alignment.End)) {
                    Text(stringResource(R.string.library_card_edit_number))
                }
            } else {
                Text(
                    stringResource(R.string.library_card_no_number),
                    style = MaterialTheme.typography.bodyMedium
                )
                TextButton(onClick = onEdit, modifier = Modifier.align(Alignment.End)) {
                    Text(stringResource(R.string.library_card_set_number))
                }
            }
        }
    }
}

/**
 * Rysuje moduły kodu (true = kreska) ze strefą ciszy po 10 modułów z każdej strony. Szerokość
 * modułu zaokrąglamy w dół do całych pikseli, żeby kreski nie rozmywały się na krawędziach —
 * czytnik rozróżnia szerokości 1–4 modułów.
 */
@Composable
private fun Barcode(modules: BooleanArray, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val total = modules.size + 2 * QUIET_ZONE_MODULES
        val moduleWidth = floor(size.width / total).coerceAtLeast(1f)
        val start = (size.width - moduleWidth * modules.size) / 2
        var i = 0
        while (i < modules.size) {
            if (!modules[i]) {
                i++
                continue
            }
            var end = i
            while (end < modules.size && modules[end]) end++
            drawRect(
                color = Color.Black,
                topLeft = Offset(start + i * moduleWidth, 0f),
                size = Size((end - i) * moduleWidth, size.height)
            )
            i = end
        }
    }
}

private const val QUIET_ZONE_MODULES = 10

@Composable
private fun CardNumberDialog(initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var value by remember { mutableStateOf(initial) }
    val trimmed = value.trim()
    val valid = trimmed.isEmpty() || isCode128Encodable(trimmed)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.library_card_number_label)) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                isError = !valid,
                label = { Text(stringResource(R.string.library_card_number_label)) },
                supportingText = {
                    Text(
                        stringResource(
                            if (valid) R.string.library_card_number_hint
                            else R.string.library_card_number_invalid
                        )
                    )
                }
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(trimmed) }, enabled = valid) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

/** Pełna jasność okna, dopóki ekran jest widoczny; potem wraca ustawienie systemowe. */
@Composable
private fun FullBrightness() {
    val activity = LocalContext.current.findActivity() ?: return
    DisposableEffect(activity) {
        val window = activity.window
        val previous = window.attributes.screenBrightness
        window.attributes = window.attributes.apply { screenBrightness = 1f }
        onDispose { window.attributes = window.attributes.apply { screenBrightness = previous } }
    }
}

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
