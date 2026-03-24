package com.terminal_heat_sink.bluetoothtokeyboardinput.plugin

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.terminal_heat_sink.bluetoothtokeyboardinput.ble.BleConstants
import com.terminal_heat_sink.bluetoothtokeyboardinput.ble.BleManager
import com.terminal_heat_sink.bluetoothtokeyboardinput.ble.ConnectionState
import com.terminal_heat_sink.bluetoothtokeyboardinput.crypto.CryptoManager
import com.terminal_heat_sink.bluetoothtokeyboardinput.data.DeviceRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Foreground service that scans for a saved BLE device, connects to it, and
 * types the KeePass credentials.
 *
 * Android 8+: cannot do long-running BLE work in a BroadcastReceiver, so
 * [ActionReceiver] delegates to this service via startForegroundService().
 */
class BleService : Service() {

    companion object {
        const val EXTRA_DEVICE_ALIAS = "btokb_device_alias"
        const val EXTRA_USERNAME     = "btokb_username"
        const val EXTRA_PASSWORD     = "btokb_password"
        const val EXTRA_SUBMIT       = "btokb_submit"

        private const val CHANNEL_ID       = "btokb_plugin_ble"
        private const val NOTIFICATION_ID  = 9742
        private const val SCAN_TIMEOUT_MS  = 15_000L
        private const val TAG              = "BtoKBPlugin"
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var ble: BleManager
    private var typingJob: Job? = null

    // ── Lifecycle ─────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        ble = BleManager(applicationContext)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val alias    = intent?.getStringExtra(EXTRA_DEVICE_ALIAS) ?: run { stopSelf(); return START_NOT_STICKY }
        val userName = intent.getStringExtra(EXTRA_USERNAME) ?: ""
        val password = intent.getStringExtra(EXTRA_PASSWORD) ?: ""
        val submit   = intent.getBooleanExtra(EXTRA_SUBMIT, false)

        // Promote to foreground immediately so the receiver's deadline is not exceeded.
        startForegroundCompat(buildNotification("Connecting to $alias…"))

        // Cancel any previous typing job and start a fresh one.
        typingJob?.cancel()
        typingJob = serviceScope.launch {
            try {
                typeCredentials(alias, userName, password, submit)
            } catch (e: Exception) {
                Log.e(TAG, "BLE typing failed", e)
            } finally {
                ble.disconnect()
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        ble.disconnect()
    }

    // ── BLE work ──────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private suspend fun typeCredentials(
        alias: String,
        userName: String,
        password: String,
        submit: Boolean,
    ) {
        val device = DeviceRepository(applicationContext).getAll()
            .firstOrNull { it.alias == alias }
            ?: throw Exception("Device '$alias' not found in saved devices")

        val crypto = CryptoManager(device.pskBytes)

        // Scan until the device appears (or timeout).
        updateNotification("Scanning for ${device.bleName}…")
        ble.startScan()
        val scanState = withTimeoutOrNull(SCAN_TIMEOUT_MS) {
            ble.connectionState.first { state ->
                state is ConnectionState.ScanResult &&
                    state.devices.any { it.name == device.bleName }
            }
        }
        ble.stopScan()

        val bluetoothDevice = (scanState as? ConnectionState.ScanResult)
            ?.devices?.firstOrNull { it.name == device.bleName }?.device
            ?: throw Exception("Device '${device.bleName}' not found nearby (scan timed out)")

        // Connect and configure.
        updateNotification("Typing via ${device.alias}…")
        ble.connect(bluetoothDevice, crypto)
        ble.setLayout(device.layout, crypto)
        ble.setOs(device.targetOs, crypto)
        ble.setDelay(device.minHoldMs, device.maxHoldMs, device.minGapMs, device.maxGapMs, crypto)
        ble.markReady()

        // Type credentials.
        if (userName.isNotEmpty()) {
            ble.sendText(userName, crypto)
        }
        if (userName.isNotEmpty() && password.isNotEmpty()) {
            ble.sendKeyTap(BleConstants.SPECIAL_KEYS.getValue("TAB"), crypto)
        }
        if (password.isNotEmpty()) {
            ble.sendText(password, crypto)
        }
        if (submit) {
            ble.sendKeyTap(BleConstants.SPECIAL_KEYS.getValue("ENTER"), crypto)
        }

        // Brief pause so the firmware can flush the last keystroke.
        delay(300)
    }

    // ── Notification helpers ──────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "BtoKB Plugin",
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "BtoKB Bluetooth typing" }
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val openPi = packageManager.getLaunchIntentForPackage(packageName)?.let { launchIntent ->
            PendingIntent.getActivity(
                this, 0, launchIntent,
                PendingIntent.FLAG_IMMUTABLE,
            )
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("BtoKB Plugin")
            .setContentText(text)
            .setOngoing(true)
            .apply { if (openPi != null) setContentIntent(openPi) }
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    /**
     * Calls startForeground() with the connectedDevice type on Android 14+
     * (required when targetSdk >= 34) and with the plain two-argument form on
     * older releases.
     */
    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }
}
