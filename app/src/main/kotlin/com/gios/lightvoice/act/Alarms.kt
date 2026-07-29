package com.gios.lightvoice.act

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.gios.lightvoice.data.Alarm
import com.gios.lightvoice.data.Store
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Alarms, timers and reminders, all on [AlarmManager.setAlarmClock].
 *
 * LightOS is not guaranteed to have anything that answers
 * `AlarmClock.ACTION_SET_ALARM`, so handing the request to "the clock app" is not an
 * option — the assistant owns the whole path from schedule to ring. `setAlarmClock`
 * (rather than `setExactAndAllowWhileIdle`) is deliberate: it is the one scheduling
 * call the system treats as a user-visible alarm, so Doze never defers it and the
 * platform shows the next-alarm indicator.
 */
object Alarms {

    private val zone: ZoneId get() = ZoneId.systemDefault()

    fun setAlarm(c: Context, hour: Int, minute: Int, label: String, repeatDays: Int): Alarm {
        val at = nextOccurrence(hour, minute, repeatDays, System.currentTimeMillis())
        return commit(
            c,
            Alarm(
                id = Store.nextId(Store.alarms(c).map { it.id }),
                atMillis = at,
                label = label,
                kind = Alarm.KIND_ALARM,
                hour = hour,
                minute = minute,
                repeatDays = repeatDays,
            ),
        )
    }

    fun setTimer(c: Context, seconds: Long, label: String): Alarm = commit(
        c,
        Alarm(
            id = Store.nextId(Store.alarms(c).map { it.id }),
            atMillis = System.currentTimeMillis() + seconds * 1000L,
            label = label,
            kind = Alarm.KIND_TIMER,
        ),
    )

    fun setReminder(c: Context, atMillis: Long, text: String): Alarm = commit(
        c,
        Alarm(
            id = Store.nextId(Store.alarms(c).map { it.id }),
            atMillis = atMillis,
            label = text,
            kind = Alarm.KIND_REMINDER,
        ),
    )

    private fun commit(c: Context, alarm: Alarm): Alarm {
        Store.upsertAlarm(c, alarm)
        schedule(c, alarm)
        return alarm
    }

    fun schedule(c: Context, alarm: Alarm) {
        if (!alarm.enabled) return
        val am = c.getSystemService(AlarmManager::class.java) ?: return
        val show = PendingIntent.getActivity(
            c,
            alarm.id,
            Intent(c, RingActivity::class.java).putExtra(EXTRA_ID, alarm.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        am.setAlarmClock(AlarmManager.AlarmClockInfo(alarm.atMillis, show), fire(c, alarm.id))
    }

    fun cancel(c: Context, id: Int) {
        c.getSystemService(AlarmManager::class.java)?.cancel(fire(c, id))
        Store.deleteAlarm(c, id)
    }

    fun cancelAll(c: Context): Int {
        val all = Store.alarms(c)
        all.forEach { cancel(c, it.id) }
        return all.size
    }

    /** Re-arms everything after a reboot or an app update, dropping one-shots whose
     *  moment passed while the phone was off rather than ringing them late. */
    fun rescheduleAll(c: Context) {
        val now = System.currentTimeMillis()
        Store.alarms(c).forEach { alarm ->
            when {
                alarm.repeating -> {
                    val next = nextOccurrence(alarm.hour, alarm.minute, alarm.repeatDays, now)
                    Store.upsertAlarm(c, alarm.copy(atMillis = next))
                    schedule(c, alarm.copy(atMillis = next))
                }
                alarm.atMillis <= now -> Store.deleteAlarm(c, alarm.id)
                else -> schedule(c, alarm)
            }
        }
    }

    /** Called once an alarm has rung: repeating entries roll forward, the rest go away. */
    fun consume(c: Context, alarm: Alarm) {
        if (alarm.repeating) {
            val next = nextOccurrence(
                alarm.hour,
                alarm.minute,
                alarm.repeatDays,
                alarm.atMillis + 60_000L,
            )
            val rolled = alarm.copy(atMillis = next)
            Store.upsertAlarm(c, rolled)
            schedule(c, rolled)
        } else {
            Store.deleteAlarm(c, alarm.id)
        }
    }

    fun snooze(c: Context, alarm: Alarm, minutes: Long = 9) {
        val snoozed = alarm.copy(
            id = Store.nextId(Store.alarms(c).map { it.id }),
            atMillis = System.currentTimeMillis() + minutes * 60_000L,
            repeatDays = 0,
        )
        Store.upsertAlarm(c, snoozed)
        schedule(c, snoozed)
    }

    private fun fire(c: Context, id: Int): PendingIntent = PendingIntent.getBroadcast(
        c,
        id,
        Intent(c, AlarmReceiver::class.java)
            .setAction("$ACTION_FIRE.$id") // distinct action so equal-extras intents don't collapse
            .putExtra(EXTRA_ID, id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /**
     * The next epoch-milli this wall-clock time comes around. With no repeat mask
     * that is today if the time is still ahead, otherwise tomorrow. With a mask it
     * is the first selected weekday from now, checked out to eight days so "same
     * weekday next week" resolves.
     */
    fun nextOccurrence(hour: Int, minute: Int, repeatDays: Int, from: Long): Long {
        val h = hour.coerceIn(0, 23)
        val m = minute.coerceIn(0, 59)
        val now = Instant.ofEpochMilli(from).atZone(zone)
        val time = LocalTime.of(h, m)
        if (repeatDays == 0) {
            var candidate = LocalDateTime.of(now.toLocalDate(), time)
            if (!candidate.isAfter(now.toLocalDateTime())) candidate = candidate.plusDays(1)
            return candidate.atZone(zone).toInstant().toEpochMilli()
        }
        var day: LocalDate = now.toLocalDate()
        repeat(8) {
            if (repeatDays and bit(day.dayOfWeek) != 0) {
                val candidate = LocalDateTime.of(day, time)
                if (candidate.isAfter(now.toLocalDateTime())) {
                    return candidate.atZone(zone).toInstant().toEpochMilli()
                }
            }
            day = day.plusDays(1)
        }
        // Unreachable for any non-zero mask, but a sane fallback beats an exception.
        return from + 24 * 3600_000L
    }

    /** Bit 0 is Sunday, matching [java.util.Calendar]'s day numbering. */
    fun bit(day: DayOfWeek): Int = 1 shl (day.value % 7)

    fun maskOf(names: List<String>): Int {
        var mask = 0
        for (raw in names) {
            val n = raw.trim().lowercase()
            val day = when {
                n.startsWith("mon") -> DayOfWeek.MONDAY
                n.startsWith("tue") -> DayOfWeek.TUESDAY
                n.startsWith("wed") -> DayOfWeek.WEDNESDAY
                n.startsWith("thu") -> DayOfWeek.THURSDAY
                n.startsWith("fri") -> DayOfWeek.FRIDAY
                n.startsWith("sat") -> DayOfWeek.SATURDAY
                n.startsWith("sun") -> DayOfWeek.SUNDAY
                n == "weekdays" || n == "weekday" -> null.also {
                    mask = mask or 0b0111110 // Mon..Fri
                }
                n == "weekends" || n == "weekend" -> null.also {
                    mask = mask or 0b1000001 // Sun + Sat
                }
                n == "daily" || n == "every day" || n == "everyday" -> null.also {
                    mask = mask or 0b1111111
                }
                else -> null
            }
            if (day != null) mask = mask or bit(day)
        }
        return mask
    }

    private val clock: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a")

    fun clockText(atMillis: Long): String =
        clock.format(Instant.ofEpochMilli(atMillis).atZone(zone))

    fun maskText(mask: Int): String = when (mask) {
        0 -> ""
        0b1111111 -> "every day"
        0b0111110 -> "weekdays"
        0b1000001 -> "weekends"
        else -> DayOfWeek.entries
            .filter { mask and bit(it) != 0 }
            .joinToString(", ") { it.name.lowercase().take(3).replaceFirstChar(Char::uppercase) }
    }

    /** How the assistant says an alarm out loud. */
    fun describe(alarm: Alarm): String {
        val time = clockText(alarm.atMillis)
        val label = alarm.label.takeIf { it.isNotBlank() }
        return when (alarm.kind) {
            Alarm.KIND_TIMER -> {
                val mins = ChronoUnit.MINUTES.between(
                    Instant.now(),
                    Instant.ofEpochMilli(alarm.atMillis),
                )
                val body = if (mins < 1) "under a minute" else "$mins minute${plural(mins)}"
                if (label != null) "Timer for $body, $label." else "Timer set for $body."
            }
            Alarm.KIND_REMINDER -> "I'll remind you at $time: ${label ?: "reminder"}."
            else -> buildString {
                append("Alarm set for $time")
                if (alarm.repeating) append(" ${maskText(alarm.repeatDays)}")
                else if (isTomorrow(alarm.atMillis)) append(" tomorrow")
                if (label != null) append(", $label")
                append(".")
            }
        }
    }

    private fun plural(n: Long) = if (n == 1L) "" else "s"

    private fun isTomorrow(atMillis: Long): Boolean {
        val today = Instant.now().atZone(zone).toLocalDate()
        val then = Instant.ofEpochMilli(atMillis).atZone(zone).toLocalDate()
        return then.isAfter(today)
    }

    const val EXTRA_ID = "alarm_id"
    private const val ACTION_FIRE = "com.gios.lightvoice.FIRE"
}
