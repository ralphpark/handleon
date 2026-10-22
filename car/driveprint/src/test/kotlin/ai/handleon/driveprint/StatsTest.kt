package ai.handleon.driveprint

import kotlin.test.*

class StatsTest {
    @Test fun zIsClampedAtFive() {
        assertEquals(5.0, Stats.z(100.0, Baseline(0.0, 0.01)))
        assertEquals(-5.0, Stats.z(-100.0, Baseline(0.0, 0.01)))
    }
    @Test fun featureMadFloorsRespectUnits() {
        val expected = listOf(3.0, .03, 2.0, .03, 1.0, .2, 2.0, .03, .02, .03, .01, .3, .1, .05, .3)
        FeatureId.entries.zip(expected).forEach { (f, floor) ->
            assertEquals(floor, f.minMad)
            assertEquals(floor, Stats.baseline(List(6) { 0.0 }, f.minMad)!!.mad)
        }
    }
}
