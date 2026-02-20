package com.shadowflee.fluxer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Keeps the app process alive while the user has switched to another app.
 *
 * Without this service, Android is free to kill the process to reclaim
 * memory, which would terminate any active voice/video call mid-conversation.
 * A foreground service carries a visible notification and signals to the OS
 * that this process is doing user-requested work.
 *
 * Lifecycle:
 *   - Started in MainActivity.onStop()  (app fully backgrounded)
 *   - Stopped in MainActivity.onStart() (app comes back to foreground)
 */
class FluxerForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "fluxer_bg"
        private const val NOTIF_ID   = Int.MAX_VALUE // reserved; won't collide with chat notifs
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()

        val tapIntent = Intent(this, MainActivity::class.java).apply {
            this.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            this, NOTIF_ID, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Fluxer is active")
            .setContentText("Tap to return to your conversation")
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .build()

        when {
            // API 30+: declare both media-playback and microphone types so the OS
            // grants the app continued mic/audio access while backgrounded.
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> startForeground(
                NOTIF_ID, notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
            // API 29: FOREGROUND_SERVICE_TYPE_MICROPHONE not yet available
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> startForeground(
                NOTIF_ID, notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
            // API 23–28: no foreground service type concept; plain startForeground is fine
            else -> startForeground(NOTIF_ID, notif)
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val existing = (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .getNotificationChannel(CHANNEL_ID)
            if (existing != null) return // already created; avoid re-creating on every start

            val channel = NotificationChannel(
                CHANNEL_ID,
                "Fluxer Background",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps Fluxer active while you use other apps"
                setShowBadge(false)
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }
}
