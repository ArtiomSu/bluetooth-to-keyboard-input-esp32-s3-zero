package com.terminal_heat_sink.bluetoothtokeyboardinput.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.terminal_heat_sink.bluetoothtokeyboardinput.ui.viewmodel.BleViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScriptScreen(viewModel: BleViewModel) {
    val context = LocalContext.current
    val repo = viewModel.scriptRepository

    // Editing state hoisted here so rememberSaveable survives rotation
    var isEditing        by rememberSaveable { mutableStateOf(false) }
    // "" sentinel = new script with no original file to rename/delete
    var editOriginalName by rememberSaveable { mutableStateOf("") }
    var editName         by rememberSaveable { mutableStateOf("") }
    var editContent      by rememberSaveable { mutableStateOf("") }

    // Reload from disk each time; not saveable since it's just a cache of the filesystem
    var scriptList by remember { mutableStateOf(repo.getAll()) }

    val isBusy           by viewModel.isBusy.collectAsState()
    val isScriptRunning  by viewModel.isScriptRunning.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(statusMessage) {
        statusMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearStatus()
        }
    }

    fun openEditor(originalName: String, name: String, content: String) {
        editOriginalName = originalName
        editName = name
        editContent = content
        isEditing = true
    }

    fun saveScript() {
        // Sanitise: strip filesystem-unsafe characters
        val safeName = editName.trim().replace(Regex("[/\\\\:*?\"<>|]"), "_")
        if (safeName.isBlank()) return
        val orig = editOriginalName.ifEmpty { null }
        if (orig != null && orig != safeName) repo.delete(orig)
        repo.save(safeName, editContent)
        editOriginalName = safeName
        editName = safeName
        scriptList = repo.getAll()
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        try {
            val content = context.contentResolver.openInputStream(uri)
                ?.bufferedReader()?.readText() ?: return@rememberLauncherForActivityResult
            // Resolve the human-readable filename via OpenableColumns
            val displayName = context.contentResolver
                .query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst())
                        cursor.getString(cursor.getColumnIndexOrThrow(android.provider.OpenableColumns.DISPLAY_NAME))
                    else null
                } ?: uri.lastPathSegment ?: ""
            val suggested = displayName
                .substringAfterLast("/")
                .removeSuffix(".txt").removeSuffix(".TXT")
                .ifBlank { "imported" }
            openEditor("", suggested, content)
        } catch (_: Exception) { /* unreadable file — ignore */ }
    }

    fun shareScript(name: String, content: String) {
        context.startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, name)
                    putExtra(Intent.EXTRA_TEXT, content)
                },
                "Export script"
            )
        )
    }

    if (isEditing) {
        ScriptEditScreen(
            name = editName,
            onNameChange = { editName = it },
            content = editContent,
            onContentChange = { editContent = it },
            isBusy = isBusy,
            isScriptRunning = isScriptRunning,
            snackbarHostState = snackbarHostState,
            onBack = {
                scriptList = repo.getAll()
                isEditing = false
            },
            onSave = { saveScript() },
            onRun = { viewModel.runScript(editContent) },
            onStop = { viewModel.stopScript() },
            onExport = { shareScript(editName.ifBlank { "script" }, editContent) },
        )
    } else {
        ScriptListScreen(
            scripts = scriptList,
            isBusy = isBusy,
            isScriptRunning = isScriptRunning,
            snackbarHostState = snackbarHostState,
            onNewScript    = { openEditor("", "", "") },
            onEditScript   = { name -> openEditor(name, name, repo.load(name)) },
            onRunScript    = { name -> viewModel.runScript(repo.load(name)) },
            onStopScript   = { viewModel.stopScript() },
            onDeleteScript = { name ->
                repo.delete(name)
                scriptList = repo.getAll()
            },
            onExportScript = { name -> shareScript(name, repo.load(name)) },
            onImport = { importLauncher.launch(arrayOf("text/plain", "*/*")) },
        )
    }
}

// ── List screen ───────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScriptListScreen(
    scripts: List<String>,
    isBusy: Boolean,
    isScriptRunning: Boolean,
    snackbarHostState: SnackbarHostState,
    onNewScript: () -> Unit,
    onEditScript: (String) -> Unit,
    onRunScript: (String) -> Unit,
    onStopScript: () -> Unit,
    onDeleteScript: (String) -> Unit,
    onExportScript: (String) -> Unit,
    onImport: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Scripts") },
                actions = {
                    TextButton(onClick = onImport) { Text("Import .txt") }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onNewScript) {
                Icon(Icons.Default.Add, contentDescription = "New script")
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        if (scripts.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(24.dp),
                ) {
                    Text("No saved scripts", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Tap + to write a new script, or use Import to load a .txt file.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(scripts, key = { it }) { name ->
                    ScriptListItem(
                        name = name,
                        isBusy = isBusy,
                        isScriptRunning = isScriptRunning,
                        onEdit   = { onEditScript(name) },
                        onRun    = { onRunScript(name) },
                        onStop   = onStopScript,
                        onExport = { onExportScript(name) },
                        onDelete = { onDeleteScript(name) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun ScriptListItem(
    name: String,
    isBusy: Boolean,
    isScriptRunning: Boolean,
    onEdit: () -> Unit,
    onRun: () -> Unit,
    onStop: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(name) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isScriptRunning) {
                    IconButton(onClick = onStop) {
                        Icon(
                            Icons.Default.Stop,
                            contentDescription = "Stop script",
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                } else {
                    IconButton(onClick = onRun, enabled = !isBusy) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Run $name")
                    }
                }
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More options for $name")
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Edit") },
                            leadingIcon = { Icon(Icons.Default.Edit, null) },
                            onClick = { menuExpanded = false; onEdit() },
                        )
                        DropdownMenuItem(
                            text = { Text("Export") },
                            leadingIcon = { Icon(Icons.Default.Share, null) },
                            onClick = { menuExpanded = false; onExport() },
                        )
                        DropdownMenuItem(
                            text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.Delete,
                                    null,
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            },
                            onClick = { menuExpanded = false; onDelete() },
                        )
                    }
                }
            }
        },
        modifier = Modifier.clickable(onClick = onEdit),
    )
}

// ── Edit screen ───────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScriptEditScreen(
    name: String,
    onNameChange: (String) -> Unit,
    content: String,
    onContentChange: (String) -> Unit,
    isBusy: Boolean,
    isScriptRunning: Boolean,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onSave: () -> Unit,
    onRun: () -> Unit,
    onStop: () -> Unit,
    onExport: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(name.ifBlank { "New Script" }) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onExport, enabled = content.isNotBlank()) {
                        Icon(Icons.Default.Share, contentDescription = "Export")
                    }
                    IconButton(onClick = onSave, enabled = name.isNotBlank()) {
                        Icon(Icons.Default.Check, contentDescription = "Save")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = name,
                onValueChange = onNameChange,
                label = { Text("Script name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = content,
                onValueChange = onContentChange,
                label = { Text("Script (Ducky-style)") },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                placeholder = {
                    Text(
                        "REM Example\nSTRING Hello, World!\nENTER",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                    )
                },
                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                maxLines = Int.MAX_VALUE,
            )
            Spacer(Modifier.height(12.dp))
            if (isScriptRunning) {
                Button(
                    onClick = onStop,
                    modifier = Modifier.fillMaxWidth(),
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Icon(Icons.Default.Stop, null, modifier = Modifier.padding(end = 8.dp))
                    Text("Stop Script")
                }
            } else {
                Button(
                    onClick = onRun,
                    enabled = content.isNotBlank() && !isBusy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.PlayArrow, null, modifier = Modifier.padding(end = 8.dp))
                    Text(if (isBusy) "Running…" else "Run Script")
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

