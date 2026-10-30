package com.example.wayfinder

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/** Keeps location updates and voice guidance alive while navigating with the screen off. */
class NavService : Service() {
    override fun onBind(i: Intent?): IBinder? = null
    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("nav", "Navigation", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, "nav").setContentTitle("Wayfinder").setContentText("Navigation in progress")
            .setSmallIcon(android.R.drawable.ic_menu_directions).setContentIntent(open).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION) else startForeground(1, n)
        NavEngine.startGps()
        return START_STICKY
    }
}
