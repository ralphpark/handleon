package ai.handleon.car.collect

import ai.handleon.car.HandleOnApp
import ai.handleon.car.R
import ai.handleon.car.signals.CarSignals
import ai.handleon.car.signals.LatestSignals
import ai.handleon.car.signals.LocationSignals
import ai.handleon.car.signals.NaviSignals
import ai.handleon.car.signals.VehicleSignals
import ai.handleon.driveprint.Features
import ai.handleon.driveprint.RawTrip
import ai.handleon.driveprint.TripSegmenter
import ai.handleon.driveprint.distanceKm
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import java.io.File
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * 앞에 화면이 없어도 도는 수집기. 1초마다 최신 신호를 샘플로 찍어 세그먼터에 넣고, 끝난 트립은 지표만 저장한다(원본 1초 신호는 버림).
 * 프로세스가 죽어도 진행 중 트립을 잃지 않게 30초마다 세그먼터를 파일에 남기고, 다시 켜지면 이어 붙인다(리뷰 #11).
 */
class CollectorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val signals = LatestSignals()
    private val segmenter = TripSegmenter()
    private lateinit var vehicle: VehicleSignals
    private lateinit var car: CarSignals
    private lateinit var location: LocationSignals
    private lateinit var navi: NaviSignals
    private val checkpoint by lazy { File(filesDir, "segmenter.json") }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running.value = true
        startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        runCatching { if (checkpoint.exists()) segmenter.restore(checkpoint.readText()) }
            .onFailure { Log.w(TAG, "체크포인트 복원 실패, 새로 시작: $it"); checkpoint.delete() }
        vehicle = VehicleSignals(this, signals).also { it.start() }
        car = CarSignals(this, signals).also { it.start() }
        location = LocationSignals(this, signals).also { it.start() }
        navi = NaviSignals(this, signals) { location.lastFixMs }.also { it.start() }
        scope.launch { tickLoop() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        scope.cancel()
        listOf(vehicle::stop, car::stop, location::stop, navi::stop).forEach { runCatching { it() } }
        try {
            // 하던 저장이 끝난 뒤 진행 중 트립을 닫아 저장한다. 데이터 지우기는 running 이 false 가 될 때까지 기다린다.
            runBlocking {
                scope.coroutineContext[Job]?.join()
                segmenter.flush()?.let { save(it) }
            }
            checkpoint.delete()
        } finally {
            liveState.value = null
            running.value = false
            super.onDestroy()
        }
    }

    private suspend fun tickLoop() {
        var lastTick = 0L
        var n = 0
        var liveEvents = LiveEvents()
        val graph = (application as HandleOnApp).graph
        while (scope.isActive) {
            val now = signals.now()
            val (naviEvents, telltales) = signals.drainEvents()
            naviEvents.forEach(segmenter::pushNavi)
            telltales.forEach(segmenter::pushTelltale)
            liveEvents = liveEvents.remember(naviEvents, telltales, now)
            val snapshot = signals.snapshot()
            val sample = SampleAssembler.toSample(now, snapshot)
            if (sample != null && sample.tMs != lastTick) {
                lastTick = sample.tMs
                segmenter.push(sample)?.let { save(it) }
                if (DEBUG_LOG) Log.d(TAG, "샘플 $sample")
            }
            if (++n % 30 == 0) runCatching { checkpoint.writeText(segmenter.snapshot()) }
            if (graph.presentation()) {
                try {
                    // current()·지표 계산 모두 이 틱에서만. 발표 모드 밖에서는 사본·DB 조회도 하지 않는다.
                    val current = segmenter.current()
                    val previous = liveState.value
                    val refresh = LiveViewAssembler.needsTripUpdate(current, previous, now)
                    val home = if (refresh) graph.tripRepository.home() else null
                    val familiar = if (refresh) graph.tripRepository.familiarCells() else emptySet()
                    liveState.value = LiveViewAssembler.from(graph.presentation(), snapshot, liveEvents, current, now, previous, home, familiar)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    // 시연 계산 실패가 기존 수집·저장을 멈추게 하지 않는다.
                    liveState.value = null
                    Log.w(TAG, "발표 화면 갱신 실패", e)
                }
            } else liveState.value = null
            delay(1000 - now % 1000)
        }
    }

    private suspend fun save(trip: RawTrip) {
        val graph = (application as HandleOnApp).graph
        val repo = graph.tripRepository
        runCatching {
            val features = Features.extract(trip, repo.home(), repo.familiarCells(), ZoneId.of("Asia/Seoul"))
            repo.saveTrip(features)
            Log.i(TRIP_TAG, "트립 저장: ${"%.1f".format(trip.distanceKm())} km")
            graph.engineRunner.run(graph.mode())
        }.onFailure { Log.e(TRIP_TAG, "트립 저장 실패", it) }
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "운전 습관 모으기", NotificationManager.IMPORTANCE_LOW))
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_handleon)
            .setContentTitle("핸들온")
            .setContentText("핸들온이 운전 습관을 조용히 모으고 있어요")
            .setOngoing(true)
            .build()
    }

    companion object {
        private val liveState = MutableStateFlow<LiveView?>(null)
        val live: StateFlow<LiveView?> = liveState.asStateFlow()
        private val running = MutableStateFlow(false)
        /** 서비스가 살아 있는 동안 true. onDestroy 의 마지막 저장까지 끝나야 false. */
        val isRunning: StateFlow<Boolean> = running.asStateFlow()
        const val TAG = "HandleOnSignals"
        const val TRIP_TAG = "HandleOnTrip"
        private const val CHANNEL = "collect"
        private const val NOTIFICATION_ID = 1
        private const val DEBUG_LOG = false // 실측 때는 true 로 켜서 초당 샘플을 logcat 에 남긴다

        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, CollectorService::class.java)) }
                .onFailure { Log.w(TAG, "수집 서비스 시작 실패: $it") }
        }
    }
}
