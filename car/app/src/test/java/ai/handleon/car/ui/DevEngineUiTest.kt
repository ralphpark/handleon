package ai.handleon.car.ui

import ai.handleon.car.collect.*
import ai.handleon.car.signals.Key
import ai.handleon.car.signals.Stamped
import ai.handleon.driveprint.*
import java.time.Instant
import kotlin.test.*

class DevEngineUiTest {
    private val noon = Instant.parse("2026-10-24T03:00:00Z").toEpochMilli()
    private fun live() = LiveViewAssembler.from(true, emptyMap(), LiveEvents(), null, noon)!!

    @Test fun absentCollectorShowsWaitingRatherThanInventingDaytime() {
        val ui = DevEngineUi.from(null)
        assertEquals("수집 서비스를 기다리고 있어요", ui.waitingText)
        assertEquals("—", ui.speedText)
        assertEquals("수집 서비스를 기다리고 있어요", ui.judgmentText)
    }

    @Test fun steeringHasSignedRoundedDegreesAndUnknownLimitStaysUnknown() {
        val ui = DevEngineUi.from(live().copy(steeringDeg = -3.4, speedLimitKmh = 0, telltales = listOf("LKA")))
        assertEquals("−3°", ui.steeringText)
        assertEquals("—", ui.speedLimitText)
        assertEquals("차로 유지 보조", ui.telltalesText)
        assertEquals("+3°", DevEngineUi.from(live().copy(steeringDeg = 3.4)).steeringText)
    }

    @Test fun knownSignalsUseKoreanLabelsAndTelltaleFallback() {
        val ui = DevEngineUi.from(live().copy(gear = Gear.D, signal = Signal.HAZARD, wiperOn = true,
            brake = true, telltales = listOf("FCA", "ABS")))
        assertEquals("D", ui.gearText)
        assertEquals("비상", ui.signalText)
        assertEquals("켜짐", ui.wiperText)
        assertEquals("밟음", ui.brakeText)
        assertEquals("전방 충돌 방지 · ABS", ui.telltalesText)
        assertEquals("없음", DevEngineUi.from(live()).telltalesText)
    }

    @Test fun noTripShowsStartHintAndMissingMetrics() {
        val ui = DevEngineUi.from(live())
        assertEquals("기어를 D 로 두면 트립이 시작돼요", ui.tripHint)
        assertEquals(listOf("—", "—", "—", "—", "—"),
            listOf(ui.distanceText, ui.hardBrakesText, ui.steeringStdText, ui.announcedTurnsText, ui.signalMissesText))
    }

    @Test fun tripDistanceUsesOneDecimalAndMissingSteeringExplainsItsWindow() {
        val trip = RawTrip(listOf(Sample(noon, 36.0, Gear.D, false, null, Signal.OFF, false, null, null, null)),
            emptyList(), emptyList())
        val features = Features.extract(trip, null, emptySet(), KST).copy(distanceKm = 2.46, hardBrakes = 1)
        val ui = DevEngineUi.from(live().copy(trip = features))
        assertNull(ui.tripHint)
        assertEquals("2.5", ui.distanceText)
        assertEquals("1", ui.hardBrakesText)
        assertEquals("—", ui.steeringStdText)
        assertEquals("시속 40km 이상 곧은 길에서 재요", ui.steeringHint)
    }

    @Test fun currentWiperShowsHoldCopy() {
        val wet = LiveViewAssembler.from(true, mapOf(Key.WIPER to Stamped(true, noon)), LiveEvents(), null, noon)
        val ui = DevEngineUi.from(wet)
        assertTrue(ui.held)
        assertEquals("밤이거나 와이퍼가 켜져 판단을 쉬어요", ui.judgmentText)
        assertEquals("낮 · 맑음 → 조작 지표를 판단해요", DevEngineUi.from(live()).judgmentText)
    }

    @Test fun guidanceIncludesDirectionAndLastAnnouncedDistance() {
        val ui = DevEngineUi.from(live().copy(guiding = true, turnAhead = TurnAhead(noon, Signal.LEFT, 80.0)))
        assertEquals("좌회전 · 80m", ui.guidanceText)
        assertEquals("우회전 · 120m", DevEngineUi.from(live().copy(guiding = true,
            turnAhead = TurnAhead(noon, Signal.RIGHT, 120.0))).guidanceText)
        assertEquals("안내 중 아님", DevEngineUi.from(live()).guidanceText)
    }
}
