package com.gios.lightvoice.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.gios.lightvoice.act.Alarms
import com.gios.lightvoice.data.Alarm
import com.gios.lightvoice.data.Store
import com.gios.light.common.hw.WheelScroll

/** Everything scheduled, and a way to delete one without talking. */
@Composable
fun AlarmsScreen() {
    val context = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    val alarms = remember(version) { Store.alarms(context).sortedBy { it.atMillis } }

    // A week of repeating alarms outruns the panel, so the wheel scrolls the list.
    val listState = rememberLazyListState()
    WheelScroll(listState)

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Text(
            "SCHEDULED",
            style = MaterialTheme.typography.labelSmall,
            color = com.gios.lightvoice.ui.theme.Dim,
            modifier = Modifier.padding(start = 16.dp, top = 18.dp, bottom = 10.dp),
        )
        Rule()
        if (alarms.isEmpty()) {
            EmptyState("Nothing scheduled.\nSay “wake me at seven” to add one.")
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize(), state = listState) {
            items(alarms, key = { it.id }) { alarm ->
                AlarmRow(alarm) {
                    Alarms.cancel(context, alarm.id)
                    version++
                }
                Rule()
            }
        }
    }
}

@Composable
private fun AlarmRow(alarm: Alarm, onDelete: () -> Unit) {
    var confirming by remember { mutableStateOf(false) }
    val kind = when (alarm.kind) {
        Alarm.KIND_TIMER -> "Timer"
        Alarm.KIND_REMINDER -> "Reminder"
        else -> "Alarm"
    }
    val repeat = Alarms.maskText(alarm.repeatDays).takeIf { it.isNotBlank() }
    MenuRow(
        label = Alarms.clockText(alarm.atMillis),
        detail = if (confirming) "DELETE" else null,
        sub = listOfNotNull(kind, repeat, alarm.label.takeIf { it.isNotBlank() })
            .joinToString(" · "),
        onClick = { if (confirming) onDelete() else confirming = true },
    )
}
