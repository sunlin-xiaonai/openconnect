package com.agmente.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

object AgmenteNotifications {
    private const val CHANNEL_ID = "agmente_thread_updates_v4"
    private const val FALLBACK_ALERT_DEBOUNCE_MS = 1800L
    private val VIBRATION_PATTERN = longArrayOf(0, 180, 120, 260)

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var isAppVisible: Boolean = false

    @Volatile
    private var lastFallbackAlertAt: Long = 0L

    fun initialize(context: Context) {
        appContext = context.applicationContext
        ensureChannel()
    }

    fun setAppVisible(visible: Boolean) {
        isAppVisible = visible
    }

    fun notifyThreadCompleted(
        threadId: String,
        threadTitle: String,
        description: String,
    ) {
        postNotification(
            notificationId = threadId.hashCode(),
            title = contextString(R.string.notification_title_completed),
            threadId = threadId,
            threadTitle = threadTitle,
            content = description,
            accentColor = android.R.color.holo_green_dark,
        )
    }

    fun notifyThreadFailed(
        threadId: String,
        threadTitle: String,
        description: String,
    ) {
        postNotification(
            notificationId = threadId.hashCode(),
            title = contextString(R.string.notification_title_failed),
            threadId = threadId,
            threadTitle = threadTitle,
            content = description,
            accentColor = android.R.color.holo_red_dark,
        )
    }

    private fun postNotification(
        notificationId: Int,
        title: String,
        threadId: String,
        threadTitle: String,
        content: String,
        accentColor: Int,
    ) {
        val context = appContext ?: return
        val pendingIntent = buildOpenThreadPendingIntent(
            context = context,
            notificationId = notificationId,
            threadId = threadId,
        )

        if (!hasNotificationPermission(context)) {
            if (isAppVisible) {
                playFallbackAlert(context)
            }
            return
        }

        val notificationTitle = "$title · $threadTitle"
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(notificationTitle)
            .setContentText(content)
            .setSubText(context.getString(R.string.notification_subtext))
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .setBigContentTitle(notificationTitle)
                    .bigText(content)
                    .setSummaryText(context.getString(R.string.notification_summary_open_thread))
            )
            .addAction(
                android.R.drawable.ic_menu_view,
                context.getString(R.string.notification_action_view_thread),
                pendingIntent,
            )
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setShowWhen(true)
            .setWhen(System.currentTimeMillis())
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(false)
            .setLocalOnly(true)
            .setSilent(false)
            .setTicker(notificationTitle)
            .setColor(ContextCompat.getColor(context, accentColor))
            .setColorized(true)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setVibrate(VIBRATION_PATTERN)
            .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
            .setLights(ContextCompat.getColor(context, accentColor), 400, 900)
            .build()

        NotificationManagerCompat.from(context).notify(notificationId, notification)

        if (isAppVisible) {
            playFallbackAlert(context)
        }
    }

    private fun buildOpenThreadPendingIntent(
        context: Context,
        notificationId: Int,
        threadId: String,
    ): PendingIntent {
        val openIntent = Intent(
            Intent.ACTION_VIEW,
            Uri.Builder()
                .scheme("agmente")
                .authority("task")
                .appendQueryParameter("threadId", threadId)
                .build(),
            context,
            MainActivity::class.java,
        ).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }

        return PendingIntent.getActivity(
            context,
            notificationId,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun playFallbackAlert(context: Context) {
        val now = System.currentTimeMillis()
        if (now - lastFallbackAlertAt < FALLBACK_ALERT_DEBOUNCE_MS) {
            return
        }
        lastFallbackAlertAt = now

        val audioManager = ContextCompat.getSystemService(context, AudioManager::class.java)
        val ringerMode = audioManager?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL
        if (ringerMode == AudioManager.RINGER_MODE_NORMAL) {
            runCatching {
                val ringtone = RingtoneManager.getRingtone(
                    context,
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                )
                ringtone?.play()
            }
        }

        runCatching {
            val vibrationEffect = VibrationEffect.createWaveform(VIBRATION_PATTERN, -1)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = ContextCompat.getSystemService(context, VibratorManager::class.java)
                vibratorManager?.defaultVibrator?.vibrate(vibrationEffect)
            } else {
                val vibrator = ContextCompat.getSystemService(context, Vibrator::class.java)
                vibrator?.vibrate(vibrationEffect)
            }
        }
    }

    private fun ensureChannel() {
        val context = appContext ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        val manager = ContextCompat.getSystemService(context, NotificationManager::class.java) ?: return
        val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.notification_channel_description)
            enableVibration(true)
            vibrationPattern = VIBRATION_PATTERN
            enableLights(true)
            lightColor = ContextCompat.getColor(context, android.R.color.holo_blue_light)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setShowBadge(true)
            setSound(
                soundUri,
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
        }
        manager.createNotificationChannel(channel)
    }

    private fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun contextString(resId: Int): String {
        val context = appContext ?: return ""
        return context.getString(resId)
    }
}
