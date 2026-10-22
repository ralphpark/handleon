package ai.handleon.driveprint

import kotlinx.serialization.Serializable

enum class Grade(val weight: Double) { A(1.0), B(0.6), C(0.3) }
enum class Direction { DOWN, UP, BOTH }
@Serializable
enum class Domain { MOBILITY, WAYFINDING, OPERATION }

@Serializable
enum class FeatureId(val grade: Grade, val direction: Direction, val domain: Domain, val minMad: Double) {
    M1(Grade.A, Direction.DOWN, Domain.MOBILITY, 3.0),
    M2(Grade.A, Direction.DOWN, Domain.MOBILITY, 0.03),
    M3(Grade.A, Direction.DOWN, Domain.MOBILITY, 2.0),
    M4(Grade.A, Direction.DOWN, Domain.MOBILITY, 0.03),
    M5(Grade.A, Direction.DOWN, Domain.MOBILITY, 1.0),
    M6(Grade.A, Direction.BOTH, Domain.MOBILITY, 0.2),
    M7(Grade.A, Direction.DOWN, Domain.MOBILITY, 2.0),
    W2(Grade.B, Direction.DOWN, Domain.WAYFINDING, 0.03),
    W3(Grade.C, Direction.UP, Domain.WAYFINDING, 0.02),
    W4(Grade.C, Direction.UP, Domain.WAYFINDING, 0.03),
    O1(Grade.A, Direction.BOTH, Domain.OPERATION, 0.01),
    O2(Grade.A, Direction.BOTH, Domain.OPERATION, 0.3),
    O7(Grade.B, Direction.UP, Domain.OPERATION, 0.1),
    O8(Grade.C, Direction.UP, Domain.OPERATION, 0.05),
    O9(Grade.C, Direction.UP, Domain.OPERATION, 0.3),
}

val CONDITION_FEATURES = listOf(FeatureId.O2, FeatureId.O7, FeatureId.O9)
