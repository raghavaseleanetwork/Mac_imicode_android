package com.sdk.glassessdksample.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import com.sdk.glassessdksample.R

/**
 * Warns the user when more than one Bluetooth audio device is connected at once.
 *
 * Android has no reliable way for this app to force audio onto a specific
 * Bluetooth device when several are connected — the legacy SCO APIs take no
 * device argument at all, and even the modern targeted API can be overridden by
 * the system or another app. Rather than silently risking "Hey IMI" audio going
 * to the wrong device, we detect the ambiguous state up front and ask the user
 * to disconnect the other device, which is the one thing guaranteed to fix it.
 */
object MultipleBluetoothDeviceNotifier {
    private const val CHANNEL_ID = "imi_multi_bt_warning"
    private const val NOTIFICATION_ID = 9421

    fun notify(context: Context, deviceNames: List<String>) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        ensureChannel(nm)

        val otherDevices = deviceNames.joinToString(", ")
        val text = "Multiple Bluetooth devices are connected ($otherDevices). " +
            "Disconnect the other device so audio goes to your glasses."

        val notification: Notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("Two Bluetooth devices connected")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        nm.notify(NOTIFICATION_ID, notification)
    }

    fun clear(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        nm.cancel(NOTIFICATION_ID)
    }

    private fun ensureChannel(nm: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Bluetooth conflicts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Warns when more than one Bluetooth audio device is connected"
        }
        nm.createNotificationChannel(channel)
    }
}
