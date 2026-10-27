package ai.handleon.car.ui

/** Only a current, successful UX reading may unlock parked interactions. */
class UxDrivingGate {
    private var generation = 0
    var driving = true
        private set

    fun beginConnection(): Int { disconnect(); return generation }
    fun report(connection: Int, requiresOptimization: Boolean?) {
        if (connection == generation) driving = requiresOptimization != false
    }
    fun disconnect() { generation++; driving = true }
}
