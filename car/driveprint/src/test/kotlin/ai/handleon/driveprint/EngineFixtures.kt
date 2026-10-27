package ai.handleon.driveprint

import java.time.LocalDate
import java.time.ZoneId

internal val KST: ZoneId = ZoneId.of("Asia/Seoul")
internal fun featureTrip(day: LocalDate = LocalDate.of(2026, 4, 6), minutes: Int = 20, hour: Int = 10): TripFeatures {
    val start = day.atTime(hour, 0).atZone(KST).toInstant().toEpochMilli()
    return Features.fromPastTrip(start, start + minutes * 60_000L,
        LatLon(37.0, 127.0), LatLon(37.1, 127.0), 10.0, LatLon(37.0, 127.0), emptySet(), KST)
        .copy(source = Source.CAR)
}
