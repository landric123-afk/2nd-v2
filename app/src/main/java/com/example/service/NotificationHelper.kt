package com.example.service

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.MainActivity

object NotificationHelper {
    private const val CHANNEL_ID = "second_geofence_notifications"
    private const val CHANNEL_NAME = "2ND Spatial Intrusions"
    private const val EXTRUSION_NOTIFICATION_ID = 2026

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, importance).apply {
                description = "Alerts triggered on physical intrusion into spatial Coordinates & audio spheres"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 150, 80, 150, 80, 400) // Double warning pulse
            }
            val notificationManager: NotificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    @SuppressLint("MissingPermission")
    fun sendGeofenceBreachNotification(
        context: Context,
        title: String,
        body: String,
        soundDropId: Int? = null,
        isGraffiti: Boolean = false
    ) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("SOUND_DROP_ID", soundDropId)
            putExtra("OPEN_AR", isGraffiti)
        }
        
        val pendingIntent = PendingIntent.getActivity(
            context,
            soundDropId ?: 0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat) // Standard platform icon
            .setContentTitle("🏴‍☠️ $title")
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setVibrate(longArrayOf(0, 150, 80, 150, 80, 400))
            .setContentIntent(pendingIntent)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))

        try {
            with(NotificationManagerCompat.from(context)) {
                notify(soundDropId ?: EXTRUSION_NOTIFICATION_ID, builder.build())
            }
        } catch (e: SecurityException) {
            // Permission not granted or missing
        }
    }
}
