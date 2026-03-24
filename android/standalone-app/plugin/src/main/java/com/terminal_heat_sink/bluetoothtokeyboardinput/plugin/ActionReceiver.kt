package com.terminal_heat_sink.bluetoothtokeyboardinput.plugin

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import keepass2android.pluginsdk.KeepassDefs
import keepass2android.pluginsdk.PluginAccessException
import keepass2android.pluginsdk.PluginActionBroadcastReceiver
import com.terminal_heat_sink.bluetoothtokeyboardinput.data.DeviceRepository
import com.terminal_heat_sink.bluetoothtokeyboardinput.plugin.R

/**
 * Handles two broadcasts from KeePass2Android:
 *
 * 1. ACTION_OPEN_ENTRY — K2A just opened an entry.  We add "Type with BtoKB"
 *    action buttons to the entry's options menu.  One button types the
 *    credentials without pressing Enter; the other appends Enter.
 *
 * 2. ACTION_ENTRY_ACTION_SELECTED — the user tapped one of our buttons.
 *    We start [BleService] which does the actual BLE scan → connect → type
 *    work in the background.
 */
class ActionReceiver : PluginActionBroadcastReceiver() {

    private companion object {
        const val TAG = "BtoKBPlugin"
        /** Marker put into the action Bundle to identify the no-op test action. */
        const val ACTION_TEST = "btokb_test"
    }

    // ── Entry opened ──────────────────────────────────────────────────────

    override fun openEntry(oe: OpenEntryAction) {
        val ctx = oe.context
        Log.d(TAG, "openEntry called from ${oe.hostPackage}")

        val token = try {
            oe.getAccessTokenForCurrentEntryScope()
        } catch (e: PluginAccessException) {
            Log.w(TAG, "Not authorized in K2A yet", e)
            Toast.makeText(
                ctx,
                "BtoKB Plugin: open the BtoKB Plugin app and then enable it in K2A Settings → Plugins",
                Toast.LENGTH_LONG,
            ).show()
            return
        }

        // ── Test button — always shown so connectivity can be verified ────
        oe.addEntryAction(
            "BtoKB ✓ Test (tap to confirm plugin works)",
            R.drawable.ic_btokb,
            Bundle().apply { putString(BleService.EXTRA_DEVICE_ALIAS, ACTION_TEST) },
            token,
        )

        // ── Device buttons ─────────────────────────────────────────────────
        val devices = DeviceRepository(ctx).getAll()

        if (devices.isEmpty()) {
            oe.addEntryAction(
                "BtoKB: No devices — open BtoKB Plugin app to configure",
                R.drawable.ic_btokb,
                Bundle(),
                token,
            )
            return
        }

        for (device in devices) {
            val bundleType = Bundle().apply {
                putString(BleService.EXTRA_DEVICE_ALIAS, device.alias)
                putBoolean(BleService.EXTRA_SUBMIT, false)
            }
            oe.addEntryAction(
                "BtoKB: Type [${device.alias}]",
                R.drawable.ic_btokb,
                bundleType,
                token,
            )

            val bundleTypeEnter = Bundle().apply {
                putString(BleService.EXTRA_DEVICE_ALIAS, device.alias)
                putBoolean(BleService.EXTRA_SUBMIT, true)
            }
            oe.addEntryAction(
                "BtoKB: Type+Enter [${device.alias}]",
                R.drawable.ic_btokb,
                bundleTypeEnter,
                token,
            )
        }
    }

    // ── User tapped one of our actions ────────────────────────────────────

    override fun actionSelected(actionSelected: ActionSelectedAction) {
        // We only add entry-level actions (not per-field), so ignore field actions.
        if (!actionSelected.isEntryAction) return

        val data = actionSelected.actionData ?: return
        val alias = data.getString(BleService.EXTRA_DEVICE_ALIAS) ?: return

        // Test action — just confirm the plugin is alive, no BLE work.
        if (alias == ACTION_TEST) {
            Log.d(TAG, "test action tapped")
            Toast.makeText(
                actionSelected.context,
                "BtoKB Plugin is working!",
                Toast.LENGTH_SHORT,
            ).show()
            return
        }

        val submit = data.getBoolean(BleService.EXTRA_SUBMIT, false)

        val fields = actionSelected.entryFields
        val userName = fields[KeepassDefs.UserNameField] ?: ""
        val password = fields[KeepassDefs.PasswordField] ?: ""

        val intent = Intent(actionSelected.context, BleService::class.java).apply {
            putExtra(BleService.EXTRA_DEVICE_ALIAS, alias)
            putExtra(BleService.EXTRA_USERNAME, userName)
            putExtra(BleService.EXTRA_PASSWORD, password)
            putExtra(BleService.EXTRA_SUBMIT, submit)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            actionSelected.context.startForegroundService(intent)
        } else {
            actionSelected.context.startService(intent)
        }
    }
}
