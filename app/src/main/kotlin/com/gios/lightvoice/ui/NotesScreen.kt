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
import com.gios.lightvoice.data.Note
import com.gios.lightvoice.data.Store
import com.gios.light.common.hw.WheelScroll
import com.gios.lightvoice.ui.theme.Dim
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun NotesScreen() {
    val context = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    val notes = remember(version) { Store.notes(context).sortedByDescending { it.created } }

    // Notes are dictated, so this list only grows; the wheel is the way through it.
    val listState = rememberLazyListState()
    WheelScroll(listState)

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Text(
            "NOTES",
            style = MaterialTheme.typography.labelSmall,
            color = Dim,
            modifier = Modifier.padding(start = 16.dp, top = 18.dp, bottom = 10.dp),
        )
        Rule()
        if (notes.isEmpty()) {
            EmptyState("No notes yet.\nSay “note that the rent is due Friday”.")
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize(), state = listState) {
            items(notes, key = { it.id }) { note ->
                NoteRow(note) {
                    Store.deleteNote(context, note.id)
                    version++
                }
                Rule()
            }
        }
    }
}

private val stamp: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM, h:mm a")

@Composable
private fun NoteRow(note: Note, onDelete: () -> Unit) {
    var confirming by remember { mutableStateOf(false) }
    MenuRow(
        label = note.text,
        detail = if (confirming) "DELETE" else null,
        sub = stamp.format(Instant.ofEpochMilli(note.created).atZone(ZoneId.systemDefault())),
        onClick = { if (confirming) onDelete() else confirming = true },
    )
}
