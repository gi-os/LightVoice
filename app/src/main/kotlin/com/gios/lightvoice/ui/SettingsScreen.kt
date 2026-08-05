package com.gios.lightvoice.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.gios.lightvoice.Prefs
import com.gios.lightvoice.act.Bb
import com.gios.lightvoice.act.ContactBook
import com.gios.light.common.hw.WheelScroll
import com.gios.lightvoice.ptt.Grants
import com.gios.lightvoice.ui.theme.Dim
import com.gios.lightvoice.ui.theme.Faint
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Keys, voice, messaging, the push-to-talk key, and a plain report of what this
 * phone can actually do. The report matters more than it would elsewhere: LightOS
 * exposes almost no Settings screens, so without it every question ends in adb.
 */
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    var note by remember { mutableStateOf("") }

    // The longest page in the app by far, and the one you least want to swipe through with
    // a key field under your thumb — so the wheel scrolls it.
    val scroll = rememberScrollState()
    WheelScroll(scroll)

    val scan = rememberLauncherForActivityResult(ScanContract()) { result ->
        val payload = result.contents
        if (payload.isNullOrBlank()) return@rememberLauncherForActivityResult
        note = applyProvisioning(context, payload)
        version++
    }

    Column(
        Modifier.fillMaxSize().background(Color.Black).verticalScroll(scroll),
    ) {
        Section("KEYS")
        KeyField("Groq (speech)", Prefs.groqKey(context), "gsk_…") {
            Prefs.setGroqKey(context, it); version++
        }
        KeyField("Anthropic (understanding)", Prefs.anthropicKey(context), "sk-ant-…") {
            Prefs.setAnthropicKey(context, it); version++
        }
        if (Prefs.ttsProvider(context) == "openai") {
            KeyField("OpenAI (voice)", Prefs.openAiKey(context), "sk-…") {
                Prefs.setOpenAiKey(context, it); version++
            }
        }
        Row(Modifier.fillMaxWidth().padding(16.dp)) {
            BigButton("SCAN QR", Modifier.weight(1f)) {
                scan.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE))
            }
        }
        Hint(
            "Both keys are needed: Groq transcribes what you say, Claude Haiku decides " +
                "what to do with it. Together they cost a fraction of a cent per request, " +
                "and both stay on this phone.",
        )

        Section("VOICE")
        val speaking = remember(version) { Prefs.ttsEnabled(context) }
        MenuRow("Speak replies", detail = onOff(speaking)) {
            Prefs.setTtsEnabled(context, !speaking); version++
        }
        val provider = remember(version) { Prefs.ttsProvider(context) }
        MenuRow(
            "Voice from",
            detail = if (provider == "openai") "OPENAI" else "GROQ",
            sub = if (provider == "openai") "gpt-4o-mini-tts" else "playai-tts, same key as speech",
        ) {
            Prefs.setTtsProvider(context, if (provider == "openai") "groq" else "openai")
            version++
        }
        MenuRow("Voice", detail = null, sub = remember(version) { Prefs.ttsVoice(context) })
        Hint(
            "LightOS ships no speech engine, so a spoken reply has to be synthesised in " +
                "the cloud. Turn this off and answers are text only — which is faster.",
        )

        Section("MESSAGES")
        KeyField("BlueBubbles server", Prefs.bbUrl(context), "https://mac.tailnet.ts.net") {
            Prefs.setBbUrl(context, it); version++
        }
        KeyField("Server password", Prefs.bbPassword(context), "password") {
            Prefs.setBbPassword(context, it); version++
        }
        var syncing by remember { mutableStateOf(false) }
        Row(Modifier.fillMaxWidth().padding(16.dp)) {
            BigButton(
                if (syncing) "SYNCING…" else "SYNC CONTACTS",
                Modifier.weight(1f),
                enabled = !syncing,
            ) {
                syncing = true
            }
        }
        LaunchedEffect(syncing) {
            if (!syncing) return@LaunchedEffect
            note = withContext(Dispatchers.IO) {
                runCatching {
                    val reachable = Bb.reachable(Prefs.bbUrl(context), Prefs.bbPassword(context))
                    if (!reachable) return@runCatching "Couldn't reach the server."
                    val n = ContactBook.refreshFromServer(context)
                    "Pulled $n contacts from the Mac's address book."
                }.getOrElse { "Sync failed: ${it.message}" }
            }
            syncing = false
            version++
        }
        Hint(
            "Texting goes through your own BlueBubbles server, so it lands as a real " +
                "iMessage in the same thread LightChat shows — not as an SMS.",
        )

        Section("PUSH TO TALK")
        val pttOn = remember(version) { Prefs.pttEnabled(context) }
        MenuRow("Hardware key", detail = onOff(pttOn)) {
            Prefs.setPttEnabled(context, !pttOn); version++
        }
        MenuRow(
            "Bound to",
            detail = null,
            sub = Grants.keyName(remember(version) { Prefs.pttKeycode(context) }) +
                " · hold for half a second",
        )
        LearnKeyRow { version++ }
        val serviceOn = remember(version) { Grants.pttServiceEnabled(context) }
        MenuRow(
            "Accessibility service",
            detail = if (serviceOn) "ON" else "OFF",
            sub = if (serviceOn) {
                "Watching for key presses"
            } else {
                "Tap to open the system list and enable June"
            },
        ) { Grants.openAccessibilitySettings(context) }
        Hint(
            "A short press of the bound key still does what it always did — only a " +
                "deliberate half-second hold opens the mic, and only the release of that " +
                "hold is swallowed. Nothing here reads your screen.",
        )

        Section("THIS PHONE")
        val exact = remember(version) { Grants.exactAlarmsAllowed(context) }
        MenuRow("Exact alarms", detail = yesNo(exact), sub = "Needed for alarms to fire on time")
        MenuRow(
            "System speech engine",
            detail = yesNo(Grants.systemTtsPresent(context)),
            sub = "Expected to be absent; the cloud voice covers it",
        )
        MenuRow(
            "System recogniser",
            detail = yesNo(Grants.systemRecognizerPresent(context)),
            sub = "Expected to be absent; Whisper covers it",
        )
        MenuRow(
            "A clock app to hand alarms to",
            detail = yesNo(Grants.systemAlarmHandlerPresent(context)),
            sub = "Either way this app rings its own",
        )
        MenuRow(
            "Contacts known",
            detail = remember(version) { ContactBook.all(context).size.toString() },
            sub = "Device address book plus the Mac's, merged",
        )

        if (note.isNotBlank()) {
            Hint(note)
        }
        Gap(28)
    }
}

@Composable
private fun LearnKeyRow(onBound: () -> Unit) {
    val context = LocalContext.current
    var learning by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }

    MenuRow(
        "Learn a key",
        detail = if (learning) "LISTENING" else null,
        sub = status.ifBlank {
            "For the LPIII's custom button, which reports a code of its own"
        },
    ) {
        if (!learning) {
            Prefs.setPttLastSeen(context, 0)
            Prefs.setPttLearning(context, true)
            status = "Press and release the key you want."
            learning = true
        }
    }

    // Poll rather than push: the service writes what it saw into prefs and stays
    // ignorant of the UI, which keeps it to key codes and nothing else.
    LaunchedEffect(learning) {
        if (!learning) return@LaunchedEffect
        var waited = 0
        while (learning && waited < 20_000) {
            val seen = Prefs.pttLastSeen(context)
            if (seen != 0) {
                Prefs.setPttKeycode(context, seen)
                Prefs.setPttLearning(context, false)
                status = "Bound to ${Grants.keyName(seen)}."
                learning = false
                onBound()
                return@LaunchedEffect
            }
            delay(250)
            waited += 250
        }
        if (learning) {
            Prefs.setPttLearning(context, false)
            status = "Didn't see a key. Is the accessibility service on?"
            learning = false
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelSmall,
        color = Dim,
        modifier = Modifier.padding(start = 16.dp, top = 22.dp, bottom = 8.dp),
    )
    Rule()
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = Faint,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

@Composable
private fun KeyField(label: String, saved: String, placeholder: String, onSave: (String) -> Unit) {
    var draft by remember(saved) { mutableStateOf(saved) }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Dim)
        Gap(6)
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(placeholder, color = Faint) },
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                imeAction = ImeAction.Done,
            ),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                onDone = { onSave(draft) },
            ),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedBorderColor = Color.White,
                unfocusedBorderColor = Faint,
                cursorColor = Color.White,
            ),
        )
        if (draft.trim() != saved) {
            Gap(8)
            BigButton("SAVE", Modifier.fillMaxWidth()) { onSave(draft) }
        }
    }
}

private fun onOff(value: Boolean) = if (value) "ON" else "OFF"

private fun yesNo(value: Boolean) = if (value) "YES" else "NO"

/**
 * A provisioning QR, so keys never have to be typed on a 3.9-inch keyboard. Either a
 * JSON object of several fields or a single bare key, recognised by its prefix.
 */
private fun applyProvisioning(context: android.content.Context, payload: String): String {
    val trimmed = payload.trim()
    val json = runCatching { JSONObject(trimmed) }.getOrNull()
    if (json == null) {
        return when {
            trimmed.startsWith("sk-ant-") -> { Prefs.setAnthropicKey(context, trimmed); "Anthropic key saved." }
            trimmed.startsWith("gsk_") -> { Prefs.setGroqKey(context, trimmed); "Groq key saved." }
            trimmed.startsWith("sk-") -> { Prefs.setOpenAiKey(context, trimmed); "OpenAI key saved." }
            else -> "That QR didn't look like a key."
        }
    }
    val saved = ArrayList<String>()
    json.optString("groq").takeIf { it.isNotBlank() }?.let { Prefs.setGroqKey(context, it); saved.add("Groq") }
    json.optString("anthropic").takeIf { it.isNotBlank() }?.let { Prefs.setAnthropicKey(context, it); saved.add("Anthropic") }
    json.optString("openai").takeIf { it.isNotBlank() }?.let { Prefs.setOpenAiKey(context, it); saved.add("OpenAI") }
    json.optString("bb_url").takeIf { it.isNotBlank() }?.let { Prefs.setBbUrl(context, it); saved.add("server") }
    json.optString("bb_password").takeIf { it.isNotBlank() }?.let { Prefs.setBbPassword(context, it); saved.add("password") }
    return if (saved.isEmpty()) "Nothing in that QR I recognised." else "Saved: ${saved.joinToString(", ")}."
}
