package com.mesh.messenger

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class MeshApp : Application() {
    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                "mesh_messages",
                "پیام‌ها",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "اعلان پیام‌های جدید"
            }
            val serviceChannel = NotificationChannel(
                "mesh_service",
                "سرویس پس‌زمینه",
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
            manager.createNotificationChannel(serviceChannel)
        }
    }
}
