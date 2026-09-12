package dev.noblebits.overkey

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Brings the overlay back after a reboot or an update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> OverlayService.startIfWanted(context)
        }
    }
}
