package com.gios.lightvoice.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gios.lightvoice.Prefs
import com.gios.lightvoice.VoiceEngine
import com.gios.lightvoice.ui.theme.Dim
import com.gios.lightvoice.ui.theme.Faint

/**
 * The talking surface, shared by the Ask tab and the push-to-talk window.
 *
 * Hold the circle to speak and release to send — but the recorder also detects the
 * end of speech on its own, so releasing early is fine and letting go of nothing
 * still works. Both paths land in [VoiceEngine.finish].
 */
@Composable
fun AskPanel(autoStart: Boolean = false, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val state by VoiceEngine.state.collectAsStateWithLifecycle()
    val level by VoiceEngine.level.collectAsStateWithLifecycle()
    var denied by remember { mutableStateOf(false) }

    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        denied = !ok
        if (ok) VoiceEngine.startListening(context)
    }

    // The push-to-talk key opens this already committed to listening.
    LaunchedEffect(autoStart) {
        if (autoStart && state.phase == VoiceEngine.Phase.Idle) {
            if (!VoiceEngine.startListening(context)) {
                askMic.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    val configured = Prefs.groqKey(context).isNotBlank() && Prefs.anthropicKey(context).isNotBlank()
    val scale by animateFloatAsState(
        targetValue = if (state.phase == VoiceEngine.Phase.Listening) {
            1f + (level * 6f).coerceAtMost(0.55f)
        } else {
            1f
        },
        label = "mic-level",
    )

    Column(
        modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                statusLine(state.phase),
                style = MaterialTheme.typography.labelSmall,
                color = Dim,
            )
            if (state.transcript.isNotBlank()) {
                Gap(14)
                Text(
                    "“${state.transcript}”",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Faint,
                    textAlign = TextAlign.Center,
                )
            }
        }

        Box(contentAlignment = Alignment.Center) {
            val size = (132 * scale).dp
            Box(
                Modifier
                    .size(size)
                    .border(1.dp, Color.White, CircleShape)
                    .background(
                        if (state.phase == VoiceEngine.Phase.Listening) Color(0xFF141414) else Color.Black,
                        CircleShape,
                    )
                    .pointerInput(configured) {
                        detectTapGestures(
                            onPress = {
                                if (!configured) return@detectTapGestures
                                VoiceEngine.reset()
                                val started = VoiceEngine.startListening(context)
                                if (!started && !VoiceEngine.listening) {
                                    askMic.launch(Manifest.permission.RECORD_AUDIO)
                                    return@detectTapGestures
                                }
                                tryAwaitRelease()
                                VoiceEngine.finish(context)
                            },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    when (state.phase) {
                        VoiceEngine.Phase.Listening -> "●"
                        VoiceEngine.Phase.Thinking, VoiceEngine.Phase.Acting -> "…"
                        else -> "HOLD"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White,
                )
            }
        }

        Column(
            Modifier.fillMaxWidth().padding(bottom = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val message = when {
                state.error.isNotBlank() -> state.error
                state.reply.isNotBlank() -> state.reply
                !configured -> "Add a Groq key and an Anthropic key in Settings to start."
                denied -> "The microphone permission is off, so I can't hear you."
                else -> ""
            }
            if (message.isNotBlank()) {
                Text(
                    message,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (state.error.isNotBlank()) Dim else Color.White,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

private fun statusLine(phase: VoiceEngine.Phase) = when (phase) {
    VoiceEngine.Phase.Idle -> "HOLD TO TALK"
    VoiceEngine.Phase.Listening -> "LISTENING"
    VoiceEngine.Phase.Thinking -> "TRANSCRIBING"
    VoiceEngine.Phase.Acting -> "WORKING"
    VoiceEngine.Phase.Replying -> ""
    VoiceEngine.Phase.Failed -> ""
}
