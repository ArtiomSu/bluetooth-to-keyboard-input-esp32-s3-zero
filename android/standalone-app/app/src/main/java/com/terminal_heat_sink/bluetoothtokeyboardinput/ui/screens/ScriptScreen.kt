package com.terminal_heat_sink.bluetoothtokeyboardinput.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.terminal_heat_sink.bluetoothtokeyboardinput.ui.viewmodel.BleViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScriptScreen(viewModel: BleViewModel) {
    var scriptText by rememberSaveable { mutableStateOf("") }
    val isBusy        by viewModel.isBusy.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(statusMessage) {
        statusMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearStatus()
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Script") }) },
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
                value = scriptText,
                onValueChange = { scriptText = it },
                label = { Text("Ducky-style script") },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                placeholder = {
                    Text(
                        "REM Example script\nSTRING Hello, World!\nENTER",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                    )
                },
                textStyle = androidx.compose.ui.text.TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                ),
                maxLines = Int.MAX_VALUE,
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { viewModel.runScript(scriptText) },
                enabled = scriptText.isNotBlank() && !isBusy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 8.dp),
                )
                Text(if (isBusy) "Running…" else "Run Script")
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
