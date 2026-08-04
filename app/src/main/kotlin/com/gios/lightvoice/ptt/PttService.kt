package com.gios.lightvoice.ptt

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.gios.lightvoice.ListenActivity
import com.gios.lightvoice.Prefs
import com.gios.light.common.hw.LightKeys

/**
 * Global push-to-talk: a long-press of one hardware key opens the mic from anywhere
 * in the phone, including from the lock screen.
 *
 * An [AccessibilityService] with `flagRequestFilterKeyEvents` is the only way an app
 * can see keys it doesn't have focus for. It is declared with no event types and
 * `canRetrieveWindowContent="false"`, so it receives key codes and nothing else — no
 * screen content ever reaches it.
 *
 * The safety rule that shapes the whole design: **the key must keep working.** A
 * `DOWN` is never consumed, so a short press still changes the volume exactly as it
 * always did; only the trailing `UP` of a press that actually became a long-press is
 * swallowed, and by then the volume change has already been applied. If this service
 * misbehaves it can therefore never trap the user — worst case they get one volume
 * step they didn't ask for.
 */
class PttService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null
    private var swallowUp = false
    private var firedForThisPress = false

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onKeyEvent(event: KeyEvent): Boolean {
        // The wheel is never push-to-talk. It belongs to whatever is on screen — this app
        // scrolls with it, LightControl uses it phone-wide — so a turn bound here would open
        // the mic every time anything scrolled. Refused before learning mode can offer it as
        // a candidate, and before a binding made by an older build can act on it.
        if (LightKeys.of(event) != null) return false

        // Learning mode: report whatever was pressed so Settings can bind it, and get
        // out of the way. Never consume here — the key being learned may be the only
        // way out of whatever is on screen.
        if (Prefs.pttLearning(this)) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                Prefs.setPttLastSeen(this, event.keyCode)
            }
            return false
        }

        if (!Prefs.pttEnabled(this)) return false
        if (event.keyCode != Prefs.pttKeycode(this)) return false

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    firedForThisPress = false
                    schedule()
                }
                return false // let the volume change happen as usual
            }
            KeyEvent.ACTION_UP -> {
                cancelPending()
                val consume = swallowUp
                swallowUp = false
                return consume
            }
        }
        return false
    }

    override fun onUnbind(intent: Intent?): Boolean {
        cancelPending()
        return super.onUnbind(intent)
    }

    private fun schedule() {
        cancelPending()
        val run = Runnable {
            if (firedForThisPress) return@Runnable
            firedForThisPress = true
            swallowUp = true
            open()
        }
        pending = run
        handler.postDelayed(run, HOLD_MILLIS)
    }

    private fun cancelPending() {
        pending?.let { handler.removeCallbacks(it) }
        pending = null
    }

    /**
     * Starting an activity from here works normally when the screen is on. With the
     * screen off or the phone locked it additionally needs the `SYSTEM_ALERT_WINDOW`
     * appop, which on Android 14 is what exempts a sideloaded app from
     * background-activity-start restrictions (`appops set … SYSTEM_ALERT_WINDOW
     * allow` over adb — see the README).
     */
    private fun open() {
        runCatching {
            startActivity(
                Intent(this, ListenActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION,
                ),
            )
        }
    }

    private companion object {
        /** Long enough that a deliberate hold is unmistakable, short enough that you
         *  don't wonder whether it heard you. */
        const val HOLD_MILLIS = 550L
    }
}
