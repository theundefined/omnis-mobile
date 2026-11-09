package com.theundefined.omnis.guard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Przycisk „Przedłuż" w powiadomieniu o terminach. Niewyeksportowany (manifest) — intent wysyła
 * tylko nasze własne, niezmienne PendingIntent. Sam nic nie robi w sieci: zdejmuje powiadomienie i
 * zleca pracę WorkManagera (potrzebuje sieci i może trwać dłużej niż życie odbiornika).
 */
class RenewActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_RENEW) return
        val targets =
            intent.getStringArrayExtra(EXTRA_TARGETS).orEmpty().mapNotNull {
                RenewTarget.decode(it)
            }
        if (targets.isEmpty()) return
        val app = context.applicationContext
        GuardNotifier(app).apply {
            ensureChannels()
            showRenewPending()
        }
        GuardScheduler.renewFromNotification(app, targets.take(MAX_RENEW_TARGETS))
    }

    companion object {
        const val ACTION_RENEW = "com.theundefined.omnis.action.RENEW_FROM_NOTIFICATION"
        const val EXTRA_TARGETS = "targets"
    }
}
