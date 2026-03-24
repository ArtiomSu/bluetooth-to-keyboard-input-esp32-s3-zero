package com.terminal_heat_sink.bluetoothtokeyboardinput.plugin

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import keepass2android.pluginsdk.Strings

/**
 * One-time setup activity launched from the home screen.
 *
 * Grants runtime BLE/notification permissions, then offers a shortcut to
 * KeePass2Android's plugin settings page so the user can enable the plugin
 * there (K2A authorization is required before any buttons appear in entries).
 */
class SetupActivity : AppCompatActivity() {

    private val blePermissions: Array<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            add(Manifest.permission.BLUETOOTH)
            @Suppress("DEPRECATION")
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            showReady()
        } else {
            showPermissionDenied()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (allPermissionsGranted()) {
            showReady()
        } else {
            permissionLauncher.launch(blePermissions)
        }
    }

    private fun allPermissionsGranted() = blePermissions.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun showReady() {
        MaterialAlertDialogBuilder(this)
            .setTitle("BtoKB Plugin — permissions granted")
            .setMessage(
                "Step 2: Enable the plugin in KeePass2Android.\n\n" +
                "Tap \"Open K2A Settings\" below, then find \"BtoKB Plugin\" " +
                "in the plugin list and tap Allow.\n\n" +
                "After that, open any KeePass entry and you will see " +
                "\"BtoKB ✓ Test\" in the entry options menu."
            )
            .setPositiveButton("Open K2A Settings") { _, _ -> openKp2aPluginSettings() }
            .setNegativeButton("Close") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun showPermissionDenied() {
        MaterialAlertDialogBuilder(this)
            .setTitle("BtoKB Plugin")
            .setMessage(
                "Bluetooth permission is required for the plugin to connect " +
                "to your ESP32-KB device.\n\nPlease grant it in Settings → Apps → BtoKB Plugin → Permissions."
            )
            .setPositiveButton("OK") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    /**
     * Opens KeePass2Android's built-in plugin management screen directly.
     * K2A exposes [Strings.ACTION_EDIT_PLUGIN_SETTINGS] for exactly this purpose.
     */
    private fun openKp2aPluginSettings() {
        val intent = Intent(Strings.ACTION_EDIT_PLUGIN_SETTINGS).apply {
            putExtra(Strings.EXTRA_PLUGIN_PACKAGE, packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        // K2A is published as two APKs (with/without network access).
        val kp2aPackages = listOf(
            "keepass2android.keepass2android",
            "keepass2android.keepass2android_nonet",
        )
        val launched = kp2aPackages.any { pkg ->
            try {
                intent.setPackage(pkg)
                startActivity(intent)
                true
            } catch (_: ActivityNotFoundException) { false }
        }
        if (!launched) {
            Toast.makeText(this, "KeePass2Android not found — install it first", Toast.LENGTH_LONG).show()
        }
        finish()
    }
}

