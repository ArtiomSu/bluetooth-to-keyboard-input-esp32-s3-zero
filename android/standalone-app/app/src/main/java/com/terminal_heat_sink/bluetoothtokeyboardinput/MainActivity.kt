package com.terminal_heat_sink.bluetoothtokeyboardinput

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.terminal_heat_sink.bluetoothtokeyboardinput.ui.AppNavigation
import com.terminal_heat_sink.bluetoothtokeyboardinput.ui.theme.BluetoothToKeyboardInputTheme
import com.terminal_heat_sink.bluetoothtokeyboardinput.ui.viewmodel.BleViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: BleViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val shareText = intent.takeIf { it?.action == Intent.ACTION_SEND }
            ?.getStringExtra(Intent.EXTRA_TEXT)
        setContent {
            BluetoothToKeyboardInputTheme {
                AppNavigation(viewModel = viewModel, shareIntent = shareText)
            }
        }
    }
}
