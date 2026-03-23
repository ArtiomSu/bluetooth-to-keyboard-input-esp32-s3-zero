package com.terminal_heat_sink.bluetoothtokeyboardinput.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.terminal_heat_sink.bluetoothtokeyboardinput.MainActivity
import com.terminal_heat_sink.bluetoothtokeyboardinput.R

object BleNotificationHelper {

    const val CHANNEL_ID      = "ble_connection"
    const val NOTIFICATION_ID = 1
    const val ACTION_DISCONNECT =
        "com.terminal_heat_sink.bluetoothtokeyboardinput.ACTION_DISCONNECT"

    fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "BLE Connection",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shows the active Bluetooth device connection"
        }
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    fun buildNotification(
        context: Context,
        bleName: String,
        layout: String,
        os: String,
    ): Notification {
        val disconnectPi = PendingIntent.getBroadcast(
            context,
            0,
            Intent(ACTION_DISCONNECT).setPackage(context.packageName),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val openAppPi = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Connected to $bleName")
            .setContentText("$layout  •  $os")
            // Not ongoing — allows swipe-to-dismiss, which triggers the deleteIntent below
            .setOngoing(false)
            .setAutoCancel(false)
            .setContentIntent(openAppPi)
            // Swiping the notification away = disconnect
            .setDeleteIntent(disconnectPi)
            // Explicit "Disconnect" action button in the expanded notification
            .addAction(0, "Disconnect", disconnectPi)
            .build()
    }
}
