package ai.handleon.car.collect

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 시동(부팅) 때 수집을 다시 켠다. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // 첫 실행 동의를 마친 경우에만(데이터를 지운 뒤에는 다시 동의할 때까지 모으지 않는다)
        val graph = (context.applicationContext as ai.handleon.car.HandleOnApp).graph
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && graph.firstRunComplete()) CollectorService.start(context)
    }
}
