package ai.handleon.car.centers

import ai.handleon.driveprint.LatLon
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CentersTest {
    @Test fun parsesSampleRows() {
        val centers = Centers.parse(SAMPLE_CSV)
        assertEquals(3, centers.size)
        assertEquals("가센터", centers.first().name)
        assertEquals("성남시 가로 1", centers.first().address)
        assertEquals("000-000-0001", centers.first().phone)
        assertEquals(LatLon(37.41, 127.09), centers.first().pos)
    }

    @Test fun nearestIsSortedByDistance() {
        val result = Centers.nearest(Centers.parse(SAMPLE_CSV).reversed(), LatLon(37.41, 127.09))
        assertEquals(listOf("가센터", "나센터", "다센터"), result.map { it.first.name })
        assertEquals(0.0, result.first().second)
        assertTrue(result.zipWithNext().all { (a, b) -> a.second <= b.second })
        assertEquals(1.11195, result[1].second, absoluteTolerance = 0.0001)
    }

    @Test fun skipsRowsWithoutCoordinates() {
        val csv = SAMPLE_CSV.replace("37.42,127.09", ",127.09")
        assertEquals(2, Centers.parse(csv).size)
    }

    @Test fun skipsInvalidCoordinates() {
        val csv = SAMPLE_CSV + "\n" + listOf("abc,127", "37,NaN", "Infinity,127", "91,127", "37,181", "37,")
            .joinToString("\n") { "누락센터,주소,000,$it" }
        assertEquals(3, Centers.parse(csv).size)
    }

    @Test fun quotedFieldsMayContainCommasAndEscapedQuotes() {
        val csv = "\uFEFFname,address,phone,lat,lon\r\n" +
            "\"가,센터\",\"가로 1, \"\"별관\"\"\",\"000-000-0001\",37.41,127.09\r\n"
        val center = Centers.parse(csv).single()
        assertEquals("가,센터", center.name)
        assertEquals("가로 1, \"별관\"", center.address)
    }

    @Test fun nearestHonorsLimitAndEmptyInput() {
        assertEquals(2, Centers.nearest(Centers.parse(SAMPLE_CSV), LatLon(37.41, 127.09), 2).size)
        assertTrue(Centers.nearest(Centers.parse(SAMPLE_CSV), LatLon(37.41, 127.09), 0).isEmpty())
        assertTrue(Centers.nearest(emptyList(), LatLon(37.41, 127.09)).isEmpty())
    }

    @Test fun wrongHeaderIsRejected() {
        assertFailsWith<IllegalArgumentException> { Centers.parse("name,lon,lat\n가,127,37") }
    }

    @Test fun malformedRowDoesNotHideFollowingValidRow() {
        val csv = "name,address,phone,lat,lon\n\"닫히지않음,주소,000,37,127\n" + SAMPLE_CSV.substringAfter('\n')
        assertEquals(3, Centers.parse(csv).size)
    }

    private companion object {
        val SAMPLE_CSV = """
            name,address,phone,lat,lon
            가센터,성남시 가로 1,000-000-0001,37.41,127.09
            나센터,성남시 나로 2,000-000-0002,37.42,127.09
            다센터,성남시 다로 3,000-000-0003,37.51,127.09
        """.trimIndent()
    }
}
