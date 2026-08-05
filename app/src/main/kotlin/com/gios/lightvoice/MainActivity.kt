package com.gios.lightvoice

import android.Manifest
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.view.WindowCompat
import com.gios.light.common.hw.LightKey
import com.gios.light.common.hw.LightKeys
import com.gios.light.common.hw.LocalWheelBus
import com.gios.light.common.hw.WheelBus
import com.gios.lightvoice.ui.AlarmsScreen
import com.gios.lightvoice.ui.AskPanel
import com.gios.lightvoice.ui.NotesScreen
import com.gios.lightvoice.ui.SettingsScreen
import com.gios.lightvoice.ui.TabBar
import com.gios.lightvoice.ui.theme.LightVoiceTheme
import com.gios.light.common.report.LightReport
import com.gios.light.common.report.ReportOverlay

class MainActivity : ComponentActivity() {

    /** Wheel notches on their way to whichever tab is showing. */
    private val wheel = WheelBus()

    /**
     * Every hardware key arrives here first — `DecorView` calls the window callback before
     * it walks the view hierarchy — so a turn is read before the focused key field in
     * Settings can take it as a letter. Both halves of a notch are consumed: one notch is a
     * complete DOWN+UP pair, and the UP would otherwise land as a keypress.
     *
     * This is the app's own window only. Push-to-talk is a separate path entirely — an
     * accessibility service, watching one key everywhere — and it lets the wheel through.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        when (LightKeys.of(event)) {
            LightKey.WheelUp -> {
                if (event.action == KeyEvent.ACTION_DOWN) wheel.send(1)
                return true
            }
            LightKey.WheelDown -> {
                if (event.action == KeyEvent.ACTION_DOWN) wheel.send(-1)
                return true
            }
            else -> Unit
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // First thing, before anything else can throw. This is the one call the shared
        // reporting module needs: it records what the app calls itself and how it is allowed
        // to file issues, and arms the crash handler on the way through. The token cannot be
        // read from inside the library — a library has its own BuildConfig, not the app's —
        // so it is handed in here. Blank is a working build; reports queue for a later one.
        LightReport.install(
            context = this,
            appName = "LightVoice",
            label = "voice",
            token = BuildConfig.REPORT_TOKEN,
            repo = BuildConfig.REPORT_REPO,
        )
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
        setContent {
            LightVoiceTheme {
                CompositionLocalProvider(LocalWheelBus provides wheel) { Home() }
                // Shake to report, the crash offer on next launch, and the app's own noticed
                // failures. A sibling, not a wrapper — the sheet is its own window, so it covers
                // the app whether or not it contains it.
                ReportOverlay()
            }
        }
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
            // One tab at a time, by construction: an unselected tab is not composed, so no
            // off-screen list is left listening for wheel notches.
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
