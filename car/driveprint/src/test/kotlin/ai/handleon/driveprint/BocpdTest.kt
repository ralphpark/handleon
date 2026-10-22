package ai.handleon.driveprint

import java.util.Random
import kotlin.test.*

class BocpdTest {
    @Test fun detectsStepChange() {
        val rnd = Random(3); val s = List(40) { rnd.nextGaussian() * 0.3 } + List(12) { 2.0 + rnd.nextGaussian() * 0.3 }
        assertTrue(Bocpd.lastChangeIndex(s)!! in 38..42)
    }
    @Test fun flatSeriesHasNoChange() {
        val random = Random(5)
        assertNull(Bocpd.lastChangeIndex(List(52) { random.nextGaussian() * 0.3 }))
    }
    @Test fun emptyAndShortSeriesDoNotInventAChange() {
        assertNull(Bocpd.lastChangeIndex(emptyList()))
        assertNull(Bocpd.lastChangeIndex(listOf(1.0)))
    }
    @Test fun longConstantSeriesRemainsNumericallyStable() = assertNull(Bocpd.lastChangeIndex(List(300) { 0.0 }))
}
