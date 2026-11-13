// Copyright (C) 2026 NovaScale contributors
// SPDX-License-Identifier: Apache-2.0
package cc.galaxnet.novascale.browser

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import cc.galaxnet.novascale.MainActivity
import cc.galaxnet.novascale.NovaScaleApplication
import cc.galaxnet.novascale.R

/** Keeps the existing runtime available to explicitly configured proxy clients. */
internal class ProxyBackgroundService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") {
            (application as NovaScaleApplication).localProxy.stop()
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("local_proxy", getString(R.string.proxy_notifications), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 1843, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1843, Intent(this, ProxyBackgroundService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, "local_proxy")
            .setSmallIcon(R.drawable.ic_terminal_notification).setContentTitle(getString(R.string.proxy_running))
            .setContentText(intent?.getStringExtra("address").orEmpty()).setContentIntent(open)
            .setOngoing(true).setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.action_stop), stop).build()).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1843, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        else startForeground(1843, notification)
        return START_NOT_STICKY
    }
    companion object {
        fun start(context: Context, address: String) {
            context.startForegroundService(Intent(context, ProxyBackgroundService::class.java).putExtra("address", address))
        }
        fun stop(context: Context) { context.stopService(Intent(context, ProxyBackgroundService::class.java)) }
    }
}
