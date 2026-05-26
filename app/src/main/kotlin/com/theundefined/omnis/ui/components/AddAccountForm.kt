package com.theundefined.omnis.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.theundefined.omnis.data.model.KNOWN_TENANTS
import com.theundefined.omnis.data.model.Tenant

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun AddAccountForm(
    onAdd: (String, String, Tenant) -> Unit,
    onCancel: () -> Unit,
    isLoading: Boolean,
    errorMessage: String? = null
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var selectedTenant by remember { mutableStateOf(KNOWN_TENANTS[0]) }
    var expanded by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }
    val autofill = LocalAutofill.current
    val autofillTree = LocalAutofillTree.current

    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            snackbarHostState.showSnackbar(it)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Text("Dodaj konto", style = MaterialTheme.typography.headlineMedium)
            Spacer(modifier = Modifier.height(16.dp))

            // Library selection dropdown
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = !expanded }
            ) {
                TextField(
                    value = selectedTenant.name,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Biblioteka") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier
                        .menuAnchor()
                        .fillMaxWidth()
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
                            }
                        )
                    }
                }
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
                label = { Text("Login") },
                modifier = Modifier
                    .fillMaxWidth()
                    .onGloballyPositioned { usernameAutofillNode.boundingBox = it.boundsInWindow() }
                    .onFocusChanged { focusState ->
                        if (focusState.isFocused) {
                            autofill?.requestAutofillForNode(usernameAutofillNode)
                        }
                    },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Email,
                    imeAction = ImeAction.Next
                )
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
                label = { Text("Hasło") },
                modifier = Modifier
                    .fillMaxWidth()
                    .onGloballyPositioned { passwordAutofillNode.boundingBox = it.boundsInWindow() }
                    .onFocusChanged { focusState ->
                        if (focusState.isFocused) {
                            autofill?.requestAutofillForNode(passwordAutofillNode)
                        }
                    },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
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
                    TextButton(onClick = onCancel) {
                        Text("Anuluj")
                    }
                    Button(onClick = { onAdd(username, password, selectedTenant) }) {
                        Text("Zaloguj i dodaj")
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
