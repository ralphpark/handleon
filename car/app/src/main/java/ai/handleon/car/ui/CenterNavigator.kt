package ai.handleon.car.ui

import ai.handleon.car.centers.Center
import ai.handleon.driveprint.LatLon
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.helper.NaviHelper
import android.content.Context
import android.os.Handler
import android.os.Looper

/** Screen-owned SDK connection. Requests only after the SDK's connected callback. */
internal interface NavigationSession {
    fun connect(onConnected: () -> Unit, onFailed: () -> Unit)
    fun route(info: RouteInfo)
    fun close()
}

class CenterNavigator internal constructor(private val createSession: () -> NavigationSession,
                                           private val post: (() -> Unit) -> Unit) {
    constructor(context: Context) : this({ SdkNavigationSession(context.applicationContext) }, mainPoster())
    private var helper: NavigationSession? = null
    private var generation = 0

    fun request(center: Center, message: (String) -> Unit) = request(center.pos, center.name, center.address, message = message)

    fun request(destination: LatLon, name: String = "집", address: String = "", canRequest: () -> Boolean = { true }, message: (String) -> Unit) {
        close()
        if (!canRequest()) return
        val token = generation
        fun notify(text: String) { post { if (generation == token) message(text) } }
        notify("내비에 연결하고 있어요")
        try {
            val navi = createSession().also { helper = it }
            navi.connect(onConnected = {
                post {
                    if (generation == token && canRequest()) try {
                        navi.route(RouteInfo(latitude = destination.lat, longitude = destination.lon,
                            poiName = name, poiId = "", address = address, poiSubId = ""))
                        notify("길안내를 요청했어요. 내비 화면에서 확인해 주세요.")
                    } catch (_: Exception) { notify("길안내를 요청하지 못했어요. 잠시 후 다시 시도해 주세요.") }
                }
            }, onFailed = { notify("내비에 연결하지 못했어요. 잠시 후 다시 시도해 주세요.") })
        } catch (_: Exception) { notify("내비에 연결하지 못했어요. 잠시 후 다시 시도해 주세요.") }
    }

    fun close() { generation++; runCatching { helper?.close() }; helper = null }
}

private fun mainPoster(): (() -> Unit) -> Unit {
    val handler = Handler(Looper.getMainLooper())
    return { action -> handler.post { action() }; Unit }
}
private class SdkNavigationSession(context: Context) : NavigationSession {
    private val navi = NaviHelper(context)
    override fun connect(onConnected: () -> Unit, onFailed: () -> Unit) = navi.initialize(onConnected, { onFailed() })
    override fun route(info: RouteInfo) = navi.requestRoute(info)
    override fun close() = navi.release()
}
