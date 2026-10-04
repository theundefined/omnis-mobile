package com.theundefined.omnis.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theundefined.omnis.R
import com.theundefined.omnis.data.model.displayTitle
import com.theundefined.omnis.ui.HoldPlacementPhase
import com.theundefined.omnis.ui.HoldPlacementState
import com.theundefined.omnis.ui.OmnisViewModel

@Composable
fun HoldPlacementDialogHost(viewModel: OmnisViewModel) {
    val state by viewModel.holdPlacement.collectAsStateWithLifecycle()
    state?.let { HoldPlacementDialog(it, viewModel) }
}

@Composable
private fun HoldPlacementDialog(state: HoldPlacementState, viewModel: OmnisViewModel) {
    AlertDialog(
        onDismissRequest = { viewModel.dismissHoldPlacement() },
        title = { Text(stringResource(R.string.hold_place_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(displayTitle(state.title), style = MaterialTheme.typography.titleSmall)
                Text(
                    editionLabel(LocalContext.current, state.version),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                when (state.phase) {
                    HoldPlacementPhase.SELECT -> SelectContent(state, viewModel)
                    HoldPlacementPhase.LOADING -> Progress(R.string.hold_place_loading)
                    HoldPlacementPhase.CONFIRM -> ConfirmContent(state, viewModel)
                    HoldPlacementPhase.PLACING -> Progress(R.string.hold_place_placing)
                    HoldPlacementPhase.DONE -> DoneContent(state)
                }
                state.error?.let {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            when (state.phase) {
                HoldPlacementPhase.SELECT ->
                    TextButton(
                        onClick = { viewModel.prepareHold() },
                        enabled = state.account != null && state.branch != null
                    ) {
                        Text(stringResource(R.string.hold_place_next))
                    }
                HoldPlacementPhase.CONFIRM ->
                    TextButton(
                        onClick = { viewModel.confirmHold() },
                        enabled = state.pickup != null
                    ) {
                        Text(stringResource(R.string.hold_place))
                    }
                HoldPlacementPhase.DONE ->
                    TextButton(onClick = { viewModel.dismissHoldPlacement() }) {
                        Text(stringResource(R.string.close))
                    }
                else -> {}
            }
        },
        dismissButton = {
            when (state.phase) {
                HoldPlacementPhase.SELECT,
                HoldPlacementPhase.LOADING ->
                    TextButton(onClick = { viewModel.dismissHoldPlacement() }) {
                        Text(stringResource(R.string.cancel))
                    }
                HoldPlacementPhase.CONFIRM ->
                    TextButton(onClick = { viewModel.backToHoldSelection() }) {
                        Text(stringResource(R.string.hold_place_back))
                    }
                else -> {}
            }
        }
    )
}

@Composable
private fun SelectContent(state: HoldPlacementState, viewModel: OmnisViewModel) {
    if (state.accounts.size > 1) {
        SectionLabel(R.string.hold_place_account)
        state.accounts.forEach { account ->
            ChoiceRow(
                selected = account.id == state.accountId,
                onClick = { viewModel.selectHoldAccount(account.id) }
            ) {
                Text(account.displayName ?: account.username)
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
    }
    SectionLabel(R.string.hold_place_branch)
    state.branches.forEachIndexed { index, branch ->
        ChoiceRow(
            selected = index == state.branchIndex,
            onClick = { viewModel.selectHoldBranch(index) }
        ) {
            Column {
                Text(branchLabel(branch), style = MaterialTheme.typography.bodyMedium)
                BranchStatusBadge(branch)
            }
        }
    }
}

@Composable
private fun ConfirmContent(state: HoldPlacementState, viewModel: OmnisViewModel) {
    val options = state.options ?: return
    val item = options.item
    if (state.accounts.size > 1) {
        state.account?.let { Text(it.displayName ?: it.username) }
    }
    state.branch?.let { Text(branchLabel(it), style = MaterialTheme.typography.bodyMedium) }
    item.statusName?.let {
        Text(
            stringResource(R.string.hold_place_copy_status, it),
            style = MaterialTheme.typography.bodySmall
        )
        if (!it.lowercase().contains("na półce")) {
            Text(
                stringResource(R.string.hold_place_queue_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    state.existingHold?.let {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            stringResource(R.string.hold_place_existing, it.status),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall
        )
    }
    Spacer(modifier = Modifier.height(8.dp))
    if (options.pickupLocations.size == 1) {
        Text(
            stringResource(R.string.hold_pickup_label, options.pickupLocations.first().name),
            style = MaterialTheme.typography.bodyMedium
        )
    } else {
        SectionLabel(R.string.hold_place_pickup)
        options.pickupLocations.forEach { pickup ->
            ChoiceRow(
                selected = pickup == state.pickup,
                onClick = { viewModel.selectHoldPickup(pickup) }
            ) {
                Text(pickup.name)
            }
        }
    }
}

@Composable
private fun DoneContent(state: HoldPlacementState) {
    val placed = state.placedHold
    when {
        state.confirming -> Progress(R.string.hold_place_confirming)
        placed != null -> {
            Text(stringResource(R.string.hold_place_done, displayTitle(placed.title)))
            Text(
                stringResource(R.string.hold_status_label, placed.status),
                style = MaterialTheme.typography.bodySmall
            )
            placed.pickupLocation?.let {
                Text(
                    stringResource(R.string.hold_pickup_label, it),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        else -> Text(stringResource(R.string.hold_place_done_pending))
    }
}

@Composable
private fun SectionLabel(textRes: Int) {
    Text(
        stringResource(textRes),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.secondary
    )
}

@Composable
private fun ChoiceRow(selected: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
                .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(modifier = Modifier.width(8.dp))
        content()
    }
}

@Composable
private fun Progress(textRes: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(24.dp))
        Spacer(modifier = Modifier.width(12.dp))
        Text(stringResource(textRes), style = MaterialTheme.typography.bodyMedium)
    }
}
