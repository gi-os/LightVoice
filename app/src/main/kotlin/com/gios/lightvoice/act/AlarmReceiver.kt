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
        // The whole alarm travels in the intent, not just its id: consume() below
        // deletes a one-shot from the store immediately, and the service reads its
        // extras later, so an id alone would resolve to nothing and never ring.
        context.startForegroundService(
            Intent(context, RingService::class.java)
                .setAction(RingService.ACTION_RING)
                .putExtra(Alarms.EXTRA_ID, id)
                .putExtra(RingService.EXTRA_ALARM, alarm.toJson().toString()),
        )
        // Roll a repeating alarm forward now, so the next one is armed even if the
        // user never touches the ring screen.
        Alarms.consume(context, alarm)
    }
}
