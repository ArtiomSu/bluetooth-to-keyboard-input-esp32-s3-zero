package com.terminal_heat_sink.bluetoothtokeyboardinput.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.terminal_heat_sink.bluetoothtokeyboardinput.ble.BleConstants
import com.terminal_heat_sink.bluetoothtokeyboardinput.ui.viewmodel.BleViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: BleViewModel,
    onProvisionClick: () -> Unit,
    onFactoryReset: () -> Unit,
) {
    val currentLayout    by viewModel.layout.collectAsState()
    val currentOs        by viewModel.targetOs.collectAsState()
    val mouseEnabled     by viewModel.mouseEnabled.collectAsState()
    val firmwareVersion  by viewModel.firmwareVersion.collectAsState()
    val statusMessage    by viewModel.statusMessage.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Seed from the active device's saved values; re-reads on every screen entry after reconnect.
    var minGap  by remember { mutableFloatStateOf(viewModel.activeDevice?.minGapMs?.toFloat()  ?: 20f) }
    var maxGap  by remember { mutableFloatStateOf(viewModel.activeDevice?.maxGapMs?.toFloat()  ?: 20f) }
    var minHold by remember { mutableFloatStateOf(viewModel.activeDevice?.minHoldMs?.toFloat() ?: 20f) }
    var maxHold by remember { mutableFloatStateOf(viewModel.activeDevice?.maxHoldMs?.toFloat() ?: 20f) }
    var showResetConfirm by remember { mutableStateOf(false) }
    var showMouseConfirm by remember { mutableStateOf(false) }
    var pendingMouseEnabled by remember { mutableStateOf(false) }

    LaunchedEffect(statusMessage) {
        statusMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearStatus()
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Settings") }) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(Modifier.height(16.dp))

            Text("Keyboard Layout", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(4.dp))
            DropdownPicker(
                options = BleConstants.SUPPORTED_LAYOUTS,
                selected = currentLayout,
                onSelected = { viewModel.setLayout(it) },
                label = "Layout",
            )

            Spacer(Modifier.height(16.dp))

            Text("Target OS", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(4.dp))
            DropdownPicker(
                options = BleConstants.SUPPORTED_OS,
                selected = currentOs,
                onSelected = { viewModel.setTargetOs(it) },
                label = "OS",
            )

            Spacer(Modifier.height(24.dp))

            Text("Keystroke Delays", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(8.dp))

            SliderRow("Min gap (ms)", minGap, 0f..200f) { minGap = it }
            SliderRow("Max gap (ms)", maxGap, 0f..200f) { maxGap = it }
            SliderRow("Min hold (ms)", minHold, 0f..200f) { minHold = it }
            SliderRow("Max hold (ms)", maxHold, 0f..200f) { maxHold = it }

            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    viewModel.setDelay(
                        minHold.toInt(), maxHold.toInt(),
                        minGap.toInt(), maxGap.toInt(),
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Apply Delays")
            }

            Spacer(Modifier.height(24.dp))

            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            Text("Mouse Support", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Enable USB Mouse")
                    Text(
                        "Toggling restarts the device",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = mouseEnabled,
                    onCheckedChange = { newValue ->
                        pendingMouseEnabled = newValue
                        showMouseConfirm = true
                    },
                )
            }

            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            if (firmwareVersion.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Firmware Version", style = MaterialTheme.typography.labelLarge)
                    Text(firmwareVersion, style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(16.dp))
            }

            Button(
                onClick = onProvisionClick,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Provision Device…")
            }

            Spacer(Modifier.height(8.dp))

            OutlinedButton(
                onClick = { showResetConfirm = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
                border = androidx.compose.foundation.BorderStroke(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.error,
                ),
            ) {
                Text("Factory Reset Device…")
            }

            Spacer(Modifier.height(16.dp))
        }
    }

    if (showMouseConfirm) {
        AlertDialog(
            onDismissRequest = { showMouseConfirm = false },
            title = { Text(if (pendingMouseEnabled) "Enable Mouse?" else "Disable Mouse?") },
            text = {
                Text(
                    if (pendingMouseEnabled)
                        "This will add USB mouse support to the device. It will restart automatically."
                    else
                        "This will remove USB mouse support. The device will restart automatically."
                )
            },
            confirmButton = {
                Button(onClick = {
                    showMouseConfirm = false
                    viewModel.setMouseEnabled(pendingMouseEnabled)
                }) { Text("Restart & Apply") }
            },
            dismissButton = {
                TextButton(onClick = { showMouseConfirm = false }) { Text("Cancel") }
            },
        )
    }

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text("Factory Reset?") },
            text = {
                Text(
                    "This will erase the device name and PSK, reverting it to factory defaults. " +
                    "The device will reboot and you will need to re-provision it before use."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showResetConfirm = false
                        viewModel.factoryReset(onComplete = onFactoryReset)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text("Reset")
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) { Text("Cancel") }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DropdownPicker(
    options: List<String>,
    selected: String,
    onSelected: (String) -> Unit,
    label: String,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        onSelected(option)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun SliderRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChanged: (Float) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Text("${value.toInt()} ms")
    }
    Slider(
        value = value,
        onValueChange = onChanged,
        valueRange = range,
        steps = (range.endInclusive - range.start).toInt() - 1,
        modifier = Modifier.fillMaxWidth(),
    )
}
