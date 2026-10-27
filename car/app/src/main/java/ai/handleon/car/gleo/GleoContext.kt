package ai.handleon.car.gleo

import ai.handleon.car.ui.KST
import ai.handleon.car.ui.buildResume
import ai.handleon.car.ui.carTripsThrough
import ai.handleon.driveprint.*
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZonedDateTime

fun buildGleoContext(ownerTrips: List<TripFeatures>, promises: Set<Promise>, labels: Set<LocalDate>, resets: Set<Reset>,
                     now: ZonedDateTime, driving: Boolean): GleoContext {
    val day = now.withZoneSameInstant(KST).toLocalDate()
    val history = ownerTrips.filter { it.source != Source.SIM && it.startMs <= now.toInstant().toEpochMilli() && it.endMs <= now.toInstant().toEpochMilli() }
    val car = carTripsThrough(history, now.toInstant().toEpochMilli())
    val resume = buildResume(car, promises, now)
    val engine = Driveprint()
    val weekly = engine.weeklyReport(history, labels, resets, day)
    return GleoContext(driving, car.filter { Instant.ofEpochMilli(it.startMs).atZone(KST).toLocalDate() == day }.map(Habits::of),
        resume.week, resume.promises, engine.readiness(history, weekly, day, labels, resets), weekly.status, YearMonth.from(day))
}
