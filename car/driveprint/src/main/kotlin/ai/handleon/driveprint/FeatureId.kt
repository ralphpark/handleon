package ai.handleon.driveprint

import kotlinx.serialization.Serializable

enum class Grade(val weight: Double) { A(1.0), B(0.6), C(0.3) }
enum class Direction { DOWN, UP, BOTH }
@Serializable
enum class Domain { MOBILITY, WAYFINDING, OPERATION }

@Serializable
enum class FeatureId(val grade: Grade, val direction: Direction, val domain: Domain) {
    M1(Grade.A, Direction.DOWN, Domain.MOBILITY),
    M2(Grade.A, Direction.DOWN, Domain.MOBILITY),
    M3(Grade.A, Direction.DOWN, Domain.MOBILITY),
    M4(Grade.A, Direction.DOWN, Domain.MOBILITY),
    M5(Grade.A, Direction.DOWN, Domain.MOBILITY),
    M6(Grade.A, Direction.BOTH, Domain.MOBILITY),
    M7(Grade.A, Direction.DOWN, Domain.MOBILITY),
    W2(Grade.B, Direction.DOWN, Domain.WAYFINDING),
    W3(Grade.C, Direction.UP, Domain.WAYFINDING),
    W4(Grade.C, Direction.UP, Domain.WAYFINDING),
    O1(Grade.A, Direction.BOTH, Domain.OPERATION),
    O2(Grade.A, Direction.BOTH, Domain.OPERATION),
    O7(Grade.B, Direction.UP, Domain.OPERATION),
    O8(Grade.C, Direction.UP, Domain.OPERATION),
    O9(Grade.C, Direction.UP, Domain.OPERATION),
}

val CONDITION_FEATURES = listOf(FeatureId.O2, FeatureId.O7, FeatureId.O9)
