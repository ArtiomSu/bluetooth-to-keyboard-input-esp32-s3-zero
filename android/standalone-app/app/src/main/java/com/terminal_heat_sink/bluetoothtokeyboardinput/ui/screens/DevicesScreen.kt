package com.terminal_heat_sink.bluetoothtokeyboardinput.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
                        }
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun SavedDeviceRow(
    device: DeviceConfig,
    onConnect: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    ListItem(
        headlineContent = { Text(device.alias) },
        supportingContent = {
            Text("${device.bleName}  •  ${device.layout}  •  ${device.targetOs}")
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
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


