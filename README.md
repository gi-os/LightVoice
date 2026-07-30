# LightVoice

**June** — a voice assistant for the **Light Phone III**. Hold a key, say what you want,
and it happens: alarms, timers, reminders, calls, iMessages, notes, plain questions.
Package `com.gios.lightvoice`, arm64, minSdk 29. Current released version: **v1.0.7**.

The assistant is named June (renamed from "Assistant" in v1.0.5); the repo and package
keep the LightVoice name on purpose, since changing the package would make Android treat
it as a different app and break Obtainium updates.

## Why this exists

LightOS has no Google Play Services, so the two platform building blocks a voice assistant
would normally reach for don't exist: `android.speech.SpeechRecognizer` has no provider to
bind to, and `android.speech.tts` has no engine installed. **Both ends of the loop —
speech in and the spoken response out — have to be cloud services**, for that reason:

1. **Capture** — `AudioRecord` straight to 16 kHz mono WAV, with a level meter and
   end-of-speech detection, so releasing the key early still works.
2. **Transcribe** — Groq `whisper-large-v3-turbo`
   (`/openai/v1/audio/transcriptions`). Contact names are passed as the decoder's
   `prompt`, which is what turns "text Alec" into the right person.
3. **Decide** — Claude Haiku, using tool use rather than regex, one round trip.
4. **Do** — the app performs the action itself and composes the confirmation from what
   actually happened, so it can never claim an alarm is set when it isn't.
5. **Speak** — Groq `playai-tts` by default (one key covers both directions, though its
   terms need accepting once in the Groq console), or OpenAI `gpt-4o-mini-tts`. Turn
   speech off and replies stay text-only.

Roughly, per request: a fifth of a cent to transcribe, a fifth to decide, a similar amount
again to speak — under half a cent a question with speech off.

## Quick start

1. Install the APK from [Releases](../../releases) or track the repo in Obtainium — CI
   releases only on pushes to `main` (see [Building](#building)).
2. Open **June → SETUP** and add a Groq key and an Anthropic key. Typing those on a
   3.9-inch keyboard is miserable, so open
   [gi-os.github.io/LightVoice](https://gi-os.github.io/LightVoice/) on a computer, fill
   the form in, and scan the QR on the phone instead.
3. Optional: add a [BlueBubbles](https://bluebubbles.app) URL and password, then **SYNC
   CONTACTS** to pull the Mac's address book.
4. Grant the permissions on first launch: microphone, contacts, phone, notifications.
5. Say something — hold the on-screen circle or, once wired up (below), a hardware key.

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

## Configuration and usage

### Push-to-talk from anywhere

The in-app circle needs no setup. To open the mic from anywhere on the phone, including a
locked screen:

```bash
# 1. Turn the accessibility service on. LightOS has no Settings UI for this list,
#    so it's set directly.
adb shell settings put secure enabled_accessibility_services \
  com.gios.lightvoice/com.gios.lightvoice.ptt.PttService
adb shell settings put secure accessibility_enabled 1

# 2. Let it open a window while the screen is off or locked. On Android 14 this
#    appop is the background-activity-start exemption, not an overlay grant.
adb shell appops set com.gios.lightvoice SYSTEM_ALERT_WINDOW allow
```

Then enable **Hardware key** in SETUP. It binds to volume-up by default; **Learn a key**
captures any other, including the LPIII's custom button, which reports a code of its own.

**The bound key keeps working.** A key-down is never swallowed — only a deliberate
half-second hold opens the mic, and only the release of that hold is consumed — so a
short press still changes the volume exactly as before. Worst case if anything misbehaves
is one unwanted volume step; the service can't trap the user.

To undo it: `adb shell settings put secure enabled_accessibility_services ""`. That empties
the same list [LightControl](https://github.com/gi-os/LightControl)'s setup writes to, so
if both are installed, colon-join both components instead of running either setup command
alone:

```bash
adb shell settings put secure enabled_accessibility_services \
  com.gios.lightvoice/com.gios.lightvoice.ptt.PttService:com.gios.lightcontrol/com.gios.lightcontrol.keys.ControlService
```

### The wheel

Turning the wheel scrolls whatever list is on screen — ALARMS, NOTES, or the SETUP page —
with nothing installed but June. LightOS relabels the wheel sensor's two scancodes in
`/system/usr/keylayout/Generic.kl`, and nothing intercepts them, so they reach the focused
window as ordinary key events; `MainActivity` reads them in `dispatchKeyEvent`, early
enough to beat the key-entry fields in SETUP (otherwise a turn there would type a letter).
No service, no permission, no root. Notches are paid off a fraction per frame rather than
applied as they arrive, since the sensor fires faster than the screen refreshes; the first
notch after a pause waits for a second to confirm it, since the wheel sits under a thumb.

**The wheel is deliberately not available as a push-to-talk key** — the PTT service
refuses it even if an older binding names one, because a turn is a scroll everywhere else
on the phone, and binding it here would open the mic every time a list is read. Holding
the wheel in, clicking it, and the camera button all do nothing in June; for those,
[LightControl](https://github.com/gi-os/LightControl) is the optional separate install
that gives the whole phone brightness, flashlight and camera-button actions, each
rebindable. It passes bare turns straight through to `com.gios.*`, so installing it does
not take June's own scrolling away.

```bash
adb install -r LightControl-v1.0.x.apk

adb shell settings put secure enabled_accessibility_services \
  com.gios.lightcontrol/com.gios.lightcontrol.keys.ControlService
adb shell settings put secure accessibility_enabled 1

adb shell appops set com.gios.lightcontrol WRITE_SETTINGS allow
adb shell appops set com.gios.lightcontrol SYSTEM_ALERT_WINDOW allow
```

### Alarms

Alarms are **this app's own**, not `AlarmClock.ACTION_SET_ALARM` — nothing on LightOS is
guaranteed to answer that intent. The stack is `AlarmManager.setAlarmClock` +
`USE_EXACT_ALARM` (install-granted; unlike `SCHEDULE_EXACT_ALARM` it needs no Settings
screen) → `RingService` foreground service → `RingActivity` with `showWhenLocked` /
`turnScreenOn`, reachable both by full-screen intent and by a direct start (the latter
needs the same `SYSTEM_ALERT_WINDOW` appop as push-to-talk above). The alarm object
travels inside the receiver's intent, not just its id — the receiver deletes a one-shot
the instant it fires, so an id alone would resolve to nothing by the time the service
reads its extras. The SETUP tab self-reports engine/recogniser/`SET_ALARM`-handler
presence, since LightOS has no Settings screens to check any of that from outside the app.

### Texting

Goes through your own BlueBubbles server, `POST /api/v1/chat/new` with a single address —
AppleScript's send-to-buddy lands in the existing thread, so there's no chat guid to
resolve and no Private API needed. Shows up in
[LightChat](https://github.com/gi-os/LightChat) like any other message. Contacts merge
`ContactsContract` with the Mac address book pulled from `GET /api/v1/contact`.

## Building

```bash
./gradlew :app:assembleRelease
```

CI builds every push and verifies the signing certificate against
`signing-fingerprint.txt` and that the package declares a launcher icon; **pushes to
`main` also cut a GitHub Release** — every other branch builds and verifies and stops,
which is the way to prove a change without cutting an Obtainium update. The keystore is
committed on purpose (`keystore/lightvoice.jks`) — this is a sideloaded personal app, and
a stable certificate is what lets Obtainium update it in place.

Regenerate the launcher icon with `python3 scripts/generate_icon.py`.

Setup QR page is GitHub Pages from `/docs` at
<https://gi-os.github.io/LightVoice/>.

## Contributing

Issues and PRs welcome.

- `enabled_accessibility_services` is a single shared list — any change to the PTT setup
  flow needs to keep the colon-join behavior working for anyone who also runs
  LightControl.
- Keep the spoken confirmation composed from what the dispatcher actually did, not from
  what the model claims — that's the guarantee the app makes and it should stay true for
  new intents too.
- CI publishes a release on every push to `main` — verify locally before pushing there.

## Version history

| Version | Change |
| --- | --- |
| v1.0.7 | Say what the wheel needs, and warn about the accessibility list (docs) |
| v1.0.6 | Scroll the lists and the setup page with the hardware wheel |
| v1.0.5 | Name the assistant June |
| v1.0.4 | Fix the ring race, a leaked meter collector, and an API-31 call on minSdk 29 |
| — | Prefs: drop the unused Context extension properties |
| — | LightVoice: a voice assistant for the Light Phone III (initial commit) |

## Licence

MIT.
