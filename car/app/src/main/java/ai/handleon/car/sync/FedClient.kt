package ai.handleon.car.sync

import ai.handleon.car.store.TripStore
import ai.handleon.driveprint.HolidayModel
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

data class FedUi(val enabled: Boolean = false, val busy: Boolean = false, val pendingOff: Boolean = false,
                 val modelRound: Int? = null, val submitted: Int? = null, val error: String? = null)

class FedClient(private val client: SupabaseClient, private val repo: TripStore, private val presentation: () -> Boolean,
                private val consent: FedConsentStore,
                private val driverId: () -> String? = { null }, private val virtuals: suspend () -> List<FedVirtual> = { emptyList() },
                private val available: () -> Boolean = { true }, private val linkVersion: () -> Long = { 0 }) {
    private val mutex = Mutex()
    private val consentMutex = Mutex()
    private val revision = AtomicLong()
    private val intentRevision = AtomicLong()
    private val mutable = MutableStateFlow(FedUi())
    val state = mutable.asStateFlow()
    private val zone = ZoneId.of("Asia/Seoul")
    private data class Link(val driver: String, val version: Long)
    private fun owner() = driverId().takeIf { client.configured && available() }?.let { Link(it, linkVersion()) }
    private fun current(id: Link) = owner() == id
    private fun requireCurrent(id: Link, version: Long) {
        if (!current(id) || revision.get() != version) throw CancellationException("federation_context_changed")
    }
    private suspend fun choice(id: Link): FedChoice = withContext(Dispatchers.IO) { consent.read(id.driver) }
    private suspend fun saveIfCurrent(id: Link, version: Long, value: FedChoice) = withContext(NonCancellable) {
        consentMutex.withLock {
            if (current(id) && revision.get() == version) withContext(Dispatchers.IO) { consent.write(id.driver, value) }
        }
    }
    private suspend fun publish(id: Link) {
        if (!current(id)) { mutable.value = FedUi(busy = mutable.value.busy); return }
        val model = parseFedModel(repo.report("FED_MODEL"))
        consentMutex.withLock {
            val selected = choice(id)
            if (current(id)) mutable.update { it.copy(enabled = selected.enabled, pendingOff = selected.pendingOff, modelRound = model?.round) }
        }
    }
    private suspend fun <T> operation(block: suspend () -> T): T = mutex.withLock {
        mutable.update { it.copy(busy = true, error = null) }
        try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (e: Exception) { mutable.update { it.copy(error = "연결하지 못했어요. 잠시 후 다시 시도해 주세요.") }; throw e }
        finally { mutable.update { it.copy(busy = false) } }
    }
    suspend fun load() = operation { owner()?.let { publish(it) } ?: run { mutable.value = FedUi() } }

    /** Consent is never inferred from presentation mode or restored for a different linked driver. */
    suspend fun setOptIn(on: Boolean) {
        val id = owner() ?: throw SupabaseException(0, "not_linked")
        val requested = revision.incrementAndGet()
        val intent = intentRevision.incrementAndGet()
        // Persist the intent before waiting for networking. Screen cancellation cannot undo an opt-out.
        withContext(NonCancellable) {
            consentMutex.withLock {
                // Erasure or disconnection may invalidate networking, but must keep an earlier withdrawal.
                if (intentRevision.get() == intent && (!on || (current(id) && revision.get() == requested))) {
                    withContext(Dispatchers.IO) { consent.write(id.driver, FedChoice(pendingOff = !on)) }
                    if (current(id)) mutable.update { it.copy(enabled = false, pendingOff = !on) }
                }
            }
        }
        currentCoroutineContext().ensureActive()
        operation {
            requireCurrent(id, requested)
            client.rpc("set_fed_opt_in", buildJsonObject { put("p_on", on) })
            saveIfCurrent(id, requested, FedChoice(enabled = on)); publish(id)
        }
    }
    private suspend fun retryOptOut(id: Link) {
        val version = revision.get()
        if (!choice(id).pendingOff) return
        requireCurrent(id, version)
        client.rpc("set_fed_opt_in", buildJsonObject { put("p_on", false) })
        saveIfCurrent(id, version, FedChoice()); publish(id)
    }
    private suspend fun fetch(id: Link): FedModel {
        try {
            retryOptOut(id)
            if (!current(id)) throw CancellationException("federation_context_changed")
            val raw = client.rpc("get_fed_model")
            val model = parseFedModel(raw.toString()) ?: throw SupabaseException(200, "invalid_fed_model")
            currentCoroutineContext().ensureActive()
            if (!current(id)) throw CancellationException("federation_context_changed")
            repo.saveReport("FED_MODEL", Json.encodeToString(model)); publish(id)
            return model
        } catch (e: Exception) {
            // A failed refresh must not silently keep applying an old model.
            if (current(id)) withContext(NonCancellable) { repo.saveReport("FED_MODEL", "null"); publish(id) }
            throw e
        }
    }
    suspend fun refreshModel() = operation {
        owner()?.let { fetch(it) } ?: run { mutable.value = FedUi(); null }
    }
    suspend fun round(nowMs: Long): Int = operation {
        val id = owner() ?: return@operation 0
        if (!choice(id).enabled) { publish(id); return@operation 0 }
        val version = revision.get()
        submit(id, fetch(id), nowMs, version)
    }
    /** Model reads are daily; donating a batch is at most once on each KST Monday. */
    suspend fun daily(nowMs: Long): Int = operation {
        val id = owner() ?: return@operation 0
        val version = revision.get()
        val model = fetch(id)
        val day = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val stamp = "FED_MONDAY:${id.driver}"
        if (day.dayOfWeek != DayOfWeek.MONDAY || repo.report(stamp) == day.toString() || !choice(id).enabled) return@operation 0
        val count = submit(id, model, nowMs, version)
        if (current(id) && version == revision.get()) repo.saveReport(stamp, day.toString())
        count
    }
    private suspend fun submit(id: Link, model: FedModel, nowMs: Long, version: Long): Int {
        var sent = 0
        suspend fun permitted() = current(id) && revision.get() == version && choice(id).enabled
        suspend fun send(tag: String, weeks: List<Pair<java.time.LocalDate, Double>>) {
            if (!permitted() || (tag != "self" && !presentation())) return
            val delta = withContext(Dispatchers.Default) { HolidayModel.localDelta(model.weights.toDoubleArray(), weeks) }
            if (delta.isEmpty()) return
            require(delta.size == HolidayModel.SIZE && delta.all { it.isFinite() && it in -1.0..1.0 } && weeks.size in 1..520)
            if (!permitted() || (tag != "self" && !presentation())) return
            client.rpc("submit_fed_update", buildJsonObject {
                put("p_round", model.round); put("p_client_tag", tag)
                put("p_delta", JsonArray(delta.map(::JsonPrimitive))); put("p_n", weeks.size)
            })
            sent++
            mutable.update { it.copy(submitted = sent) }
        }
        mutable.update { it.copy(submitted = 0) }
        try {
            send("self", weeklyRatios(repo.trips(), zone, nowMs))
            if (permitted() && presentation()) {
                val mocks = virtuals()
                require(mocks.map { it.tag }.sorted() == listOf("virtual-1", "virtual-2"))
                for (mock in mocks.sortedBy { it.tag }) send(mock.tag, completedFedWeeks(mock.weeks, nowMs))
            }
        } catch (e: SupabaseException) {
            if (e.code == "fed_opt_in_required") { saveIfCurrent(id, version, FedChoice()); publish(id) }
            throw e // round_changed waits for the next attempt; never retrain/retry within this batch.
        }
        return sent
    }
    /** Drain model writes before the existing local-data erasure; nothing may repopulate cleared reports. */
    suspend fun <T> withErasure(block: suspend () -> T): T {
        revision.incrementAndGet()
        return mutex.withLock {
            withContext(NonCancellable) {
                consentMutex.withLock { withContext(Dispatchers.IO) { consent.clearKeepingWithdrawals() } }
            }
            block().also { mutable.value = FedUi() }
        }
    }
}
