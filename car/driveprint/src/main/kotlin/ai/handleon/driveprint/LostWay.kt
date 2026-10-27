package ai.handleon.driveprint

object LostWay {
    const val MIN_REROUTES = 2
    fun flagged(t: TripFeatures): Boolean = t.guided && t.familiarDest && t.reroutes >= MIN_REROUTES
}
