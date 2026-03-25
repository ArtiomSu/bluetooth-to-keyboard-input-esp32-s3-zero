package com.terminal_heat_sink.bluetoothtokeyboardinput.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import com.terminal_heat_sink.bluetoothtokeyboardinput.ui.viewmodel.BleViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SendScreen(
    viewModel: BleViewModel,
    onDisconnect: () -> Unit,
) {
    val pendingShareText by viewModel.pendingShareText.collectAsState()
    var text by rememberSaveable { mutableStateOf(pendingShareText ?: "") }
    val isBusy          by viewModel.isBusy.collectAsState()
    val statusMessage   by viewModel.statusMessage.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val clipboardManager = LocalClipboardManager.current
    val view = LocalView.current
    // Clipboard access on Android 10+ is only allowed once the window has focus.
    // ON_RESUME fires before focus is restored, so we use OnWindowFocusChangeListener
    // which fires after the window is fully foregrounded and clipboard reads succeed.
    var clipText by remember { mutableStateOf(clipboardManager.getText()?.text ?: "") }
    DisposableEffect(view) {
        val focusListener = android.view.ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
            if (hasFocus) {
                clipText = clipboardManager.getText()?.text ?: ""
            }
        }
        view.viewTreeObserver.addOnWindowFocusChangeListener(focusListener)
        onDispose { view.viewTreeObserver.removeOnWindowFocusChangeListener(focusListener) }
    }
    val clipPreview = when {
        clipText.isEmpty()     -> "empty"
        clipText.length <= 3   -> clipText
        else                   -> clipText.take(3) + "…"
    }

    // Auto-send when share text arrives (both cold start and singleTask re-delivery)
    LaunchedEffect(pendingShareText) {
        if (!pendingShareText.isNullOrBlank()) {
            text = pendingShareText!!  // update text field so the user sees what was shared
            viewModel.sendText(pendingShareText!!)
            viewModel.clearPendingShareText()
        }
    }

    LaunchedEffect(statusMessage) {
        statusMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearStatus()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Send Text") },
                actions = {
                    TextButton(onClick = onDisconnect) { Text("Disconnect") }
                }
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
                value = text,
                onValueChange = { text = it },
                label = { Text("Text to type") },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                placeholder = { Text("Enter text here…") },
                trailingIcon = {
                    if (text.isNotEmpty()) {
                        IconButton(onClick = { text = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear")
                        }
                    }
                },
                maxLines = 20,
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { viewModel.sendText(text) },
                enabled = text.isNotBlank() && !isBusy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 8.dp),
                )
                Text(if (isBusy) "Sending…" else "Send")
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { viewModel.sendText(text + "\n") },
                enabled = text.isNotBlank() && !isBusy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (isBusy) "Sending…" else "Send + Enter")
            }
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = {
                        val clip = clipboardManager.getText()?.text ?: ""
                        viewModel.sendText(clip)
                    },
                    enabled = clipText.isNotBlank() && !isBusy,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        Icons.Default.ContentPaste,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 4.dp),
                    )
                    Text("\"$clipPreview\"")
                }
                Button(
                    onClick = {
                        val clip = clipboardManager.getText()?.text ?: ""
                        viewModel.sendText(clip + "\n")
                    },
                    enabled = clipText.isNotBlank() && !isBusy,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        Icons.Default.ContentPaste,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 4.dp),
                    )
                    Text("\"$clipPreview\" + ↵")
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
