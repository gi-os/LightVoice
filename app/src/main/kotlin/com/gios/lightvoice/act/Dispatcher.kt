package com.gios.lightvoice.act

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.gios.lightvoice.Prefs
import com.gios.lightvoice.data.Alarm
import com.gios.lightvoice.data.Store
import com.gios.lightvoice.net.Plan
import com.gios.lightvoice.net.ToolCall
import com.gios.lightvoice.net.VoiceError
import org.json.JSONObject

/**
 * Runs what [com.gios.lightvoice.net.Brain] decided, and composes the confirmation
 * from what actually happened rather than from what was asked for. If the alarm
 * lands at 07:00 tomorrow, that is what you hear — the model never gets to narrate
 * a result it hasn't seen.
 *
 * Blocking by design; callers are already on a background dispatcher.
 */
object Dispatcher {

    fun run(c: Context, plan: Plan): String {
        if (plan.calls.isEmpty()) {
            return plan.text.ifBlank { "I'm not sure what to do with that." }
        }
        return plan.calls.joinToString(" ") { call ->
            runCatching { execute(c, call) }
                .getOrElse { e -> (e as? VoiceError)?.message ?: "That didn't work: ${e.message}" }
        }
    }

    private fun execute(c: Context, call: ToolCall): String = when (call.name) {
        "set_alarm" -> setAlarm(c, call.args)
        "set_timer" -> setTimer(c, call.args)
        "set_reminder" -> setReminder(c, call.args)
        "cancel_alarms" -> cancel(c, call.args)
        "list_alarms" -> listAlarms(c)
        "call_contact" -> callContact(c, call.args)
        "send_message" -> sendMessage(c, call.args)
        "add_note" -> addNote(c, call.args)
        "list_notes" -> listNotes(c)
        else -> "I don't know how to do that yet."
    }

    // ---- time -------------------------------------------------------------

    private fun setAlarm(c: Context, args: JSONObject): String {
        val (hour, minute) = parseTime(args.optString("time"))
            ?: return "I didn't get a time for that alarm."
        val repeat = args.optJSONArray("repeat")?.let { arr ->
            (0 until arr.length()).map { arr.optString(it) }
        }.orEmpty()
        val alarm = Alarms.setAlarm(c, hour, minute, args.optString("label").trim(), Alarms.maskOf(repeat))
        return Alarms.describe(alarm)
    }

    private fun setTimer(c: Context, args: JSONObject): String {
        val seconds = args.optLong("seconds", 0L)
        if (seconds <= 0L) return "How long should the timer run?"
        return Alarms.describe(
            Alarms.setTimer(c, seconds.coerceAtMost(24 * 3600L), args.optString("label").trim()),
        )
    }

    private fun setReminder(c: Context, args: JSONObject): String {
        val text = args.optString("text").trim().ifBlank { "reminder" }
        val inSeconds = args.optLong("in_seconds", 0L)
        val at = when {
            inSeconds > 0L -> System.currentTimeMillis() + inSeconds * 1000L
            else -> parseTime(args.optString("time"))
                ?.let { (h, m) -> Alarms.nextOccurrence(h, m, 0, System.currentTimeMillis()) }
                ?: return "When should I remind you?"
        }
        return Alarms.describe(Alarms.setReminder(c, at, text))
    }

    private fun cancel(c: Context, args: JSONObject): String {
        val which = args.optString("which").trim().lowercase()
        val all = Store.alarms(c)
        if (all.isEmpty()) return "Nothing was scheduled."
        if (which.isBlank() || which == "all" || which == "everything") {
            val n = Alarms.cancelAll(c)
            return "Cancelled ${n} ${if (n == 1) "alarm" else "alarms"}."
        }
        val hit = all.filter {
            it.label.lowercase().contains(which) ||
                which.contains(it.kind) ||
                Alarms.clockText(it.atMillis).lowercase().contains(which)
        }
        if (hit.isEmpty()) return "I couldn't find that one."
        hit.forEach { Alarms.cancel(c, it.id) }
        return if (hit.size == 1) "Cancelled the ${Alarms.clockText(hit[0].atMillis)} one."
        else "Cancelled ${hit.size} of them."
    }

    private fun listAlarms(c: Context): String {
        val all = Store.alarms(c).sortedBy { it.atMillis }
        if (all.isEmpty()) return "Nothing scheduled."
        return all.take(4).joinToString(" ") { alarm ->
            val when0 = Alarms.clockText(alarm.atMillis)
            val repeat = Alarms.maskText(alarm.repeatDays).takeIf { it.isNotBlank() }
            val label = alarm.label.takeIf { it.isNotBlank() }
            val kind = if (alarm.kind == Alarm.KIND_TIMER) "Timer" else "Alarm"
            buildString {
                append("$kind at $when0")
                if (repeat != null) append(" $repeat")
                if (label != null) append(", $label")
                append(".")
            }
        }
    }

    // ---- people -----------------------------------------------------------

    private fun callContact(c: Context, args: JSONObject): String {
        val spoken = args.optString("name").trim()
        val contact = ContactBook.resolve(c, spoken)
            ?: return "I don't have $spoken in your contacts."
        val number = contact.phone
            ?: return "I have no number for ${contact.name}."
        val canDial = c.checkSelfPermission(android.Manifest.permission.CALL_PHONE) ==
            PackageManager.PERMISSION_GRANTED
        val intent = Intent(
            if (canDial) Intent.ACTION_CALL else Intent.ACTION_DIAL,
            Uri.parse("tel:" + Uri.encode(number)),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            c.startActivity(intent)
            "Calling ${contact.name}."
        }.getOrElse { "I couldn't open the dialer." }
    }

    private fun sendMessage(c: Context, args: JSONObject): String {
        val spoken = args.optString("name").trim()
        val text = args.optString("text").trim()
        if (text.isBlank()) return "What should I say?"
        if (!Prefs.messagingConfigured(c)) {
            return "Messaging isn't set up — add your BlueBubbles server in Settings."
        }
        val contact = ContactBook.resolve(c, spoken)
            ?: return "I don't have $spoken in your contacts."
        val handle = contact.imessage ?: contact.phone
            ?: return "I have no iMessage handle for ${contact.name}."
        Bb.sendText(Prefs.bbUrl(c), Prefs.bbPassword(c), handle, text)
        return "Sent to ${contact.name.substringBefore(" ")}."
    }

    // ---- notes ------------------------------------------------------------

    private fun addNote(c: Context, args: JSONObject): String {
        val text = args.optString("text").trim()
        if (text.isBlank()) return "What should I note down?"
        Store.addNote(c, text)
        return "Noted."
    }

    private fun listNotes(c: Context): String {
        val notes = Store.notes(c).sortedByDescending { it.created }
        if (notes.isEmpty()) return "No notes yet."
        val head = notes.take(3).joinToString(" ") { it.text.trimEnd('.') + "." }
        val rest = notes.size - 3
        return if (rest > 0) "$head And $rest more." else head
    }

    // ---- parsing ----------------------------------------------------------

    /** "HH:MM", tolerating "7", "7:5", "07.30" and a stray am/pm the model left in. */
    fun parseTime(raw: String): Pair<Int, Int>? {
        val cleaned = raw.trim().lowercase()
        if (cleaned.isBlank()) return null
        val pm = cleaned.endsWith("pm")
        val am = cleaned.endsWith("am")
        val digits = cleaned.removeSuffix("am").removeSuffix("pm").trim()
            .replace('.', ':').replace(' ', ':')
        val parts = digits.split(":")
        var hour = parts.getOrNull(0)?.filter(Char::isDigit)?.toIntOrNull() ?: return null
        val minute = parts.getOrNull(1)?.filter(Char::isDigit)?.toIntOrNull() ?: 0
        if (pm && hour < 12) hour += 12
        if (am && hour == 12) hour = 0
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour to minute
    }
}
