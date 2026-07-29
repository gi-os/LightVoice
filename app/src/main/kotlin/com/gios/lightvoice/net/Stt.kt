package com.gios.lightvoice.net

import java.io.File
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject

/**
 * Speech to text through Groq's Whisper endpoint.
 *
 * The LPIII has no Play Services, so `android.speech.SpeechRecognizer` has no
 * provider to bind to and the platform path is simply absent. Groq runs
 * `whisper-large-v3-turbo` at a couple of hundred times real time, which makes a
 * cloud round trip feel closer to on-device than a local model would — and it costs
 * a fraction of a cent per utterance.
 */
object Stt {
    private const val URL = "https://api.groq.com/openai/v1/audio/transcriptions"
    private const val MODEL = "whisper-large-v3-turbo"

    /**
     * [prompt] biases the decoder — feeding it the user's own contact names is what
     * turns "text Alex" into the right handle instead of "text Alec".
     */
    fun transcribe(wav: File, apiKey: String, prompt: String = ""): String {
        if (apiKey.isBlank()) throw VoiceError("No Groq key yet — add one in Settings.")
        if (!wav.exists() || wav.length() < 2048L) throw VoiceError("I didn't catch that.")

        val form = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", wav.name, wav.asRequestBody("audio/wav".toMediaType()))
            .addFormDataPart("model", MODEL)
            .addFormDataPart("response_format", "json")
            .addFormDataPart("temperature", "0")
            .addFormDataPart("language", "en")
            .apply { if (prompt.isNotBlank()) addFormDataPart("prompt", prompt.take(880)) }
            .build()

        val req = Request.Builder()
            .url(URL)
            .addHeader("Authorization", "Bearer $apiKey")
            .post(form)
            .build()

        Http.client.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw VoiceError(explain(resp.code, raw, "Groq"))
            val text = runCatching { JSONObject(raw).optString("text") }.getOrNull().orEmpty().trim()
            if (text.isEmpty()) throw VoiceError("I didn't catch that.")
            return text
        }
    }

    /** Turns an HTTP failure into something worth reading on a 3.9-inch screen. */
    fun explain(code: Int, raw: String, who: String): String {
        val detail = runCatching {
            JSONObject(raw).optJSONObject("error")?.optString("message")
        }.getOrNull()?.takeIf { it.isNotBlank() }
        return when (code) {
            401, 403 -> "$who rejected the key."
            429 -> "$who is rate-limiting — try again in a moment."
            in 500..599 -> "$who is having a moment ($code)."
            else -> detail ?: "$who failed ($code)."
        }
    }
}
