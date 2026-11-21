package com.example.vidgrab

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager

/** Keeps the process alive (with a notification, a CPU wake lock and a Wi-Fi lock) while downloads run. */
class DownloadService : Service() {
    private var lock: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null

    override fun onBind(i: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    override fun onCreate() {
        super.onCreate()
        lock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "vidgrab:downloads")
            .apply { acquire(6 * 60 * 60 * 1000L) }
        // Without this, Wi-Fi drops into power-save mode when the screen is off and downloads slow down.
        wifi = try {
            applicationContext.getSystemService(WifiManager::class.java)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "vidgrab:downloads")
                .apply { setReferenceCounted(false); acquire() }
        } catch (_: Exception) { null }
    }

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, build(this, "Downloads running"), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        lock?.let { if (it.isHeld) it.release() }
        wifi?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    companion object {
        /** The "N downloads running" notification; each download also has its own (see Notifier). */
        fun build(ctx: Context, text: String): Notification = Notifier.summary(ctx, text)
    }
}
