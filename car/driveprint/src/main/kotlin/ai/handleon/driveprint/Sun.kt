package ai.handleon.driveprint

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.*

/** NOAA fractional-year approximation; civil sunrise/sunset use zenith 90.833 degrees. */
object Sun {
    fun sunriseMs(p: LatLon, date: LocalDate): Long = eventMs(p, date, rising = true)
    fun sunsetMs(p: LatLon, date: LocalDate): Long = eventMs(p, date, rising = false)

    fun isNight(p: LatLon, tMs: Long, zone: ZoneId): Boolean {
        val date = Instant.ofEpochMilli(tMs).atZone(zone).toLocalDate()
        return tMs < sunriseMs(p, date) || tMs > sunsetMs(p, date)
    }

    private fun eventMs(p: LatLon, date: LocalDate, rising: Boolean): Long {
        val latitude = Math.toRadians(p.lat)
        var minutesUtc = 720.0 - 4 * p.lon
        // Re-evaluate declination and equation of time at the event, not at midnight.
        repeat(4) {
            val gamma = 2 * PI / date.lengthOfYear() *
                (date.dayOfYear - 1 + (minutesUtc / 60 - 12) / 24)
            val equationMinutes = 229.18 * (0.000075 + 0.001868 * cos(gamma) -
                0.032077 * sin(gamma) - 0.014615 * cos(2 * gamma) - 0.040849 * sin(2 * gamma))
            val declination = 0.006918 - 0.399912 * cos(gamma) + 0.070257 * sin(gamma) -
                0.006758 * cos(2 * gamma) + 0.000907 * sin(2 * gamma) -
                0.002697 * cos(3 * gamma) + 0.00148 * sin(3 * gamma)
            val cosHour = (cos(Math.toRadians(90.833)) - sin(latitude) * sin(declination)) /
                (cos(latitude) * cos(declination))
            require(cosHour in -1.0..1.0) { "No sunrise/sunset at this latitude on $date" }
            val hourDegrees = Math.toDegrees(acos(cosHour))
            minutesUtc = 720 - 4 * p.lon - equationMinutes + (if (rising) -4 else 4) * hourDegrees
        }
        // Do not wrap to 0..24h: Seoul sunrise belongs to the preceding UTC day.
        return date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() + (minutesUtc * 60_000).roundToLong()
    }
}
