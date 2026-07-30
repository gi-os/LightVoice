# LightVoice

**June** — a voice assistant for the Light Phone III. Hold a key, say what you want,
and it happens — alarms, timers, reminders, calls, iMessages, notes, and plain questions.
Siri's job description before Siri became a chatbot.

<p align="center">
  <em>June · com.gios.lightvoice · arm64 · minSdk 29</em>
</p>

## What it does

| Say | What happens |
| --- | --- |
| "Wake me at seven" | Alarm at 07:00, rung by this app |
| "Alarm for six thirty on weekdays" | Repeating alarm, Mon–Fri |
| "Timer for ten minutes" | Countdown, full-screen ring |
| "Remind me to call the landlord at four" | Reminder with the text on screen |
| "What's set?" / "Cancel everything" | Reads back or clears the schedule |
| "Call Alex" | Dials from the merged address book |
| "Text Alex I'm running late" | Real iMessage through your BlueBubbles server |
| "Note that the rent is due Friday" | Saved to Notes |
| "How long does a hard-boiled egg take?" | Answered in a sentence, spoken aloud |

## How it works

Nothing on a Light Phone III can do the first or the last step of this, so both are
borrowed from elsewhere:

1. **Capture** — `AudioRecord` straight to 16 kHz mono WAV, with a level meter and
   end-of-speech detection, so you can let go of the key early and it still works.
2. **Transcribe** — Groq `whisper-large-v3-turbo`. There is no Play Services on
   LightOS, so `android.speech.SpeechRecognizer` has no provider to bind to; the
   platform path simply doesn't exist. Your contact names are passed as a decoder
   prompt, which is what turns "text Alec" into the right person.
3. **Decide** — Claude Haiku, using tool use rather than regex. One round trip.
4. **Do** — the app performs the action itself and composes the confirmation from
   what actually happened, so it can never tell you an alarm is set when it isn't.
5. **Speak** — PlayAI Dialog on Groq (one key for both directions), or OpenAI TTS.
   LightOS ships no TTS engine either. Turn speech off and replies are text only.

Alarms are the app's own: `AlarmManager.setAlarmClock`, a foreground service for the
sound, and a full-screen activity with `showWhenLocked` + `turnScreenOn`. Handing the
request to a clock app via `AlarmClock.ACTION_SET_ALARM` isn't an option, because
LightOS isn't guaranteed to have anything that answers it — the Settings tab reports
whether yours does.

Texting goes through your own [BlueBubbles](https://bluebubbles.app) server over
`POST /api/v1/chat/new`, which lands in the existing thread for a known address and
starts one otherwise. It shows up in [LightChat](https://github.com/gi-os/LightChat)
like any other message.

## Setup

1. Install the APK from [Releases](../../releases), or track the repo in Obtainium.
2. Open **June → SETUP** and add a Groq key and an Anthropic key. Typing those
   on a 3.9-inch keyboard is miserable, so open
   [the setup page](https://gi-os.github.io/LightVoice/) on a computer, fill it in,
   and scan the QR instead.
3. Optional: add your BlueBubbles URL and password, then **SYNC CONTACTS** to pull
   the Mac's address book.
4. Grant the permissions it asks for on first launch: microphone, contacts, phone,
   notifications.

### Push-to-talk from anywhere

The in-app circle works with no further setup. To open the mic from anywhere in the
phone, including a locked screen:

```bash
# 1. Turn the accessibility service on. LightOS has no Settings UI for this list,
#    so set it directly.
adb shell settings put secure enabled_accessibility_services \
  com.gios.lightvoice/com.gios.lightvoice.ptt.PttService
adb shell settings put secure accessibility_enabled 1

# 2. Let it open a window while the screen is off or locked. On Android 14 this
#    appop is the background-activity-start exemption, not an overlay grant.
adb shell appops set com.gios.lightvoice SYSTEM_ALERT_WINDOW allow
```

Then enable **Hardware key** in SETUP. It binds to volume-up by default; **Learn a
key** captures any other, including the LPIII's custom button, which reports a code
of its own.

**The bound key keeps working.** A key-down is never swallowed, so a short press
still changes the volume exactly as before; only a deliberate half-second hold opens
the mic, and only the release of that hold is consumed. Worst case, if anything about
this misbehaves, you get one volume step you didn't ask for — it can't trap you.

To undo it:

```bash
adb shell settings put secure enabled_accessibility_services ""
```

### The wheel

Turning the wheel scrolls the list you are looking at — ALARMS, NOTES, or the SETUP page.
Light relabelled the wheel sensor's two scancodes in `/system/usr/keylayout/Generic.kl` and
nothing in the system intercepts them, so they reach the focused window as ordinary key
events and `MainActivity` reads them in `dispatchKeyEvent`, early enough to beat the key
fields in SETUP, which would otherwise take a turn as a letter.

Notches are paid off a fraction per frame rather than applied as they arrive, because the
sensor fires faster than the screen refreshes and a spin applied notch-by-notch is a stack of
jumps; the first notch after a pause also waits for a second to confirm it, because the wheel
sits under a thumb. The long version is in
[LightNews](https://github.com/gi-os/LightNews#the-wheel-and-the-camera-button).

The wheel is *not* available as a push-to-talk key, and the service refuses it even if an
older binding names one: a turn is a scroll everywhere on this phone, so binding it here
would open the mic every time you read a list. Only the turns are handled at all — the wheel
click and the camera button belong to
[LightControl](https://github.com/gi-os/LightControl), which owns them phone-wide.

## Cost

Per request, roughly: a fifth of a cent to transcribe, a fifth to decide, and a
similar amount again to speak. Speech off, it's under half a cent a question.

## Build

```bash
./gradlew :app:assembleRelease
```

CI builds every push and verifies the signing certificate against
`signing-fingerprint.txt` and that the package declares a launcher icon; pushes to
`main` also cut a GitHub Release. The keystore is committed on purpose — this is a
sideloaded personal app, and a stable certificate is what lets Obtainium update it in
place.

Regenerate the launcher icon with `python3 scripts/generate_icon.py`.
