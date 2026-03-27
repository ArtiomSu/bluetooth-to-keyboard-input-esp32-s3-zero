package com.terminal_heat_sink.bluetoothtokeyboardinput.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.terminal_heat_sink.bluetoothtokeyboardinput.ble.BleConstants
import com.terminal_heat_sink.bluetoothtokeyboardinput.data.DeviceConfig
import com.terminal_heat_sink.bluetoothtokeyboardinput.ui.viewmodel.BleViewModel
import kotlinx.coroutines.launch
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex
import androidx.compose.material.icons.filled.DragHandle
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesScreen(
    viewModel: BleViewModel,
    onScanClick: () -> Unit,
    onDeviceConnect: () -> Unit,
) {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val devices = remember { viewModel.repository.getAll() }
    var deviceList by remember { mutableStateOf(devices) }

    // Clone dialog state
    var cloneSource by remember { mutableStateOf<DeviceConfig?>(null) }

    // Drag-to-reorder state
    val lazyListState = rememberLazyListState()
    var draggingAlias by remember { mutableStateOf<String?>(null) }
    var draggingOffset by remember { mutableStateOf(0f) }

    // ── Export: system Save-file picker ──────────────────────────────────────
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val json = viewModel.repository.exportJson()
                context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
                snackbarHostState.showSnackbar("Devices exported successfully")
            } catch (e: Exception) {
                snackbarHostState.showSnackbar("Export failed: ${e.message}")
            }
        }
    }

    // ── Import: system Open-file picker ──────────────────────────────────────
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val json = context.contentResolver.openInputStream(uri)
                    ?.use { it.readBytes().toString(Charsets.UTF_8) }
                    ?: throw Exception("Could not read file")
                val count = viewModel.repository.importJson(json)
                if (count < 0) {
                    snackbarHostState.showSnackbar("Import failed: invalid JSON")
                } else {
                    deviceList = viewModel.repository.getAll()
                    snackbarHostState.showSnackbar("Imported $count device(s)")
                }
            } catch (e: Exception) {
                snackbarHostState.showSnackbar("Import failed: ${e.message}")
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Saved Devices") },
                actions = {
                    IconButton(onClick = { importLauncher.launch(arrayOf("application/json", "*/*")) }) {
                        Icon(Icons.Default.FileUpload, contentDescription = "Import devices")
                    }
                    IconButton(onClick = { exportLauncher.launch("bluetooth-input-devices.json") }) {
                        Icon(Icons.Default.FileDownload, contentDescription = "Export devices")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
                state = lazyListState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                // this allows easier connecting to an unprovisioned device, but might confuse users why its always there, so lets hide it for now.
                // item {
                //     // Default / unprovisioned device row
                //     SavedDeviceRow(
                //         device = viewModel.repository.getDefaultDevice(),
                //         onConnect = {
                //             viewModel.selectDeviceConfig(null) // will use default PSK
                //             onScanClick()
                //         },
                //         onDelete = null,
                //         onClone = null, // default device can't be cloned (no PSK stored)
                //     )
                //     HorizontalDivider()
                // }
                items(deviceList, key = { it.alias }) { config ->
                    val index = deviceList.indexOf(config)
                    val fromIdx = deviceList.indexOfFirst { it.alias == draggingAlias }
                    val isDragging = index == fromIdx && draggingAlias != null

                    // Average item height for threshold calculations (read from layout each frame).
                    val avgSize = lazyListState.layoutInfo.visibleItemsInfo
                        .takeIf { it.isNotEmpty() }
                        ?.map { it.size }?.average()?.toFloat() ?: 88f

                    // Prospective target index based on current drag offset.
                    val prospective = if (fromIdx >= 0 && avgSize > 0)
                        (fromIdx + (draggingOffset / avgSize).roundToInt())
                            .coerceIn(0, deviceList.size - 1)
                    else -1

                    // Visual y-offset: dragged item follows the finger; items in its path shift.
                    val visualOffset = when {
                        isDragging -> draggingOffset
                        fromIdx >= 0 && prospective > fromIdx && index in (fromIdx + 1)..prospective -> -avgSize
                        fromIdx >= 0 && prospective < fromIdx && index in prospective until fromIdx -> avgSize
                        else -> 0f
                    }

                    // Drag gesture attached to the drag-handle icon inside the row.
                    // Keyed on the stable alias so the coroutine survives recompositions
                    // that happen while draggingOffset changes.
                    val dragHandleModifier = Modifier.pointerInput(config.alias) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                draggingAlias = config.alias
                                draggingOffset = 0f
                            },
                            onDragEnd = {
                                val fIdx = deviceList.indexOfFirst { it.alias == draggingAlias }
                                if (fIdx >= 0) {
                                    val itemH = lazyListState.layoutInfo.visibleItemsInfo
                                        .firstOrNull { it.index == fIdx }?.size?.toFloat() ?: avgSize
                                    val tIdx = if (itemH > 0)
                                        (fIdx + (draggingOffset / itemH).roundToInt())
                                            .coerceIn(0, deviceList.size - 1)
                                    else fIdx
                                    if (fIdx != tIdx) {
                                        val newList = deviceList.toMutableList().also {
                                            it.add(tIdx, it.removeAt(fIdx))
                                        }
                                        deviceList = newList
                                        // Persist order: Gson deserialises the stored JSON into
                                        // a LinkedHashMap whose iteration order matches insertion
                                        // order. Deleting all entries then re-saving in the new
                                        // order means getAll() returns them in that order next
                                        // time the app starts.
                                        newList.forEach { viewModel.repository.delete(it.alias) }
                                        newList.forEach { viewModel.repository.save(it) }
                                    }
                                }
                                draggingAlias = null
                                draggingOffset = 0f
                            },
                            onDragCancel = {
                                draggingAlias = null
                                draggingOffset = 0f
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                draggingOffset += dragAmount.y
                            },
                        )
                    }

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
                        isDragging = isDragging,
                        dragHandleModifier = dragHandleModifier,
                        modifier = Modifier
                            .zIndex(if (isDragging) 1f else 0f)
                            .graphicsLayer { translationY = visualOffset },
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
    isDragging: Boolean = false,
    dragHandleModifier: Modifier = Modifier,
    modifier: Modifier = Modifier,
) {
    ListItem(
        headlineContent = { Text(device.alias) },
        supportingContent = {
            val fw = if (!device.firmwareVersion.isNullOrEmpty()) "  •  fw ${device.firmwareVersion}" else ""
            Text("${device.bleName}  •  ${device.layout}  •  ${device.targetOs}$fw")
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.DragHandle,
                    contentDescription = "Drag to reorder",
                    modifier = dragHandleModifier.padding(horizontal = 12.dp, vertical = 16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
        modifier = modifier.clickable(onClick = onConnect),
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


