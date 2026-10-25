package ai.handleon.car.collect

/** Short stops belong to the same session; a full break begins at the first stopped tick. */
class DriveSession(private val breakMs: Long = 15 * 60_000L) {
    private var stoppedAtMs: Long? = null
    var view: DriveSessionView? = null
        private set

    fun update(nowMs: Long, moving: Boolean) {
        if (!moving) {
            if (view != null && stoppedAtMs == null) stoppedAtMs = nowMs
            return
        }
        val previous = view
        val newSession = previous == null || stoppedAtMs?.let { nowMs - it >= breakMs } == true
        view = DriveSessionView(if (newSession) nowMs else previous!!.startMs, nowMs)
        stoppedAtMs = null
    }
}

data class DriveSessionView(val startMs: Long, val lastMovingMs: Long) {
    val continuousMs get() = lastMovingMs - startMs
}
