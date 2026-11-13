/*
 * NovaScale for Android
 * Copyright (C) 2026 NovaScale contributors
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package cc.galaxnet.novascale.terminalfeature

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import cc.galaxnet.novascale.MainActivity
import cc.galaxnet.novascale.NovaScaleApplication
import cc.galaxnet.novascale.R

/**
 * Keeps the process hosting opted-in terminal runtimes in foreground priority.
 * SSH and Tailscale remain owned by the application registry; this service
 * deliberately owns no second networking stack.
 */
internal class TerminalBackgroundService : Service() {
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CLOSE_ALL) {
            (application as NovaScaleApplication).terminalSessions.closeAll()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val sessionCount = intent?.getIntExtra(EXTRA_SESSION_COUNT, 1)?.coerceAtLeast(1) ?: 1
        val notification = notification(sessionCount)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_terminals),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.notification_channel_terminals_description)
                setShowBadge(false)
            },
        )
    }

    private fun notification(sessionCount: Int): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val closeAll = PendingIntent.getService(
            this,
            1,
            Intent(this, TerminalBackgroundService::class.java).setAction(ACTION_CLOSE_ALL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val label = resources.getQuantityString(
            R.plurals.active_terminal_sessions,
            sessionCount,
            sessionCount,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_terminal_notification)
            .setContentTitle(getString(R.string.notification_terminals_title))
            .setContentText(label)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(
                Notification.Action.Builder(
                    null,
                    getString(R.string.notification_close_all),
                    closeAll,
                ).build(),
            )
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "terminal_sessions"
        private const val NOTIFICATION_ID = 1842
        private const val EXTRA_SESSION_COUNT = "session_count"
        private const val ACTION_REFRESH = "cc.galaxnet.novascale.action.REFRESH_TERMINALS"
        private const val ACTION_CLOSE_ALL = "cc.galaxnet.novascale.action.CLOSE_TERMINALS"

        fun update(context: Context, enabled: Boolean, sessionCount: Int) {
            if (!enabled || sessionCount == 0) {
                context.stopService(Intent(context, TerminalBackgroundService::class.java))
                return
            }
            val intent = Intent(context, TerminalBackgroundService::class.java)
                .setAction(ACTION_REFRESH)
                .putExtra(EXTRA_SESSION_COUNT, sessionCount)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
