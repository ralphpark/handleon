package ai.handleon.driveprint

import java.time.LocalDate
import kotlin.test.*

class OutingsTest {
    private val first = featureTrip(minutes = 20)
    private fun second(gap: Int, minutes: Int = 30) = first.copy(
        startMs = first.endMs + gap * 60_000L, endMs = first.endMs + (gap + minutes) * 60_000L)
    private fun check(o: Outing, rest: Boolean, closed: Boolean, longest: Int) {
        assertEquals(rest, o.rested)
        assertEquals(closed, o.closed)
        assertEquals(longest * 60_000L, o.longestContinuousMs)
    }
    @Test fun `short stop keeps one outing`() {
        val last = second(10)
        val outings = Outings.group(listOf(last, first), last.endMs + 30 * 60_000)
        assertEquals(1, outings.size)
        check(outings.single(), false, true, 50)
        assertEquals(50 * 60_000L, outings.single().driveMs)
        assertEquals(listOf(first.startMs, last.startMs), outings.single().tripStarts)
        assertEquals(first.startMs.toString(), outings.single().key)
    }
    @Test fun `fifteen minute stop is a rest`() {
        val last = second(15)
        val outings = Outings.group(listOf(first, last), last.endMs)
        assertEquals(1, outings.size)
        check(outings.single(), true, false, 30)
    }
    @Test fun `thirty minute gap starts a new outing`() {
        val last = second(30)
        val outings = Outings.group(listOf(first, last), last.endMs + 30 * 60_000)
        assertEquals(2, outings.size)
        check(outings[0], false, true, 20)
        check(outings[1], false, true, 30)
    }
    @Test fun `open outing is not closed`() {
        val before = Outings.group(listOf(first), first.endMs + 29 * 60_000)
        assertEquals(1, before.size)
        check(before.single(), false, false, 20)
        check(Outings.group(listOf(first), first.endMs + 30 * 60_000).single(), false, true, 20)
    }
    @Test fun `outing across midnight stays one outing`() {
        val start = LocalDate.of(2026, 4, 6).atTime(23, 50).atZone(KST).toInstant().toEpochMilli()
        val a = first.copy(startMs = start, endMs = start + 10 * 60_000)
        val b = a.copy(startMs = a.endMs + 5 * 60_000, endMs = a.endMs + 10 * 60_000)
        val outings = Outings.group(listOf(a, b), b.endMs)
        assertEquals(1, outings.size)
        check(outings.single(), false, false, 15)
    }
    @Test fun `empty trips give no outings`() = assertEquals(emptyList(), Outings.group(emptyList(), 0))
}
