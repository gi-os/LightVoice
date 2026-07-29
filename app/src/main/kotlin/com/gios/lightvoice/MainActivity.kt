package com.gios.lightvoice

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.view.WindowCompat
import com.gios.lightvoice.ui.AlarmsScreen
import com.gios.lightvoice.ui.AskPanel
import com.gios.lightvoice.ui.NotesScreen
import com.gios.lightvoice.ui.SettingsScreen
import com.gios.lightvoice.ui.TabBar
import com.gios.lightvoice.ui.theme.LightVoiceTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        // Asked once on first open rather than at the moment of speaking: a permission
        // sheet appearing over the mic is the one thing that breaks the illusion.
        runCatching {
            requestPermissions(
                arrayOf(
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.READ_CONTACTS,
                    Manifest.permission.CALL_PHONE,
                    Manifest.permission.POST_NOTIFICATIONS,
                ),
                1,
            )
        }
        setContent { LightVoiceTheme { Home() } }
    }

    override fun onPause() {
        super.onPause()
        // Leaving the app mid-sentence should not leave the mic open.
        if (VoiceEngine.listening) VoiceEngine.abandon()
    }
}

@Composable
private fun Home() {
    var tab by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Box(Modifier.weight(1f)) {
            when (tab) {
                0 -> AskPanel()
                1 -> AlarmsScreen()
                2 -> NotesScreen()
                else -> SettingsScreen()
            }
        }
        TabBar(tab, listOf("ASK", "ALARMS", "NOTES", "SETUP")) { tab = it }
    }
}
