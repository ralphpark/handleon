package ai.handleon.car.engine

import ai.handleon.car.store.TripStore
import ai.handleon.driveprint.ConditionReport
import ai.handleon.driveprint.Driveprint
import ai.handleon.driveprint.LongReport
import ai.handleon.driveprint.Reset
import ai.handleon.driveprint.ResetKind
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

enum class Mode { MAEUM, CONDITION }

data class EngineState(val long: LongReport?, val condition: ConditionReport?, val updatedMs: Long)

class EngineRunner(
    private val repo: TripStore,
    private val engine: Driveprint = Driveprint(),
    private val clock: () -> ZonedDateTime = { ZonedDateTime.now(ZoneId.of("Asia/Seoul")) },
    private val onSaved: () -> Unit = {},
) {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(EngineState(null, null, 0))
    val state: StateFlow<EngineState> = mutableState.asStateFlow()

    /** 트립 저장 직후와 일일 갱신에서 호출한다. 먼저 저장한 결과만 상태로 공개한다. */
    suspend fun run(mode: Mode): EngineState = mutex.withLock {
        val now = clock().withZoneSameInstant(ZoneId.of("Asia/Seoul"))
        val trips = repo.trips()
        val labeledDays = repo.labels().keys
        val resets = repo.resets().mapNotNull { (day, kind) ->
            ResetKind.entries.firstOrNull { it.name == kind }?.let { Reset(day, it) }
        }.toSet()
        val next = withContext(Dispatchers.Default) {
            when (mode) {
                Mode.MAEUM -> EngineState(engine.longReport(trips, labeledDays,
                    resets, now.toLocalDate()), null, now.toInstant().toEpochMilli())
                Mode.CONDITION -> EngineState(null, engine.conditionReport(trips, labeledDays,
                    resets, now.toLocalDate()), now.toInstant().toEpochMilli())
            }
        }
        next.long?.let { repo.saveReport("LONG", Json.encodeToString(it)) }
        next.condition?.let { repo.saveReport("CONDITION", Json.encodeToString(it)) }
        onSaved()
        mutableState.value = next
        next
    }
}
