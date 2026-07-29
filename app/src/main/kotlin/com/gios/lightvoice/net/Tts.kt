package com.gios.lightvoice.net

import android.content.Context
import com.gios.lightvoice.Prefs
import java.io.File
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * The spoken reply. LightOS ships no TTS engine — `android.speech.tts` has nothing
 * to bind to — so the voice is synthesised in the cloud and played as a file.
 *
 * Groq is the default because it means one key for both ears and mouth (PlayAI
 * Dialog, which needs its terms accepted once in the Groq console). OpenAI is there
 * as an alternative for anyone who already has that key.
 */
object Tts {
    private const val GROQ_URL = "https://api.groq.com/openai/v1/audio/speech"
    private const val OPENAI_URL = "https://api.openai.com/v1/audio/speech"

    /** Returns the audio file to play, or null when speech is off or unconfigured. */
    fun synthesize(c: Context, text: String): File? {
        if (!Prefs.ttsEnabled(c) || text.isBlank()) return null
        val openAi = Prefs.ttsProvider(c) == "openai"
        val key = if (openAi) Prefs.openAiKey(c) else Prefs.groqKey(c)
        if (key.isBlank()) return null

        val format = if (openAi) "mp3" else "wav"
        val body = JSONObject()
            .put("model", if (openAi) "gpt-4o-mini-tts" else "playai-tts")
            .put("voice", Prefs.ttsVoice(c))
            .put("input", text.take(1200))
            .put("response_format", format)
            .toString()

        val req = Request.Builder()
            .url(if (openAi) OPENAI_URL else GROQ_URL)
            .addHeader("Authorization", "Bearer $key")
            .addHeader("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        Http.client.newCall(req).execute().use { resp ->
            // A silent failure is the right one here: the reply is already on screen,
            // so a missing voice should never become an error the user has to read.
            if (!resp.isSuccessful) return null
            val bytes = resp.body?.bytes() ?: return null
            if (bytes.size < 512) return null
            val out = File(c.cacheDir, "reply.$format")
            out.writeBytes(bytes)
            return out
        }
    }
}
