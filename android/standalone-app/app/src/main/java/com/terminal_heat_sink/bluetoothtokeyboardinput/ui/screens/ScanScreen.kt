package com.terminal_heat_sink.bluetoothtokeyboardinput.ui.screens

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.terminal_heat_sink.bluetoothtokeyboardinput.ble.ConnectionState
import com.terminal_heat_sink.bluetoothtokeyboardinput.ble.ScannedDevice
import com.terminal_heat_sink.bluetoothtokeyboardinput.ui.viewmodel.BleViewModel

private fun requiredPermissions() =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    else
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

private fun Context.allPermissionsGranted() =
    requiredPermissions().all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

private fun Context.isBluetoothEnabled(): Boolean {
    val bm = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    return bm?.adapter?.isEnabled == true
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanScreen(
    viewModel: BleViewModel,
    onConnected: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val connectionState by viewModel.connectionState.collectAsState()
    val statusMessage   by viewModel.statusMessage.collectAsState()
    val isBusy          by viewModel.isBusy.collectAsState()
    val pendingConfig   by viewModel.pendingConfig.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        when {
            results.all { it.value } -> viewModel.startScan()
            else -> viewModel.setPermissionDenied()
        }
    }

    // If permissions are already granted, start immediately; otherwise request them.
    LaunchedEffect(Unit) {
        if (context.allPermissionsGranted()) {
            viewModel.startScan()
        } else {
            permissionLauncher.launch(requiredPermissions())
        }
    }

    DisposableEffect(Unit) {
        onDispose { viewModel.stopScan() }
    }

    LaunchedEffect(connectionState) {
        if (connectionState is ConnectionState.Connected) onConnected()
    }

    // Auto-connect when scanning for a specific saved device and it appears in results.
    LaunchedEffect(connectionState, pendingConfig) {
        val config = pendingConfig ?: return@LaunchedEffect
        if (connectionState is ConnectionState.ScanResult) {
            val match = (connectionState as ConnectionState.ScanResult).devices
                .firstOrNull { it.name == config.bleName }
            if (match != null) {
                viewModel.stopScan()
                viewModel.connectToDevice(match.device, config)
            }
        }
    }

    LaunchedEffect(statusMessage) {
        statusMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearStatus()
        }
    }

    val title = pendingConfig?.let { "Connect to ${it.alias}" } ?: "Scan for Devices"

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = {
                        viewModel.stopScan()
                        onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        when {
            isBusy || connectionState is ConnectionState.Connecting -> {
                CenteredSpinner(padding, pendingConfig?.bleName?.let { "Connecting to $it…" })
            }
            connectionState is ConnectionState.Scanning -> {
                CenteredSpinner(padding, pendingConfig?.bleName?.let { "Looking for $it…" })
            }
            connectionState is ConnectionState.ScanResult -> {
                val allDevices = (connectionState as ConnectionState.ScanResult).devices
                // If we're reconnecting to a specific device, only show that one in the list
                // (auto-connect LaunchedEffect above handles it, but show it so user can tap too)
                val devices = if (pendingConfig != null) {
                    allDevices.filter { it.name == pendingConfig?.bleName }
                } else {
                    allDevices
                }
                if (devices.isEmpty()) {
                    NoDevicesFound(
                        padding,
                        message = pendingConfig?.bleName?.let { "\"$it\" not found. Make sure it's powered on." },
                        onRescan = { viewModel.startScan() },
                    )
                } else {
                    LazyColumn(
                        Modifier
                            .fillMaxSize()
                            .padding(padding)
                    ) {
                        items(devices, key = { it.address }) { scanned ->
                            ScanResultRow(scanned) {
                                viewModel.stopScan()
                                // Use saved config for this device if one is pending, else default PSK
                                val config = pendingConfig
                                if (config != null) {
                                    viewModel.connectToDevice(scanned.device, config)
                                } else {
                                    viewModel.connectToDefaultDevice(scanned.device)
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
            connectionState is ConnectionState.Error -> {
                val msg = (connectionState as ConnectionState.Error).message
                ErrorState(padding, msg, onRetry = {
                    if (context.allPermissionsGranted()) viewModel.startScan()
                    else permissionLauncher.launch(requiredPermissions())
                })
            }
            else -> {
                // Permissions denied / Bluetooth off / initial state
                PermissionOrBluetoothPrompt(
                    padding,
                    bluetoothOff = !context.isBluetoothEnabled(),
                    onRequestPermissions = { permissionLauncher.launch(requiredPermissions()) },
                )
            }
        }
    }
}

@Composable
private fun CenteredSpinner(
    padding: androidx.compose.foundation.layout.PaddingValues,
    label: String? = null,
) {
    Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text(label ?: "Scanning…", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun NoDevicesFound(
    padding: androidx.compose.foundation.layout.PaddingValues,
    message: String? = null,
    onRescan: () -> Unit,
) {
    Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
            Text("No devices found", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                message ?: "Make sure the ESP32 is powered on and advertising.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = onRescan, modifier = Modifier.fillMaxWidth()) { Text("Scan Again") }
        }
    }
}

@Composable
private fun ErrorState(
    padding: androidx.compose.foundation.layout.PaddingValues,
    message: String,
    onRetry: () -> Unit,
) {
    Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
            Text("Error", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(8.dp))
            Text(message, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(16.dp))
            Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text("Retry") }
        }
    }
}

@Composable
private fun PermissionOrBluetoothPrompt(
    padding: androidx.compose.foundation.layout.PaddingValues,
    bluetoothOff: Boolean,
    onRequestPermissions: () -> Unit,
) {
    val context = LocalContext.current
    Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(24.dp),
        ) {
            if (bluetoothOff) {
                Text("Bluetooth is off", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Enable Bluetooth to scan for devices.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Open Bluetooth Settings") }
            } else {
                Text("Bluetooth permissions needed", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Grant Bluetooth permission to scan for devices.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = onRequestPermissions, modifier = Modifier.fillMaxWidth()) {
                    Text("Grant Permission")
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = android.net.Uri.fromParts("package", context.packageName, null)
                        }
                        context.startActivity(intent)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Open App Settings") }
            }
        }
    }
}

@Composable
private fun ScanResultRow(device: ScannedDevice, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(device.name) },
        supportingContent = { Text(device.address) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

