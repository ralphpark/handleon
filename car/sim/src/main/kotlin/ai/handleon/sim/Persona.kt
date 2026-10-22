package ai.handleon.sim

import java.time.YearMonth

data class Persona(
    val id: Int, val declineOnset: YearMonth?, val tripsPerMonth: Double,
    val places: Int, val homeKmScale: Double, val seed: Long,
)
