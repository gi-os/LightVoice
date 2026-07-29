package com.gios.lightvoice

import android.content.Context
import com.gios.lightvoice.act.ContactBook
import com.gios.lightvoice.act.Dispatcher
import com.gios.lightvoice.audio.Player
import com.gios.lightvoice.audio.Recorder
import com.gios.lightvoice.net.Brain
import com.gios.lightvoice.net.Stt
import com.gios.lightvoice.net.Tts
import com.gios.lightvoice.net.VoiceError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The whole conversation, in one place: hold, release, transcribe, decide, do, speak.
 *
 * A singleton rather than a ViewModel because two entry points share it — the Ask
 * tab in [MainActivity] and the [ListenActivity] the push-to-talk key opens — and a
 * reply must survive the window that started it going away mid-request.
 */
object VoiceEngine {

    enum class Phase { Idle, Listening, Thinking, Acting, Replying, Failed }

    data class State(
        val phase: Phase = Phase.Idle,
        val transcript: String = "",
        val reply: String = "",
        val error: String = "",
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private var recorder: Recorder? = null
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level

    @Volatile
    private var processing = false

    /** True when the mic is live, so a second key-press stops rather than restarts. */
    val listening: Boolean get() = _state.value.phase == Phase.Listening

    fun reset() {
        _state.value = State()
    }

    /**
     * Opens the mic. Returns false when the permission is missing or the mic is
     * busy — the caller is expected to ask for the permission and try again.
     */
    fun startListening(context: Context): Boolean {
        if (processing || listening) return false
        Player.stop()
        val app = context.applicationContext
        val rec = Recorder(app)
        if (!rec.hasPermission()) return false
        _state.value = State(phase = Phase.Listening)

        // Mirror the recorder's meter; collection ends when the flow's owner is dropped.
        scope.launch { rec.level.collect { _level.value = it } }

        // Fires when you stop talking, so releasing the key is optional.
        val started = rec.start { finish(app) }
        if (!started) {
            _state.value = State(phase = Phase.Failed, error = "The microphone is unavailable.")
            return false
        }
        recorder = rec
        return true
    }

    /** Called on key release, on tap, or by the recorder's own end-of-speech. */
    fun finish(context: Context) {
        val rec = recorder ?: return
        if (processing) return
        processing = true
        recorder = null
        val app = context.applicationContext

        scope.launch {
            try {
                val wav = rec.stop() ?: throw VoiceError("I didn't catch that.")
                _state.value = _state.value.copy(phase = Phase.Thinking)

                val names = ContactBook.names(app)
                val transcript = Stt.transcribe(
                    wav,
                    Prefs.groqKey(app),
                    prompt = names.take(60).joinToString(", "),
                )
                _state.value = _state.value.copy(transcript = transcript, phase = Phase.Acting)

                val plan = Brain.plan(transcript, names, Prefs.anthropicKey(app))
                val reply = Dispatcher.run(app, plan)
                _state.value = _state.value.copy(phase = Phase.Replying, reply = reply)

                Tts.synthesize(app, reply)?.let { Player.play(it) }
            } catch (e: Throwable) {
                _state.value = _state.value.copy(
                    phase = Phase.Failed,
                    error = (e as? VoiceError)?.message ?: (e.message ?: "Something went wrong."),
                )
            } finally {
                processing = false
                _level.value = 0f
            }
        }
    }

    fun abandon() {
        recorder?.cancel()
        recorder = null
        Player.stop()
        _level.value = 0f
        if (!processing) reset()
    }
}
