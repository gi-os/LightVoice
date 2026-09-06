package com.gios.lightvoice

import android.content.Context
import android.view.KeyEvent

/**
 * Every setting the assistant has, in one SharedPreferences file. Keys are entered
 * by hand or by QR (see `docs/index.html`) and never leave the phone.
 *
 * No Room, no DataStore: the whole persisted state is three keys, a URL, a handful
 * of flags and two small JSON lists, and avoiding ksp keeps the build a single
 * Kotlin compile.
 */
object Prefs {
    private const val FILE = "lightvoice"

    private const val K_GROQ = "groq_key"
    private const val K_ANTHROPIC = "anthropic_key"
    private const val K_OPENAI = "openai_key"
    private const val K_TTS_ON = "tts_on"
    private const val K_TTS_PROVIDER = "tts_provider" // "groq" | "openai"
    private const val K_TTS_VOICE = "tts_voice"
    private const val K_BB_URL = "bb_url"
    private const val K_BB_PASSWORD = "bb_password"
    private const val K_PTT_ON = "ptt_on"
    private const val K_PTT_KEYCODE = "ptt_keycode"
    private const val K_PTT_LEARN = "ptt_learn"
    private const val K_PTT_LAST_SEEN = "ptt_last_seen"
    private const val K_USED = "used"
    private const val K_ALARMS = "alarms"
    private const val K_NOTES = "notes"
    private const val K_BB_CONTACTS = "bb_contacts"

    fun prefs(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun str(c: Context, k: String, d: String = "") = prefs(c).getString(k, d) ?: d
    private fun put(c: Context, k: String, v: String) = prefs(c).edit().putString(k, v.trim()).apply()

    fun groqKey(c: Context) = str(c, K_GROQ)
    fun setGroqKey(c: Context, v: String) = put(c, K_GROQ, v)
    fun anthropicKey(c: Context) = str(c, K_ANTHROPIC)
    fun setAnthropicKey(c: Context, v: String) = put(c, K_ANTHROPIC, v)
    fun openAiKey(c: Context) = str(c, K_OPENAI)
    fun setOpenAiKey(c: Context, v: String) = put(c, K_OPENAI, v)

    fun ttsEnabled(c: Context) = prefs(c).getBoolean(K_TTS_ON, true)
    fun setTtsEnabled(c: Context, v: Boolean) = prefs(c).edit().putBoolean(K_TTS_ON, v).apply()

    fun ttsProvider(c: Context) = str(c, K_TTS_PROVIDER, "groq")
    fun setTtsProvider(c: Context, v: String) = put(c, K_TTS_PROVIDER, v)

    /** PlayAI voice on Groq, or an OpenAI voice name when the provider is openai. */
    fun ttsVoice(c: Context) = str(c, K_TTS_VOICE, "").ifBlank {
        if (ttsProvider(c) == "openai") "alloy" else "Fritz-PlayAI"
    }
    fun setTtsVoice(c: Context, v: String) = put(c, K_TTS_VOICE, v)

    fun bbUrl(c: Context) = str(c, K_BB_URL)
    fun setBbUrl(c: Context, v: String) {
        var url = v.trim().trimEnd('/')
        if (url.isNotEmpty() && !url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://$url"
        }
        put(c, K_BB_URL, url)
    }

    fun bbPassword(c: Context) = str(c, K_BB_PASSWORD)
    fun setBbPassword(c: Context, v: String) = put(c, K_BB_PASSWORD, v)
    fun messagingConfigured(c: Context) = bbUrl(c).isNotBlank() && bbPassword(c).isNotBlank()

    fun pttEnabled(c: Context) = prefs(c).getBoolean(K_PTT_ON, false)
    fun setPttEnabled(c: Context, v: Boolean) = prefs(c).edit().putBoolean(K_PTT_ON, v).apply()

    /** Which hardware key long-press opens the mic. Volume-up by default because it
     *  exists on every unit; the LPIII's custom key reports a vendor code, which the
     *  "learn a key" flow in Settings captures. */
    fun pttKeycode(c: Context) = prefs(c).getInt(K_PTT_KEYCODE, KeyEvent.KEYCODE_VOLUME_UP)
    fun setPttKeycode(c: Context, v: Int) = prefs(c).edit().putInt(K_PTT_KEYCODE, v).apply()

    fun pttLearning(c: Context) = prefs(c).getBoolean(K_PTT_LEARN, false)
    fun setPttLearning(c: Context, v: Boolean) = prefs(c).edit().putBoolean(K_PTT_LEARN, v).apply()

    /** Last keycode the service saw while learning, so Settings can display it. */
    fun pttLastSeen(c: Context) = prefs(c).getInt(K_PTT_LAST_SEEN, 0)
    fun setPttLastSeen(c: Context, v: Int) = prefs(c).edit().putInt(K_PTT_LAST_SEEN, v).apply()

    /** Set once the hold gesture has been used; the "HOLD" coaching text then stays away. */
    fun used(c: Context) = prefs(c).getBoolean(K_USED, false)
    fun setUsed(c: Context, v: Boolean) = prefs(c).edit().putBoolean(K_USED, v).apply()

    fun alarmsJson(c: Context) = str(c, K_ALARMS, "[]")
    fun setAlarmsJson(c: Context, v: String) = put(c, K_ALARMS, v)
    fun notesJson(c: Context) = str(c, K_NOTES, "[]")
    fun setNotesJson(c: Context, v: String) = put(c, K_NOTES, v)
    fun bbContactsJson(c: Context) = str(c, K_BB_CONTACTS, "{}")
    fun setBbContactsJson(c: Context, v: String) = put(c, K_BB_CONTACTS, v)
}
