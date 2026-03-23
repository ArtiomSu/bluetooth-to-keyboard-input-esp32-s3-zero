package com.terminal_heat_sink.bluetoothtokeyboardinput.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.terminal_heat_sink.bluetoothtokeyboardinput.ble.BleConstants
import com.terminal_heat_sink.bluetoothtokeyboardinput.ui.viewmodel.BleViewModel
import java.security.SecureRandom

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProvisionScreen(
    viewModel: BleViewModel,
    onBack: () -> Unit,
    onProvisioned: () -> Unit,
) {
    var newAlias        by rememberSaveable { mutableStateOf(viewModel.activeDevice?.alias   ?: "") }
    var newBleName      by rememberSaveable { mutableStateOf(viewModel.activeDevice?.bleName  ?: "") }
    var newPskHex       by rememberSaveable { mutableStateOf(viewModel.activeDevice?.pskHex   ?: generateRandomPskHex()) }
    var newVidHex       by rememberSaveable { mutableStateOf("%04X".format(viewModel.activeDevice?.usbVid ?: BleConstants.DEFAULT_USB_VID)) }
    var newPidHex       by rememberSaveable { mutableStateOf("%04X".format(viewModel.activeDevice?.usbPid ?: BleConstants.DEFAULT_USB_PID)) }
    var newManufacturer by rememberSaveable { mutableStateOf(viewModel.activeDevice?.usbManufacturerName ?: BleConstants.DEFAULT_USB_MANUFACTURER) }
    var newSerial       by rememberSaveable { mutableStateOf(viewModel.activeDevice?.usbSerialNumber    ?: BleConstants.DEFAULT_USB_SERIAL) }
    val isBusy        by viewModel.isBusy.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(statusMessage) {
        statusMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearStatus()
        }
    }

    val vidValid = newVidHex.length == 4 && newVidHex.all { it.isDigit() || it.uppercaseChar() in 'A'..'F' }
    val pidValid = newPidHex.length == 4 && newPidHex.all { it.isDigit() || it.uppercaseChar() in 'A'..'F' }
    val isValid = newAlias.isNotBlank()
        && newBleName.isNotBlank()
        && newPskHex.length == 64
        && newPskHex.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
        && vidValid && pidValid && newManufacturer.isNotBlank()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Provision Device") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
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

            Text(
                "Assign a unique name and PSK to the connected ESP32.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = newAlias,
                onValueChange = { newAlias = it },
                label = { Text("Alias (local name)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
            )

            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = newBleName,
                onValueChange = { newBleName = it },
                label = { Text("BLE device name (e.g. ESP32-KB-office)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
            )

            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = newPskHex,
                onValueChange = { newPskHex = it.lowercase().filter { c -> c.isLetterOrDigit() } },
                label = { Text("PSK (64 hex chars)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("${newPskHex.length}/64 characters") },
                isError = newPskHex.isNotEmpty() && newPskHex.length != 64,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
            )

            Spacer(Modifier.height(4.dp))
            TextButton(onClick = { newPskHex = generateRandomPskHex() }) {
                Text("Generate random PSK")
            }

            Spacer(Modifier.height(12.dp))
            Text("USB Identity", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(8.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = newVidHex,
                    onValueChange = { newVidHex = it.uppercase().filter { c -> c.isLetterOrDigit() }.take(4) },
                    label = { Text("VID (hex)") },
                    singleLine = true,
                    isError = newVidHex.isNotEmpty() && newVidHex.length != 4,
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                )
                OutlinedTextField(
                    value = newPidHex,
                    onValueChange = { newPidHex = it.uppercase().filter { c -> c.isLetterOrDigit() }.take(4) },
                    label = { Text("PID (hex)") },
                    singleLine = true,
                    isError = newPidHex.isNotEmpty() && newPidHex.length != 4,
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                )
            }

            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = newManufacturer,
                onValueChange = { newManufacturer = it.take(64) },
                label = { Text("Manufacturer name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
            )

            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = newSerial,
                onValueChange = { newSerial = it.take(64) },
                label = { Text("Serial number (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
            )

            Spacer(Modifier.height(16.dp))

            Button(
                onClick = {
                    val vid = newVidHex.toInt(16)
                    val pid = newPidHex.toInt(16)
                    viewModel.provision(newBleName, newPskHex, newAlias, vid, pid, newManufacturer, newSerial, onProvisioned)
                },
                enabled = isValid && !isBusy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (isBusy) "Provisioning…" else "Provision")
            }

            Spacer(Modifier.height(8.dp))

            Text(
                "The device will restart after provisioning and advertise under the new name.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(16.dp))
        }
    }
}

private fun generateRandomPskHex(): String {
    val bytes = ByteArray(32)
    SecureRandom().nextBytes(bytes)
    return bytes.joinToString("") { "%02x".format(it) }
}
