package ai.handleon.car.ui

import ai.handleon.car.engine.Mode
import ai.handleon.car.HandleOnApp
import ai.handleon.car.replay.ReplayController
import ai.handleon.car.replay.ReplayParser
import ai.handleon.driveprint.Driveprint
import ai.handleon.driveprint.LongReport
import android.content.Context
import androidx.lifecycle.ViewModel
import java.time.Instant
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** UI playback owner; never receives a repository. Kept across Activity recreation. */
class ReplayViewModel : ViewModel() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutable = MutableStateFlow(ReplayUi())
    val state = mutable.asStateFlow()
    private val running = MutableStateFlow(false)
    private var visible = false
    private var job: Job? = null

    fun setVisible(value: Boolean) { visible = value; running.value = value && !mutable.value.paused }
    fun pause() { mutable.update { it.copy(paused = !it.paused) }; running.value = visible && !mutable.value.paused }
    fun end() { job?.cancel(); job = null; running.value = false }

    fun start(context: Context, mode: Mode, restart: Boolean = false) {
        if (!restart && job != null && mutable.value.mode == mode) return
        end()
        mutable.value = ReplayUi(mode = mode)
        running.value = visible
        val app = context.applicationContext
        job = scope.launch {
            try {
                val trips = withContext(Dispatchers.IO) {
                    app.assets.open("replay/${if (mode == Mode.MAEUM) "father" else "child"}.json").bufferedReader().use { ReplayParser.parse(it.readText()).trips }
                }
                val dates = trips.map { Instant.ofEpochMilli(it.startMs).atZone(KST).toLocalDate() }.sorted()
                val first = dates.firstOrNull()?.let(YearMonth::from)
                mutable.update { it.copy(start = first, total = if (mode == Mode.MAEUM && first != null)
                    ChronoUnit.MONTHS.between(first, YearMonth.from(dates.last())).toInt() + 1 else dates.distinct().size) }
                val controller = ReplayController(trips, Driveprint())
                var finalReport: LongReport? = null
                if (mode == Mode.MAEUM) controller.monthly().flowOn(Dispatchers.Default).buffer(0).collect { report ->
                    running.first { it }
                    val month = first?.plusMonths(mutable.value.frame.toLong())
                    val progress = CollectionProgress.from(trips.filter { trip ->
                        month != null && YearMonth.from(Instant.ofEpochMilli(trip.startMs).atZone(KST)) <= month
                    })
                    // Assets have no home metadata: do not invent a registered home.
                    mutable.update { it.copy(maeum = MaeumUi.from(report, null, progress), frame = it.frame + 1, stages = it.stages + report.stage) }
                    finalReport = report
                } else controller.daily().flowOn(Dispatchers.Default).buffer(0).collect { report ->
                    running.first { it }
                    mutable.update { it.copy(condition = ConditionUi.from(report), frame = it.frame + 1) }
                }
                mutable.update { it.copy(complete = true) }
                finalReport?.let { report ->
                    try { (app as HandleOnApp).graph.carSync.uploadReplay(report) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { /* Playback remains complete; no sensitive network errors enter UI/logs. */ }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.update { it.copy(error = "재생 기록을 열지 못했어요. 재생을 끝내고 다시 열어 주세요.") } }
        }
    }

    override fun onCleared() { scope.cancel() }
}
