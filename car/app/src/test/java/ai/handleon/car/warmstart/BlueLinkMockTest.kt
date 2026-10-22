package ai.handleon.car.warmstart

import java.io.File
import java.time.YearMonth
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BlueLinkMockTest {
    @Test fun blueLinkMockCovers46Months() {
        val months = BlueLinkMock.parse(File("src/main/assets/bluelink_mock.json").readText())
        assertEquals(46, months.size)
        assertEquals(YearMonth.of(2021, 1), months.first().month)
        assertEquals(YearMonth.of(2024, 10), months.last().month)
        assertTrue(months.zipWithNext().all { (a, b) -> a.month.plusMonths(1) == b.month })
        assertTrue(months.first().distanceKm in 900.0..1200.0)
        assertTrue(months.zipWithNext().all { (a, b) -> b.distanceKm < a.distanceKm })
        assertTrue(months.all { it.distanceKm > 0 && it.drivingMinutes > 0 })
    }

    @Test fun optionalScoreIsPreservedAsMissing() {
        val months = BlueLinkMock.parse("""{"version":1,"persona":"father","months":[
            {"month":"2021-01","distanceKm":1000.5,"drivingMinutes":2001},
            {"month":"2021-02","distanceKm":990.0,"drivingMinutes":1980,"safeDrivingScore":null}
        ]}""")
        assertEquals(1000.5, months.first().distanceKm)
        assertEquals(2001, months.first().drivingMinutes)
        assertNull(months.first().safeDrivingScore)
        assertNull(months.last().safeDrivingScore)
    }
}
