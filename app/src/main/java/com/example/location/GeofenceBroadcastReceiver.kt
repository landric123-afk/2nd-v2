package com.example.location

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.service.NotificationHelper
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent

class GeofenceBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent)
        if (event == null || event.hasError()) {
            Log.w(TAG, "GeofencingEvent error: ${event?.errorCode}")
            return
        }

        if (event.geofenceTransition == Geofence.GEOFENCE_TRANSITION_ENTER) {
            NotificationHelper.createNotificationChannel(context)
            val triggering = event.triggeringGeofences ?: return
            for (fence in triggering) {
                val requestId = fence.requestId
                val meta = GeofenceRegistry.lookup(requestId)
                if (meta != null) {
                    NotificationHelper.sendGeofenceBreachNotification(
                        context = context,
                        title = if (meta.isGraffiti) "VISUAL ANCHOR LINKED" else "SOUND-DROP IN RANGE",
                        body = meta.notificationBody,
                        soundDropId = meta.notificationId,
                        isGraffiti = meta.isGraffiti
                    )
                } else {
                    NotificationHelper.sendGeofenceBreachNotification(
                        context = context,
                        title = "SPATIAL ZONE ENTERED",
                        body = "Entered nearby 2ND geofence zone ($requestId).",
                        soundDropId = requestId.hashCode(),
                        isGraffiti = requestId.startsWith("graf_")
                    )
                }
            }
        }
    }

    private companion object {
        const val TAG = "GeofenceReceiver"
    }
}

data class RegisteredGeofenceMeta(
    val requestId: String,
    val title: String,
    val notificationBody: String,
    val notificationId: Int,
    val isGraffiti: Boolean
)

object GeofenceRegistry {
    private val map = LinkedHashMap<String, RegisteredGeofenceMeta>()

    @Synchronized
    fun replaceAll(items: List<RegisteredGeofenceMeta>) {
        map.clear()
        items.forEach { map[it.requestId] = it }
    }

    @Synchronized
    fun lookup(requestId: String): RegisteredGeofenceMeta? = map[requestId]
}
