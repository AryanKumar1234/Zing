package com.example.ntfyalarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/** Restarts the always-on listener after a reboot or an app update, once setup has been done. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences(NtfyAlarmService.PREFS, Context.MODE_PRIVATE)
        val name = prefs.getString(NtfyAlarmService.KEY_NAME, "").orEmpty()
        if (name.isBlank() || !prefs.getBoolean(NtfyAlarmService.KEY_SETUP_DONE, false)) return
        try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, NtfyAlarmService::class.java).setAction(NtfyAlarmService.ACTION_START)
            )
        } catch (_: Exception) {
        }
    }
}
