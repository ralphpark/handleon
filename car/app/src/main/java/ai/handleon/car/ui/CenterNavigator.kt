package ai.handleon.car.ui

import ai.handleon.car.centers.Center
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.helper.NaviHelper
import android.content.Context
import android.os.Handler
import android.os.Looper

/** Screen-owned SDK connection. Requests only after the SDK's connected callback. */
class CenterNavigator(private val context: Context) {
    private var helper: NaviHelper? = null
    private var generation = 0
    private val main = Handler(Looper.getMainLooper())

    fun request(center: Center, message: (String) -> Unit) {
        close()
        val token = generation
        fun notify(text: String) { main.post { if (generation == token) message(text) } }
        notify("내비에 연결하고 있어요")
        try {
            val navi = NaviHelper(context.applicationContext).also { helper = it }
            navi.initialize(onConnected = {
                main.post {
                    if (generation == token) try {
                        navi.requestRoute(RouteInfo(latitude = center.pos.lat, longitude = center.pos.lon,
                            poiName = center.name, poiId = "", address = center.address, poiSubId = ""))
                        notify("길안내를 요청했어요. 내비 화면에서 확인해 주세요.")
                    } catch (_: Exception) { notify("길안내를 요청하지 못했어요. 잠시 후 다시 시도해 주세요.") }
                }
            }, onFailed = { notify("내비에 연결하지 못했어요. 잠시 후 다시 시도해 주세요.") })
        } catch (_: Exception) { notify("내비에 연결하지 못했어요. 잠시 후 다시 시도해 주세요.") }
    }

    fun close() { generation++; runCatching { helper?.release() }; helper = null }
}
