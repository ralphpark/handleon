package ai.handleon.driveprint

import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Offline, memory-transcribed 2023–2027 calendar, including known special/election days.
 * Not a live government calendar. See IMPLEMENTATION_REPORT.md for names and verification gaps.
 * Outside this range only weekends are recognized.
 */
object KoreanHolidays {
    private val dates = buildSet {
        val byYear = mapOf(
            2023 to "01-01 01-21 01-22 01-23 01-24 03-01 05-05 05-27 05-29 06-06 08-15 09-28 09-29 09-30 10-02 10-03 10-09 12-25",
            2024 to "01-01 02-09 02-10 02-11 02-12 03-01 04-10 05-05 05-06 05-15 06-06 08-15 09-16 09-17 09-18 10-01 10-03 10-09 12-25",
            2025 to "01-01 01-27 01-28 01-29 01-30 03-01 03-03 05-05 05-06 06-03 06-06 08-15 10-03 10-05 10-06 10-07 10-08 10-09 12-25",
            2026 to "01-01 02-16 02-17 02-18 03-01 03-02 05-05 05-24 05-25 06-03 06-06 08-15 08-17 09-24 09-25 09-26 10-03 10-05 10-09 12-25",
            2027 to "01-01 02-06 02-07 02-08 02-09 03-01 05-05 05-13 06-06 08-15 08-16 09-14 09-15 09-16 10-03 10-04 10-09 10-11 12-25 12-27",
        )
        byYear.forEach { (year, days) ->
            days.split(' ').forEach { add(LocalDate.parse(year.toString() + "-" + it)) }
        }
    }

    fun isOffDay(date: LocalDate): Boolean =
        date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY || date in dates
}
