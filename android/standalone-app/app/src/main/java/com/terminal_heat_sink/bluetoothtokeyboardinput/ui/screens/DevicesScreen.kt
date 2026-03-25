package com.terminal_heat_sink.bluetoothtokeyboardinput.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.terminal_heat_sink.bluetoothtokeyboardinput.ble.BleConstants
import com.terminal_heat_sink.bluetoothtokeyboardinput.data.DeviceConfig
import com.terminal_heat_sink.bluetoothtokeyboardinput.ui.viewmodel.BleViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesScreen(
    viewModel: BleViewModel,
    onScanClick: () -> Unit,
    onDeviceConnect: () -> Unit,
) {
    val devices = remember { viewModel.repository.getAll() }
    var deviceList by remember { mutableStateOf(devices) }

    // Clone dialog state
    var cloneSource by remember { mutableStateOf<DeviceConfig?>(null) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Bluetooth Input") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                viewModel.selectDeviceConfig(null)
                onScanClick()
            }) {
                Icon(Icons.Default.Add, contentDescription = "Scan for devices")
            }
        }
    ) { padding ->
        if (deviceList.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("No saved devices", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Tap + to scan for a device",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                item {
                    // Default / unprovisioned device row
                    SavedDeviceRow(
                        device = viewModel.repository.getDefaultDevice(),
                        onConnect = {
                            viewModel.selectDeviceConfig(null) // will use default PSK
                            onScanClick()
                        },
                        onDelete = null,
                        onClone = null, // default device can't be cloned (no PSK stored)
                    )
                    HorizontalDivider()
                }
                items(deviceList, key = { it.alias }) { config ->
                    SavedDeviceRow(
                        device = config,
                        onConnect = {
                            viewModel.selectDeviceConfig(config)
                            onScanClick()
                        },
                        onDelete = {
                            viewModel.repository.delete(config.alias)
                            deviceList = viewModel.repository.getAll()
                        },
                        onClone = { cloneSource = config },
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    // Clone dialog — shown when cloneSource is set
    cloneSource?.let { source ->
        CloneDeviceDialog(
            source = source,
            existingAliases = deviceList.map { it.alias }.toSet(),
            onConfirm = { newAlias, newLayout, newOs ->
                viewModel.repository.save(
                    source.copy(
                        alias = newAlias,
                        layout = newLayout,
                        targetOs = newOs,
                        firmwareVersion = null, // version will be refreshed on next connect
                    )
                )
                deviceList = viewModel.repository.getAll()
                cloneSource = null
            },
            onDismiss = { cloneSource = null },
        )
    }
}

@Composable
private fun SavedDeviceRow(
    device: DeviceConfig,
    onConnect: () -> Unit,
    onDelete: (() -> Unit)?,
    onClone: (() -> Unit)?,
) {
    ListItem(
        headlineContent = { Text(device.alias) },
        supportingContent = {
            val fw = if (!device.firmwareVersion.isNullOrEmpty()) "  •  fw ${device.firmwareVersion}" else ""
            Text("${device.bleName}  •  ${device.layout}  •  ${device.targetOs}$fw")
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onClone != null) {
                    IconButton(onClick = onClone) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Clone profile")
                    }
                }
                if (onDelete != null) {
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete")
                    }
                }
            }
        },
        modifier = Modifier.clickable(onClick = onConnect),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CloneDeviceDialog(
    source: DeviceConfig,
    existingAliases: Set<String>,
    onConfirm: (alias: String, layout: String, os: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var alias   by remember { mutableStateOf("${source.alias}-copy") }
    var layout  by remember { mutableStateOf(source.layout) }
    var os      by remember { mutableStateOf(source.targetOs) }

    val aliasError = when {
        alias.isBlank()              -> "Alias cannot be empty"
        alias in existingAliases     -> "An entry with this alias already exists"
        else                         -> null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Clone \"${source.alias}\"") },
        text = {
            Column {
                Text(
                    "Creates a new profile using the same device and PSK, " +
                    "but with a different layout and target OS.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = alias,
                    onValueChange = { alias = it },
                    label = { Text("New alias") },
                    singleLine = true,
                    isError = aliasError != null,
                    supportingText = aliasError?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Text("Layout", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(4.dp))
                ProfileDropdownPicker(
                    options = BleConstants.SUPPORTED_LAYOUTS,
                    selected = layout,
                    onSelected = { layout = it },
                    label = "Layout",
                )
                Spacer(Modifier.height(12.dp))
                Text("Target OS", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(4.dp))
                ProfileDropdownPicker(
                    options = BleConstants.SUPPORTED_OS,
                    selected = os,
                    onSelected = { os = it },
                    label = "OS",
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(alias.trim(), layout, os) },
                enabled = aliasError == null,
            ) { Text("Clone") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileDropdownPicker(
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
                    onClick = { onSelected(option); expanded = false },
                )
            }
        }
    }
}


