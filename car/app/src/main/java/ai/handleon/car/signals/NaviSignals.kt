package ai.handleon.car.signals

import ai.handleon.driveprint.GuidanceEnded
import ai.handleon.driveprint.GuidanceStarted
import ai.handleon.driveprint.LatLon
import ai.handleon.driveprint.Reroute
import ai.handleon.driveprint.Signal
import ai.handleon.driveprint.TurnAhead
import ai.handleon.driveprint.TurnPassed
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * NaviHelper: 안내 시작·끝·재탐색, TBT 맨 앞 회전이 바뀌면 이전 회전을 지난 것으로(TurnPassed) 새 회전을 예고로(TurnAhead), 제한속도.
 * GPS 가 10초 넘게 없으면 내비 현재 위치를 대신 쓴다.
 */
class NaviSignals(context: Context, private val sink: LatestSignals, private val lastGpsMs: () -> Long) {
    private val navi = NaviHelper(context.applicationContext)
    private val main = Handler(Looper.getMainLooper())
    private var guiding = false
    private var pendingDestination = false
    private var headKey: String? = null
    private var headDir: Signal? = null
    private var headDistance = 0.0
    var failed: String? = null
        private set

    private val poll = object : Runnable {
        override fun run() {
            navi.getTBTInfo()
            if (sink.now() - lastGpsMs() > 10_000) navi.getCurrentLocationInfo()
            main.postDelayed(this, 1000)
        }
    }

    private val listener = object : NaviHelperEventListener {
        override fun onRouteStarted(routeStartInfo: RouteStartInfo) = askDestination()
        override fun onRouteStateInfo(routeStateInfo: RouteStateInfo) {
            if (routeStateInfo.isRouting && !guiding) askDestination() // 앱이 안내 도중에 켜진 경우
        }
        override fun onDestinationInfo(destinationInfo: DestinationInfo) {
            if (!pendingDestination) return
            pendingDestination = false
            guiding = true
            sink.onNavi(GuidanceStarted(sink.now(), LatLon(destinationInfo.latitude, destinationInfo.longitude), destinationInfo.name))
        }
        override fun onRouteCancelled() = endGuidance()
        override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) = endGuidance()
        override fun onReRouteEnded() { if (guiding) sink.onNavi(Reroute(sink.now())) }
        override fun onTBTInfo(tbtInfoList: List<TBTInfo>) = onHead(tbtInfoList.firstOrNull())
        override fun onCurrentRoadSpeedLimit(speedLimit: Int) = sink.set(Key.LIMIT, speedLimit.takeIf { it > 0 })
        override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) {
            if (sink.now() - lastGpsMs() > 10_000) sink.set(Key.POS, LatLon(currentLocationInfo.latitude, currentLocationInfo.longitude))
        }
    }

    fun start() {
        runCatching {
            navi.initialize(
                onConnected = {
                    navi.addListener(listener)
                    navi.getRouteStateInfo()
                    main.post(poll)
                },
                onFailed = { failed = "내비"; Log.w(VehicleSignals.TAG, "NaviHelper 연결 실패: $it") },
            )
        }.onFailure { failed = "내비"; Log.w(VehicleSignals.TAG, "NaviHelper 초기화 실패: $it") }
    }

    fun stop() {
        main.removeCallbacksAndMessages(null)
        runCatching { navi.removeListener(listener); navi.release() }
    }

    private fun askDestination() {
        pendingDestination = true
        navi.getDestinationInfo()
    }

    private fun endGuidance() {
        if (!guiding) return
        guiding = false
        headKey = null; headDir = null
        sink.onNavi(GuidanceEnded(sink.now()))
    }

    // ponytail: 재탐색으로 맨 앞이 바뀐 것도 "지난 회전"으로 센다. 엔진은 Reroute 와 함께 보므로 지금은 둔다.
    private fun onHead(first: TBTInfo?) {
        if (!guiding) return
        val key = first?.let { "${it.type}|${it.description}" }
        val distance = first?.distance ?: 0.0
        // 같은 안내 문구가 연달아 오면(예: 좌회전 두 번) 거리가 다시 늘어난 것으로 바뀜을 안다
        val changed = key != headKey || distance > headDistance + 50
        headDistance = distance
        if (!changed) return
        val now = sink.now()
        headDir?.let { sink.onNavi(TurnPassed(now, it)) }
        headKey = key
        headDir = first?.type?.toSignal()
        headDir?.let { sink.onNavi(TurnAhead(now, it, distance)) }
    }
}
