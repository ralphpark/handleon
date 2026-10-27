package ai.handleon.car.replay

import ai.handleon.car.engine.Mode

enum class ReplayPersona(val label: String, val id: String, val asset: String, val mode: Mode, val stepMs: Long) {
    FATHER("아버지", "father", "replay/father.json", Mode.MAEUM, 2_500),
    NEW_CAR("새 차", "new_car", "replay_new_car.json", Mode.MAEUM, 2_500),
    SUDDEN("갑작스러운 변화", "sudden", "replay_sudden.json", Mode.MAEUM, 1_000),
    CHILD("자녀", "child", "replay/child.json", Mode.CONDITION, 500);

    companion object {
        fun forMode(mode: Mode) = if (mode == Mode.MAEUM) listOf(FATHER, NEW_CAR, SUDDEN)
            else listOf(CHILD, FATHER, NEW_CAR, SUDDEN)
    }
}
