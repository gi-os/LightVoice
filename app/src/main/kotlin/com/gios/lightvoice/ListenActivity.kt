package com.gios.lightvoice

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gios.lightvoice.ui.AskPanel
import com.gios.lightvoice.ui.theme.LightVoiceTheme
import kotlinx.coroutines.delay

/**
 * The window the push-to-talk key opens, from anywhere in the phone — including a
 * locked, dark screen, which is what `showWhenLocked` and `turnScreenOn` in the
 * manifest buy. It starts listening immediately and closes itself once the answer
 * has been read, so it never becomes a place you have to navigate out of.
 */
class ListenActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        VoiceEngine.reset()
        setContent {
            LightVoiceTheme {
                val state by VoiceEngine.state.collectAsStateWithLifecycle()

                // Linger on the reply long enough to read it, then get out of the way.
                LaunchedEffect(state.phase) {
                    val done = state.phase == VoiceEngine.Phase.Replying ||
                        state.phase == VoiceEngine.Phase.Failed
                    if (done) {
                        delay(if (state.phase == VoiceEngine.Phase.Failed) 4_000 else 7_000)
                        finish()
                    }
                }
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    AskPanel(autoStart = true)
                }
            }
        }
    }

    /** The key that opened this may fire again while it is up; treat that as "stop
     *  talking, I'm done" rather than stacking a second window. */
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        if (VoiceEngine.listening) VoiceEngine.finish(this)
    }

    override fun onStop() {
        super.onStop()
        if (VoiceEngine.listening) VoiceEngine.finish(this)
    }
}
