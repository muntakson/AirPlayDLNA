package com.airplay.dlna

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

/**
 * Application class for DLNA streaming app.
 * Initializes notification channels required for foreground services.
 */
class DLNAApplication : Application() {

    companion object {
        const val CHANNEL_ID = "dlna_streaming_channel"
        const val CHANNEL_NAME = "DLNA Streaming"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    /**
     * Creates notification channel for foreground service notifications.
     * Required for Android O (API 26) and above.
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notification channel for DLNA streaming service"
                setShowBadge(false)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
}
