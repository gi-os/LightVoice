package com.gios.lightvoice.ptt

import android.app.AlarmManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.view.KeyEvent

/**
 * Whether the pieces the assistant leans on are actually present on this phone.
 *
 * The point of surfacing all of it in Settings is that LightOS has no Settings
 * screens of its own for most of this, so the alternative to a self-report is adb
 * archaeology every time something doesn't work.
 */
object Grants {

    /**
     * Whether the push-to-talk accessibility service is actually switched on.
     *
     * The setting is a colon-separated list of flattened component names, and it is read here
     * rather than through `AccessibilityManager` because that only lists services matching the
     * feedback types you ask for, and this one declares none.
     *
     * Compared as [ComponentName] objects, not as strings, and that is the whole fix. There are
     * two flattened forms of the same component — the long `pkg/pkg.Cls` that
     * `flattenToString` writes, and the short `pkg/.Cls` that `flattenToShortString` writes and
     * that the platform stores when the class lives under the package — and the old code
     * compared against the long one only. So the readout said OFF for a service that was
     * running perfectly well. [ComponentName.unflattenFromString] accepts both forms and
     * expands the short one, so the comparison no longer depends on which form the writer used.
     */
    fun pttServiceEnabled(c: Context): Boolean {
        val expected = ComponentName(c, PttService::class.java)
        val enabled = runCatching {
            Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        }.getOrNull().orEmpty()
        return enabled.split(':').any { entry ->
            ComponentName.unflattenFromString(entry.trim()) == expected
        }
    }

    fun exactAlarmsAllowed(c: Context): Boolean =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            true // no such restriction existed before Android 12
        } else {
            c.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() ?: false
        }

    /** True if anything on the phone can speak, i.e. whether the cloud voice is
     *  merely preferred or strictly required. */
    fun systemTtsPresent(c: Context): Boolean = runCatching {
        c.packageManager.queryIntentServices(
            Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE),
            0,
        ).isNotEmpty()
    }.getOrDefault(false)

    /** True if anything answers the platform speech-recognition intent. On a Light
     *  Phone this is false — no Play Services — which is why Whisper is the STT. */
    fun systemRecognizerPresent(c: Context): Boolean = runCatching {
        c.packageManager.queryIntentActivities(
            Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH),
            0,
        ).isNotEmpty()
    }.getOrDefault(false)

    /** Whether a clock app would have accepted `SET_ALARM`. Informational only — the
     *  assistant schedules its own alarms precisely because this is often false. */
    fun systemAlarmHandlerPresent(c: Context): Boolean = runCatching {
        c.packageManager.queryIntentActivities(
            Intent(android.provider.AlarmClock.ACTION_SET_ALARM),
            0,
        ).isNotEmpty()
    }.getOrDefault(false)

    fun openAccessibilitySettings(c: Context) {
        runCatching {
            c.startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /** A readable name for a bound key, since a bare code means nothing on screen. */
    fun keyName(code: Int): String = when (code) {
        KeyEvent.KEYCODE_VOLUME_UP -> "Volume up"
        KeyEvent.KEYCODE_VOLUME_DOWN -> "Volume down"
        KeyEvent.KEYCODE_HEADSETHOOK -> "Headset button"
        0 -> "none"
        else -> KeyEvent.keyCodeToString(code)
            .removePrefix("KEYCODE_")
            .lowercase()
            .replace('_', ' ')
            .replaceFirstChar(Char::uppercase)
    }
}
