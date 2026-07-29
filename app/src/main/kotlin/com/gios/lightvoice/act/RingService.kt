package com.gios.lightvoice.act

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import com.gios.lightvoice.R
import com.gios.lightvoice.data.Alarm
import com.gios.lightvoice.data.Store

/**
 * Everything that happens when an alarm goes off: the sound, the vibration, the
 * wake lock and the full-screen notification that puts [RingActivity] on the panel.
 *
 * Two paths open that window, because on a sideloaded app neither is guaranteed:
 * the notification's full-screen intent (the sanctioned route, install-granted via
 * `USE_FULL_SCREEN_INTENT`) and a direct `startActivity`, which only lands if the
 * `SYSTEM_ALERT_WINDOW` appop has been granted over adb. [RingActivity] is
 * `singleTop`, so whichever arrives second is a no-op.
 */
class RingService : Service() {

    private var player: MediaPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var ringingId: Int = -1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { finishRinging(); return START_NOT_STICKY }
            ACTION_SNOOZE -> {
                val id = intent.getIntExtra(Alarms.EXTRA_ID, ringingId)
                (Store.alarm(this, id) ?: snapshot)?.let { Alarms.snooze(this, it) }
                finishRinging()
                return START_NOT_STICKY
            }
        }

        val id = intent?.getIntExtra(Alarms.EXTRA_ID, -1) ?: -1
        val alarm = Store.alarm(this, id) ?: snapshot ?: run { stopSelf(); return START_NOT_STICKY }
        // The stored alarm may already have been rolled forward or deleted by
        // AlarmReceiver, so keep our own copy of what is ringing.
        snapshot = alarm
        ringingId = alarm.id

        startForeground(NOTIF_ID, notification(alarm))
        acquireWakeLock()
        startSound()
        startVibration()
        runCatching {
            startActivity(
                Intent(this, RingActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(Alarms.EXTRA_ID, alarm.id),
            )
        }
        return START_STICKY
    }

    override fun onDestroy() {
        releaseEverything()
        super.onDestroy()
    }

    private fun finishRinging() {
        releaseEverything()
        snapshot = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun releaseEverything() {
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        vibrator()?.cancel()
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(PowerManager::class.java) ?: return
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lightvoice:ring").apply {
            setReferenceCounted(false)
            acquire(WAKE_MILLIS)
        }
    }

    /** Default alarm tone, falling back to the ringtone and then the notification
     *  sound — a sideloaded build cannot assume LightOS ships all three. */
    private fun startSound() {
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ?: return
        player = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                setDataSource(this@RingService, uri)
                isLooping = true
                prepare()
                start()
            }
        }.getOrNull()
        // The alarm stream can sit at zero from a previous silent night; an alarm you
        // cannot hear is the one failure mode worth being pushy about.
        runCatching {
            val am = getSystemService(AudioManager::class.java) ?: return@runCatching
            val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            if (am.getStreamVolume(AudioManager.STREAM_ALARM) < max / 3) {
                am.setStreamVolume(AudioManager.STREAM_ALARM, max / 2, 0)
            }
        }
    }

    private fun startVibration() {
        val pattern = longArrayOf(0, 600, 700)
        runCatching {
            vibrator()?.vibrate(
                VibrationEffect.createWaveform(pattern, 0),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build(),
            )
        }
    }

    private fun vibrator(): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Vibrator::class.java)
        }

    private fun notification(alarm: Alarm): android.app.Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm?.createNotificationChannel(
            NotificationChannel(CHANNEL, "Alarms", NotificationManager.IMPORTANCE_HIGH).apply {
                // We play the tone ourselves on the alarm stream, so the channel stays
                // silent — otherwise the phone rings twice, half a second apart.
                setSound(null, null)
                enableVibration(false)
                setBypassDnd(true)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            },
        )
        val full = PendingIntent.getActivity(
            this,
            alarm.id,
            Intent(this, RingActivity::class.java).putExtra(Alarms.EXTRA_ID, alarm.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            10_000 + alarm.id,
            Intent(this, RingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = when (alarm.kind) {
            Alarm.KIND_TIMER -> "Timer"
            Alarm.KIND_REMINDER -> "Reminder"
            else -> "Alarm"
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(alarm.label.ifBlank { title })
            .setContentText(Alarms.clockText(alarm.atMillis))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setAutoCancel(false)
            .setFullScreenIntent(full, true)
            .setContentIntent(full)
            .addAction(0, "Stop", stop)
            .build()
    }

    companion object {
        const val ACTION_RING = "com.gios.lightvoice.RING"
        const val ACTION_STOP = "com.gios.lightvoice.RING_STOP"
        const val ACTION_SNOOZE = "com.gios.lightvoice.RING_SNOOZE"
        private const val CHANNEL = "alarms"
        private const val NOTIF_ID = 42
        private const val WAKE_MILLIS = 10 * 60_000L

        /** What is ringing right now. [AlarmReceiver] deletes a one-shot from the store
         *  the moment it fires, so the ring screen reads it from here instead. */
        @Volatile
        var snapshot: Alarm? = null
            private set

        fun stop(c: Context) =
            c.startService(Intent(c, RingService::class.java).setAction(ACTION_STOP))

        fun snooze(c: Context, id: Int) = c.startService(
            Intent(c, RingService::class.java)
                .setAction(ACTION_SNOOZE)
                .putExtra(Alarms.EXTRA_ID, id),
        )
    }
}
