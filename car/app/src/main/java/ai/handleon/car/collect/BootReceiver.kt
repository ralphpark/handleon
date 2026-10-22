package ai.handleon.car.collect

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 시동(부팅) 때 수집을 다시 켠다. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) CollectorService.start(context)
    }
}
