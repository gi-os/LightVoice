package com.gios.lightvoice.data

import android.content.Context
import com.gios.lightvoice.Prefs
import java.util.Calendar
import org.json.JSONArray
import org.json.JSONObject

/**
 * A scheduled ring. [kind] only changes how it is described back to you — an alarm,
 * a countdown timer and a reminder all end in the same full-screen ring.
 *
 * [repeatDays] is a bitmask over [Calendar.SUNDAY]..[Calendar.SATURDAY] (bit 0 =
 * Sunday); 0 means fire once and delete. Repeating entries keep [hour]/[minute] as
 * the source of truth and recompute [atMillis] each time they fire, so a weekday
 * alarm never drifts across a DST boundary.
 */
data class Alarm(
    val id: Int,
    val atMillis: Long,
    val label: String = "",
    val kind: String = KIND_ALARM,
    val hour: Int = -1,
    val minute: Int = -1,
    val repeatDays: Int = 0,
    val enabled: Boolean = true,
) {
    val repeating: Boolean get() = repeatDays != 0

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("at", atMillis).put("label", label).put("kind", kind)
        .put("hour", hour).put("minute", minute).put("repeat", repeatDays).put("on", enabled)

    companion object {
        const val KIND_ALARM = "alarm"
        const val KIND_TIMER = "timer"
        const val KIND_REMINDER = "reminder"

        fun fromJson(o: JSONObject) = Alarm(
            id = o.optInt("id"),
            atMillis = o.optLong("at"),
            label = o.optString("label", ""),
            kind = o.optString("kind", KIND_ALARM),
            hour = o.optInt("hour", -1),
            minute = o.optInt("minute", -1),
            repeatDays = o.optInt("repeat", 0),
            enabled = o.optBoolean("on", true),
        )
    }
}

data class Note(val id: Int, val text: String, val created: Long) {
    fun toJson(): JSONObject =
        JSONObject().put("id", id).put("text", text).put("created", created)

    companion object {
        fun fromJson(o: JSONObject) =
            Note(o.optInt("id"), o.optString("text"), o.optLong("created"))
    }
}

/** JSON-in-SharedPreferences persistence for alarms and notes. */
object Store {

    fun alarms(c: Context): List<Alarm> = read(Prefs.alarmsJson(c)) { Alarm.fromJson(it) }

    fun saveAlarms(c: Context, list: List<Alarm>) =
        Prefs.setAlarmsJson(c, write(list) { it.toJson() })

    fun alarm(c: Context, id: Int): Alarm? = alarms(c).firstOrNull { it.id == id }

    fun upsertAlarm(c: Context, alarm: Alarm) {
        val list = alarms(c).filter { it.id != alarm.id } + alarm
        saveAlarms(c, list.sortedBy { it.atMillis })
    }

    fun deleteAlarm(c: Context, id: Int) = saveAlarms(c, alarms(c).filter { it.id != id })

    fun notes(c: Context): List<Note> = read(Prefs.notesJson(c)) { Note.fromJson(it) }

    fun addNote(c: Context, text: String): Note {
        val note = Note(nextId(notes(c).map { it.id }), text.trim(), System.currentTimeMillis())
        Prefs.setNotesJson(c, write(notes(c) + note) { it.toJson() })
        return note
    }

    fun deleteNote(c: Context, id: Int) =
        Prefs.setNotesJson(c, write(notes(c).filter { it.id != id }) { it.toJson() })

    /** Smallest unused positive id. Ids double as [android.app.PendingIntent] request
     *  codes, so reusing a just-freed one is fine but colliding with a live one is not. */
    fun nextId(taken: List<Int>): Int {
        var id = 1
        while (id in taken) id++
        return id
    }

    private fun <T> read(json: String, parse: (JSONObject) -> T): List<T> = runCatching {
        val arr = JSONArray(json)
        (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(parse) }
    }.getOrDefault(emptyList())

    private fun <T> write(list: List<T>, dump: (T) -> JSONObject): String {
        val arr = JSONArray()
        list.forEach { arr.put(dump(it)) }
        return arr.toString()
    }
}
