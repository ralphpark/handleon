package ai.handleon.driveprint

import kotlin.test.*

class PromisesTest {
    private val trip = featureTrip()
    private fun outing(duration: Long, closed: Boolean = true) = Outing(0, duration, listOf(0), duration, false, duration, closed)
    @Test fun `night trip breaks no-night promise`() {
        assertEquals(listOf(PromiseResult(Promise.NO_NIGHT, 1, 1)), Promises.evaluate(setOf(Promise.NO_NIGHT), listOf(trip, trip.copy(night = true)), emptyList()))
    }
    @Test fun `rain trip breaks no-rain promise`() {
        assertEquals(listOf(PromiseResult(Promise.NO_RAIN, 1, 1)), Promises.evaluate(setOf(Promise.NO_RAIN), listOf(trip, trip.copy(rain = true)), emptyList()))
    }
    @Test fun `exactly two hours keeps rest promise`() {
        assertEquals(listOf(PromiseResult(Promise.REST_2H, 1, 1)), Promises.evaluate(setOf(Promise.REST_2H), emptyList(), listOf(outing(7_200_000), outing(7_201_000))))
    }
    @Test fun `open outing is not judged for rest`() {
        assertEquals(listOf(PromiseResult(Promise.REST_2H, 0, 0)), Promises.evaluate(setOf(Promise.REST_2H), emptyList(), listOf(outing(9_000_000, false))))
    }
    @Test fun `unselected promises are not returned`() {
        assertEquals(emptyList(), Promises.evaluate(emptySet(), listOf(trip), emptyList()))
        assertEquals(listOf(Promise.NO_NIGHT, Promise.REST_2H, Promise.NO_RAIN),
            Promises.evaluate(linkedSetOf(Promise.NO_RAIN, Promise.REST_2H, Promise.NO_NIGHT), emptyList(), emptyList()).map { it.promise })
    }
}
