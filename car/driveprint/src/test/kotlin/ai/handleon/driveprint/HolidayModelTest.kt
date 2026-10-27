package ai.handleon.driveprint

import java.time.LocalDate
import kotlin.math.sqrt
import kotlin.test.*

class HolidayModelTest {
    private val zero get() = DoubleArray(HolidayModel.SIZE)
    private val start = LocalDate.of(2026, 1, 5)
    private fun counts(n: Int, count: Double = 10.0) = (0 until n).map { start.plusWeeks(it.toLong()) to count }

    @Test fun `delta is clipped`() {
        val global = DoubleArray(HolidayModel.SIZE) { 0.3 }
        val before = global.copyOf()
        val weeks = counts(8).mapIndexed { i, pair -> pair.first to if (i == 7) 1000.0 else 10.0 }
        val delta = HolidayModel.localDelta(global, weeks, clip = 0.1)
        val norm = sqrt(delta.sumOf { it * it })
        assertEquals(0.1, norm, 1e-10)
        assertTrue(delta.all { it.isFinite() })
        assertContentEquals(before, global)
    }

    @Test fun `three synthetic drivers learn the chuseok dip`() {
        val chuseok = LocalDate.of(2026, 9, 21)
        val drivers = listOf(10.0, 20.0, 30.0).map { usual ->
            counts(52, usual).map { (day, count) -> day to if (day == chuseok) count * 0.6 else count }
        }
        val global = zero
        repeat(5) {
            val delta = HolidayModel.average(drivers.map { HolidayModel.localDelta(global, it) to it.size })
            global.indices.forEach { global[it] += delta[it] }
        }
        val dip = HolidayModel.predict(global, chuseok)
        assertTrue(dip < -0.2, "learned chuseok effect=$dip")
        assertTrue(kotlin.math.abs(HolidayModel.predict(global, chuseok.minusWeeks(1))) < 0.15)
    }

    @Test fun `weekly counts are normalized by each drivers median`() {
        val weeks = counts(8).mapIndexed { i, pair -> pair.first to if (i == 4) 6.0 else 10.0 }
        val a = HolidayModel.localDelta(zero, weeks)
        val b = HolidayModel.localDelta(zero, weeks.map { it.first to it.second * 100 })
        assertTrue(a.any { it != 0.0 })
        a.indices.forEach { assertEquals(a[it], b[it], 1e-12) }
        assertContentEquals(zero, HolidayModel.localDelta(zero, counts(4)))
    }

    @Test fun `fewer than four weeks or zero median produces no update`() {
        for (n in 0..3) assertTrue(HolidayModel.localDelta(zero, counts(n)).isEmpty())
        assertTrue(HolidayModel.localDelta(zero, counts(4, 0.0)).isEmpty())
        assertTrue(HolidayModel.localDelta(zero, counts(3, 0.0) + (start.plusWeeks(3) to 10.0)).isEmpty())
    }

    @Test fun `average weights updates by sample count without mutating them`() {
        val a = zero.also { it[0] = -0.4; it[2] = 0.2 }
        val b = zero.also { it[0] = 0.8; it[2] = -0.1 }
        val aBefore = a.copyOf()
        val bBefore = b.copyOf()
        val result = HolidayModel.average(listOf(a to 1, b to 3, DoubleArray(0) to 2, zero to 0))
        assertEquals(0.5, result[0], 1e-12)
        assertEquals(-0.025, result[2], 1e-12)
        assertTrue(result.indices.filter { it != 0 && it != 2 }.all { result[it] == 0.0 })
        assertContentEquals(aBefore, a)
        assertContentEquals(bBefore, b)
        assertContentEquals(zero, HolidayModel.average(emptyList()))
        assertContentEquals(zero, HolidayModel.average(listOf(DoubleArray(0) to 3, a to 0)))
        assertContentEquals(a, HolidayModel.average(listOf(a to Int.MAX_VALUE, a to Int.MAX_VALUE)))
    }

    @Test fun `holiday features use calendar dates rather than every weekend`() {
        val expected = listOf(
            "2026-02-16" to setOf(0, 1, 4),
            "2026-09-21" to setOf(0, 2, 11),
            "2026-05-04" to setOf(0, 3, 7),
            "2026-04-06" to setOf(0, 6),
            "2025-10-06" to setOf(0, 2, 3, 12),
            "2025-12-29" to setOf(0, 3, 14),
            "2027-02-01" to setOf(0, 1, 4),
            "2027-02-08" to setOf(0, 1, 4),
            "2023-09-25" to setOf(0, 2, 11),
            "2023-10-02" to setOf(0, 3, 12),
        )
        for ((day, active) in expected) {
            val x = HolidayModel.features(LocalDate.parse(day))
            assertEquals(15, x.size)
            x.indices.forEach { assertEquals(if (it in active) 1.0 else 0.0, x[it], "$day index=$it") }
        }
    }

    @Test fun `unlisted years have no holiday indicators but retain month features`() {
        val x = HolidayModel.features(LocalDate.of(2030, 9, 9))
        assertContentEquals(doubleArrayOf(0.0, 0.0, 0.0), x.copyOfRange(1, 4))
        assertEquals(1.0, x[0])
        assertEquals(1.0, x[11])
        assertEquals(2.0, x.sum())
    }

    @Test fun `January is the reference month and prediction uses all coefficients`() {
        assertTrue(HolidayModel.features(LocalDate.of(2026, 1, 5)).drop(4).all { it == 0.0 })
        for (month in 2..12) {
            val x = HolidayModel.features(LocalDate.of(2026, month, 1))
            assertEquals(1.0, x[month + 2])
            assertEquals(1.0, x.drop(4).sum())
        }
        val w = zero.also { it[0] = 0.1; it[2] = -0.4; it[11] = 0.05 }
        assertEquals(-0.25, HolidayModel.predict(w, LocalDate.of(2026, 9, 21)), 1e-12)
    }

    @Test fun `invalid learning inputs are rejected before a model can be poisoned`() {
        assertFailsWith<IllegalArgumentException> { HolidayModel.predict(DoubleArray(14), start) }
        assertFailsWith<IllegalArgumentException> { HolidayModel.predict(zero.also { it[0] = Double.NaN }, start) }
        assertFailsWith<IllegalArgumentException> { HolidayModel.localDelta(zero, counts(4, -1.0)) }
        assertFailsWith<IllegalArgumentException> { HolidayModel.localDelta(zero, counts(4, Double.NaN)) }
        assertFailsWith<IllegalArgumentException> { HolidayModel.localDelta(zero, counts(4), lr = -0.1) }
        assertFailsWith<IllegalArgumentException> { HolidayModel.localDelta(zero, counts(4), epochs = -1) }
        assertFailsWith<IllegalArgumentException> { HolidayModel.localDelta(zero, counts(4), clip = -1.0) }
        assertFailsWith<IllegalArgumentException> { HolidayModel.localDelta(zero, List(4) { start to 10.0 }) }
        assertFailsWith<IllegalArgumentException> { HolidayModel.average(listOf(DoubleArray(14) to 1)) }
        assertFailsWith<IllegalArgumentException> { HolidayModel.average(listOf(zero to -1)) }
        assertFailsWith<IllegalArgumentException> { HolidayModel.average(listOf(zero.also { it[0] = Double.POSITIVE_INFINITY } to 1)) }
    }

    @Test fun `learned holiday effect suppresses weekly flag`() {
        val input = (0..9).map { i -> WeekInput(start.plusWeeks(i.toLong()),
            mapOf(FeatureId.M7 to if (i < 8) 100.0 else 0.0), 10, false, false) }
        assertTrue(WeeklyTrack.evaluate(input).weeks.last().flagged)
        for (effect in listOf(-0.2, 0.2)) {
            val r = WeeklyTrack.evaluate(input + input.last().copy(weekStart = start.plusWeeks(10)),
                effect = { if (it == start.plusWeeks(9)) effect else 0.0 })
            assertFalse(r.weeks.any { it.flagged })
            assertTrue(r.weeks[9].excluded)
            assertTrue(r.weeks[9].over.isEmpty())
        }
        assertTrue(WeeklyTrack.evaluate(input, effect = { -0.1999 }).weeks.last().flagged)
    }

    @Test fun `learned holiday weeks do not enter the baseline`() {
        val input = counts(4).map { (day, _) -> WeekInput(day, mapOf(FeatureId.M7 to 100.0), 10, false, false) }
        val r = WeeklyTrack.evaluate(input, effect = { if (it == start) -0.3 else 0.0 })
        assertEquals(WeeklyStatus.BUILDING, r.status)
        assertEquals(1, r.weeksUntilReady)
        assertEquals(start.plusWeeks(1), r.baselineFrom)
    }

    @Test fun `weeklyReport forwards effect for completed KST weeks`() {
        val first = LocalDate.of(2026, 4, 6)
        val trips = (0L..3L).flatMap { week -> (0L..2L).map { day -> featureTrip(first.plusWeeks(week).plusDays(day)) } }
        val today = first.plusWeeks(4)
        val engine = Driveprint()
        assertEquals(WeeklyStatus.OK, engine.weeklyReport(trips, emptySet(), emptySet(), today).status)
        val seen = mutableListOf<LocalDate>()
        val r = engine.weeklyReport(trips, emptySet(), emptySet(), today, null) { day ->
            seen.add(day)
            if (day == first.plusWeeks(3)) -0.2 else 0.0
        }
        assertEquals((0L..3L).map { first.plusWeeks(it) }, seen)
        assertEquals(WeeklyStatus.BUILDING, r.status)
        assertEquals(1, r.weeksUntilReady)
        assertTrue(r.weeks.last().excluded)
    }
}
