package ai.handleon.car.sync

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SyncSnapshot(val driverId: String? = null, val lastSyncMs: Long? = null, val letterDriverId: String? = null)

/** The same private handleon_sync preferences as the session store. */
class SyncState(private val prefs: SharedPreferences) {
    private val connectionCounter = java.util.concurrent.atomic.AtomicLong()
    val connectionGeneration: Long get() = connectionCounter.get()
    private val mutable = MutableStateFlow(SyncSnapshot(prefs.getString("driver_id", null),
        if (prefs.contains("last_sync_ms")) prefs.getLong("last_sync_ms", 0) else null,
        prefs.getString("letter_driver_id", null)))
    val state = mutable.asStateFlow()
    val driverId: String? get() = state.value.driverId
    val lastSyncMs: Long? get() = state.value.lastSyncMs

    @Synchronized fun setDriver(driverId: String?) {
        if (driverId == this.driverId) return
        connectionCounter.incrementAndGet()
        prefs.edit().putString("driver_id", driverId).remove("last_sync_ms").remove("letter_driver_id").apply()
        mutable.value = SyncSnapshot(driverId)
    }

    @Synchronized fun markSynced(driverId: String, nowMs: Long) {
        if (driverId != this.driverId) return
        prefs.edit().putLong("last_sync_ms", nowMs).apply()
        mutable.value = mutable.value.copy(lastSyncMs = nowMs)
    }

    @Synchronized fun setLetterDriver(driverId: String?) {
        if (driverId != null && driverId != this.driverId) return
        prefs.edit().putString("letter_driver_id", driverId).apply()
        mutable.value = mutable.value.copy(letterDriverId = driverId)
    }

    val importsPending: Boolean get() = prefs.getBoolean("imports_pending", false)

    /** Called on IO before inserts, and cleared only after the derived report has been saved. */
    @Synchronized fun setImportsPending(pending: Boolean) {
        if (pending == importsPending) return
        check(prefs.edit().putBoolean("imports_pending", pending).commit()) { "sync_state_write_failed" }
    }
}
