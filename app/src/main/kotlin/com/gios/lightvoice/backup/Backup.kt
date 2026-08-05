package com.gios.lightvoice.backup

import com.gios.light.common.sync.Contents
import com.gios.light.common.sync.FileStore
import com.gios.light.common.sync.LightSyncBackup

/**
 * What LightSync takes off this phone on behalf of the assistant.
 *
 * One store, because there is one file. Everything the app persists lives in the single
 * `lightvoice` SharedPreferences file — see [com.gios.lightvoice.Prefs], which says plainly why
 * there is no Room database here: the whole state is three API keys, a URL, a handful of flags
 * and two small JSON lists, and a database would have bought a ksp step and nothing else.
 *
 * The part that is genuinely unrecoverable is the alarm stack. LightVoice schedules its own
 * alarms rather than handing them to a clock app — LightOS has none — so the alarms, timers and
 * reminders exist nowhere but in this file. Lose it and a repeating weekday alarm is simply
 * gone, with no notification and nothing to notice until the morning it does not ring. Saved
 * notes are the same shape of loss. Those two are the reason this provider exists.
 *
 * The API keys and the settings ride along in the same file. They are cheap to replace — a QR
 * code off `docs/index.html` and a minute in Settings — but "cheap" is not "free", and there is
 * no way to exclude them without splitting the file, which would be a schema change for the
 * sake of tidiness. They are keys to third-party accounts, so the archive is worth the same care
 * as the phone: LightSync encrypts it, and it should not be handed anywhere else.
 *
 * Deliberately absent: cached audio. `cacheDir` holds the last recorded utterance
 * (`utterance.wav`) and the last spoken reply, both of which are rewritten on the next sentence
 * and meaningless a minute later. Backing them up would trade megabytes per run for nothing, and
 * they are voice recordings, so shipping them off the phone is an active cost rather than a
 * neutral one. `cacheDir` is not reachable from [Contents] anyway — the omission is stated here
 * so that nobody adds it later thinking it was an oversight.
 *
 * Also absent: the queued crash reports under `filesDir`. They belong to the phone that produced
 * them, and restoring them onto another one would file issues about hardware that was never
 * involved.
 */
class Backup : LightSyncBackup() {

    override fun label() = "Voice"

    override fun stores() = listOf(
        FileStore("main", Contents(prefs = listOf("lightvoice"))),
    )
}
