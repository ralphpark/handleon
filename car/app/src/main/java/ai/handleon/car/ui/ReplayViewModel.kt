package ai.handleon.car.ui

import ai.handleon.car.engine.Mode
import ai.handleon.car.HandleOnApp
import ai.handleon.car.replay.*
import ai.handleon.driveprint.Driveprint
import android.content.Context
import androidx.lifecycle.ViewModel
import java.time.YearMonth
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Owns a selected synthetic playback, never a repository. Kept across Activity recreation. */
class ReplayViewModel(dispatcher: CoroutineDispatcher? = null) : ViewModel() {
    private val scope = CoroutineScope(SupervisorJob() + (dispatcher ?: Dispatchers.Main.immediate))
    private val computation = dispatcher ?: Dispatchers.Default
    private val mutable = MutableStateFlow(ReplayUi())
    val state = mutable.asStateFlow()
    private val running = MutableStateFlow(false)
    @Volatile private var visible = false
    @Volatile private var generation = 0L
    private var job: Job? = null

    fun setVisible(value: Boolean) { visible = value; running.value = value && !mutable.value.paused }
    fun pause() { mutable.update { it.copy(paused = !it.paused) }; running.value = visible && !mutable.value.paused }
    fun end() { generation++; job?.cancel(); job = null; running.value = false }

    fun start(context: Context, mode: Mode, restart: Boolean = false, persona: ReplayPersona? = null) {
        if (!restart && job != null) return
        val app = context.applicationContext as HandleOnApp
        val choices = ReplayPersona.forMode(mode)
        start(persona ?: choices.first(), choices, load = { selected ->
            withContext(Dispatchers.IO) {
                app.assets.open(selected.asset).bufferedReader().use { ReplayParser.parse(it.readText()) }
            }
        }, presentation = { app.graph.presentation() }, upload = { frame, active ->
            app.graph.carSync.uploadReplay(frame.longReport, frame.trips, frame.today,
                canUpload = { active() && !app.graph.driving && !app.graph.erasingData && app.graph.firstRunComplete() })
        })
    }

    internal fun start(persona: ReplayPersona, choices: List<ReplayPersona> = ReplayPersona.forMode(persona.mode),
                       load: suspend (ReplayPersona) -> ReplayData, presentation: () -> Boolean = { false },
                       upload: suspend (ReplayFrame, () -> Boolean) -> Boolean) {
        end()
        val version = generation
        mutable.value = ReplayUi(mode = persona.mode, persona = persona, choices = choices, total = 0)
        running.value = visible
        job = scope.launch {
            try {
                val data = load(persona)
                ensureActive()
                require(data.persona == persona.id && data.trips.isNotEmpty())
                val controller = ReplayController(data.trips, Driveprint())
                mutable.update { it.copy(total = controller.frameCount(persona)) }
                var last: ReplayFrame? = null
                // No producer prefetch: a hidden/paused first-drive scene cannot be skipped on return.
                controller.frames(persona, computation).collect { frame ->
                    running.first { it }
                    ensureActive()
                    if (version != generation) throw CancellationException("replay_changed")
                    val engine = Driveprint()
                    val readiness = engine.readiness(frame.trips, frame.weekly, frame.today, emptySet(), emptySet())
                    val month = if (persona == ReplayPersona.FATHER) YearMonth.from(frame.today).minusMonths(1)
                        else YearMonth.from(frame.today)
                    mutable.update { it.copy(start = it.start ?: month, displayDay = frame.today,
                        firstDrive = frame.firstDrive,
                        maeum = MaeumUi.from(frame.longReport, null, CollectionProgress.from(frame.trips), weekly = frame.weekly, readiness = readiness),
                        condition = frame.condition?.let(ConditionUi::from) ?: it.condition,
                        weekly = frame.weekly, tripLine = frame.tripLine, habits = frame.habits,
                        frame = it.frame + 1, stages = it.stages + frame.longReport.stage,
                        adapt = if (presentation()) frame.adapt else emptyList()) }
                    last = frame
                }
                ensureActive()
                mutable.update { it.copy(complete = true, uploadMessage = "재생 결과를 확인하고 있어요") }
                val owner = currentCoroutineContext()[Job]!!
                val current = { version == generation && owner.isActive }
                val active = { current() && visible }
                last?.let { frame ->
                    try {
                        // Preserve the existing local-only child condition replay.
                        val sent = persona != ReplayPersona.CHILD && upload(frame, active)
                        ensureActive()
                        if (current()) mutable.update { it.copy(uploadMessage = if (sent) "재생 결과를 보냈어요 · 모의" else "재생을 마쳤어요") }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) {
                        if (current()) mutable.update { it.copy(uploadMessage = "재생은 끝났어요. 결과는 보내지 못했어요.") }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (version == generation) mutable.update { it.copy(error = "재생 기록을 열지 못했어요. 다른 재생을 고르거나 다시 열어 주세요.") }
            }
        }
    }

    override fun onCleared() { end(); scope.cancel() }
}
