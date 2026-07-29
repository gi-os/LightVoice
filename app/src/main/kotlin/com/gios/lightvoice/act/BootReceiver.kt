package com.gios.lightvoice.act

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** AlarmManager forgets everything across a reboot, and an app update cancels the
 *  app's pending intents, so both events re-arm from the stored list. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED ->
                Alarms.rescheduleAll(context)
        }
    }
}
