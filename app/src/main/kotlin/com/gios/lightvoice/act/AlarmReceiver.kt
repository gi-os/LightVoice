package com.gios.lightvoice.act

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.gios.lightvoice.data.Store

/** Wakes on the exact alarm and hands straight to [RingService], which owns the
 *  wake lock, the sound and the full-screen intent. A receiver has ~10 seconds and
 *  no window rights of its own, so it does nothing else here. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(Alarms.EXTRA_ID, -1)
        val alarm = Store.alarm(context, id) ?: return
        context.startForegroundService(
            Intent(context, RingService::class.java)
                .setAction(RingService.ACTION_RING)
                .putExtra(Alarms.EXTRA_ID, id),
        )
        // Roll a repeating alarm forward now, so the next one is armed even if the
        // user never touches the ring screen.
        Alarms.consume(context, alarm)
    }
}
