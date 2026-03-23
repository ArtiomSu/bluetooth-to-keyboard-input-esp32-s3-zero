package com.terminal_heat_sink.bluetoothtokeyboardinput

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.terminal_heat_sink.bluetoothtokeyboardinput.ui.AppNavigation
import com.terminal_heat_sink.bluetoothtokeyboardinput.ui.theme.BluetoothToKeyboardInputTheme
import com.terminal_heat_sink.bluetoothtokeyboardinput.ui.viewmodel.BleViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: BleViewModel by viewModels()

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* granted or denied — notification is optional, no further action needed */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Ask for notification permission on Android 13+. The connection notification
        // is a nice-to-have, so we request it silently without extra rationale UI.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        val shareText = intent.takeIf { it?.action == Intent.ACTION_SEND }
            ?.getStringExtra(Intent.EXTRA_TEXT)
        setContent {
            BluetoothToKeyboardInputTheme {
                AppNavigation(viewModel = viewModel, shareIntent = shareText)
            }
        }
    }
}
