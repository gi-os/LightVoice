package com.gios.lightvoice.act

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gios.lightvoice.data.Alarm
import com.gios.lightvoice.data.Store
import com.gios.lightvoice.ui.BigButton
import com.gios.lightvoice.ui.theme.Dim
import com.gios.lightvoice.ui.theme.LightVoiceTheme

/**
 * The full-screen ring. Reached either by the notification's full-screen intent or
 * by [RingService]'s direct start; `showWhenLocked` + `turnScreenOn` in the manifest
 * are what let it light a sleeping panel.
 */
class RingActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getIntExtra(Alarms.EXTRA_ID, -1)
        val alarm = Store.alarm(this, id) ?: RingService.snapshot
        setContent {
            LightVoiceTheme {
                RingScreen(
                    alarm = alarm,
                    onStop = { RingService.stop(this); finish() },
                    onSnooze = { RingService.snooze(this, alarm?.id ?: id); finish() },
                )
            }
        }
    }

    /** The hardware keys must not dismiss an alarm by accident, and back must not
     *  leave it ringing behind a blank screen — so both do nothing here. */
    @Deprecated("Back is deliberately inert while an alarm is ringing")
    override fun onBackPressed() = Unit
}

@Composable
private fun RingScreen(alarm: Alarm?, onStop: () -> Unit, onSnooze: () -> Unit) {
    val title = when (alarm?.kind) {
        Alarm.KIND_TIMER -> "Timer"
        Alarm.KIND_REMINDER -> "Reminder"
        else -> "Alarm"
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(title.uppercase(), style = MaterialTheme.typography.labelLarge, color = Dim)
            Box(Modifier.height(12.dp))
            Text(
                Alarms.clockText(alarm?.atMillis ?: System.currentTimeMillis()),
                style = MaterialTheme.typography.displayLarge,
                color = Color.White,
            )
            val label = alarm?.label?.takeIf { it.isNotBlank() }
            if (label != null) {
                Box(Modifier.height(16.dp))
                Text(
                    label,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            BigButton("SNOOZE", Modifier.weight(1f), filled = false, onClick = onSnooze)
            BigButton("STOP", Modifier.weight(1f), filled = true, onClick = onStop)
        }
    }
}
