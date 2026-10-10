package com.theundefined.omnis.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.AutofillNode
import androidx.compose.ui.autofill.AutofillType
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalAutofill
import androidx.compose.ui.platform.LocalAutofillTree
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.theundefined.omnis.R
import com.theundefined.omnis.data.model.CatalogLink
import com.theundefined.omnis.data.model.DEMO_PASSWORD
import com.theundefined.omnis.data.model.DEMO_USERNAME
import com.theundefined.omnis.data.model.EXAMPLE_CATALOG_LINK
import com.theundefined.omnis.data.model.KNOWN_TENANTS
import com.theundefined.omnis.data.model.Tenant
import com.theundefined.omnis.data.model.parseCatalogLink

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun AddAccountForm(
    onAdd: (String, String, Tenant) -> Unit,
    onAddCustom: (String, String, CatalogLink) -> Unit,
    onCancel: () -> Unit,
    isLoading: Boolean,
    errorMessage: String? = null
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var selectedTenant by remember { mutableStateOf(KNOWN_TENANTS[0]) }
    var expanded by remember { mutableStateOf(false) }
    // Biblioteka spoza listy — wklejony link do jej katalogu zamiast wyboru z KNOWN_TENANTS.
    var customMode by remember { mutableStateOf(false) }
    var catalogLinkText by remember { mutableStateOf("") }
    val catalogLink = remember(catalogLinkText) { parseCatalogLink(catalogLinkText) }

    val snackbarHostState = remember { SnackbarHostState() }
    val autofill = LocalAutofill.current
    val autofillTree = LocalAutofillTree.current

    LaunchedEffect(errorMessage) { errorMessage?.let { snackbarHostState.showSnackbar(it) } }

    Box(modifier = Modifier.fillMaxSize().imePadding()) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)
        ) {
            Text(
                stringResource(R.string.add_account),
                style = MaterialTheme.typography.headlineMedium
            )
            Spacer(modifier = Modifier.height(16.dp))

            if (customMode) {
                TextField(
                    value = catalogLinkText,
                    onValueChange = { catalogLinkText = it },
                    label = { Text(stringResource(R.string.custom_library_link)) },
                    placeholder = { Text("https://…/discovery/search?vid=…") },
                    isError = catalogLinkText.isNotBlank() && catalogLink == null,
                    supportingText = {
                        Text(
                            if (catalogLinkText.isNotBlank() && catalogLink == null)
                                stringResource(R.string.custom_library_link_invalid)
                            else stringResource(R.string.custom_library_link_hint)
                        )
                    },
                    singleLine = true,
                    keyboardOptions =
                        KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Next
                        ),
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    stringResource(R.string.custom_library_example, EXAMPLE_CATALOG_LINK),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row {
                    TextButton(onClick = { catalogLinkText = EXAMPLE_CATALOG_LINK }) {
                        Text(stringResource(R.string.custom_library_use_example))
                    }
                    TextButton(onClick = { customMode = false }) {
                        Text(stringResource(R.string.custom_library_back_to_list))
                    }
                }
            } else {
                // Library selection dropdown
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = !expanded }
                ) {
                    TextField(
                        value = selectedTenant.name,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.library)) },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                        },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        KNOWN_TENANTS.forEach { tenant ->
                            DropdownMenuItem(
                                text = { Text(tenant.name) },
                                onClick = {
                                    selectedTenant = tenant
                                    expanded = false
                                    if (tenant.isDemo) {
                                        if (username.isEmpty()) username = DEMO_USERNAME
                                        if (password.isEmpty()) password = DEMO_PASSWORD
                                    }
                                }
                            )
                        }
                    }
                }
                TextButton(onClick = { customMode = true }) {
                    Text(stringResource(R.string.custom_library_not_listed))
                }
            }

            if (!customMode && selectedTenant.isDemo) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    stringResource(R.string.demo_credentials_hint, DEMO_USERNAME, DEMO_PASSWORD),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Username field
            val usernameAutofillNode = remember {
                AutofillNode(
                    autofillTypes = listOf(AutofillType.Username),
                    onFill = { username = it }
                )
            }
            TextField(
                value = username,
                onValueChange = { username = it },
                label = { Text(stringResource(R.string.login)) },
                modifier =
                    Modifier.fillMaxWidth()
                        .onGloballyPositioned {
                            usernameAutofillNode.boundingBox = it.boundsInWindow()
                        }
                        .onFocusChanged { focusState ->
                            if (focusState.isFocused) {
                                autofill?.requestAutofillForNode(usernameAutofillNode)
                            }
                        },
                keyboardOptions =
                    KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next)
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Password field
            val passwordAutofillNode = remember {
                AutofillNode(
                    autofillTypes = listOf(AutofillType.Password),
                    onFill = { password = it }
                )
            }
            TextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(stringResource(R.string.password)) },
                modifier =
                    Modifier.fillMaxWidth()
                        .onGloballyPositioned {
                            passwordAutofillNode.boundingBox = it.boundsInWindow()
                        }
                        .onFocusChanged { focusState ->
                            if (focusState.isFocused) {
                                autofill?.requestAutofillForNode(passwordAutofillNode)
                            }
                        },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions =
                    KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done
                    )
            )

            SideEffect {
                autofillTree.children[usernameAutofillNode.id] = usernameAutofillNode
                autofillTree.children[passwordAutofillNode.id] = passwordAutofillNode
            }

            Spacer(modifier = Modifier.height(24.dp))

            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
            } else {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
                    Button(
                        enabled = !customMode || catalogLink != null,
                        onClick = {
                            val link = catalogLink
                            if (customMode && link != null) onAddCustom(username, password, link)
                            else onAdd(username, password, selectedTenant)
                        }
                    ) {
                        Text(stringResource(R.string.login_and_add))
                    }
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}
