package ai.handleon.car.sync

import ai.handleon.driveprint.*
import java.time.Instant
import kotlinx.serialization.json.*
import org.junit.Test
import kotlin.test.*

class DriveNoticesTest {
    private val now = Instant.parse("2026-10-27T09:00:00Z").toEpochMilli()
    private fun outing(end: Long = now, duration: Long = 60000) = Outing(now - 3600000, end, listOf(now - 3600000), duration, true, duration, false)
    @Test fun `old outings are not reported`() {
        val edge = outing(now - 6 * 3600000)
        assertEquals(listOf(edge), DriveNotices.toReport(listOf(edge.copy(endMs = edge.endMs - 1), edge), now))
    }
    @Test fun `minutes are clamped`() {
        assertEquals(1, DriveNotices.reportBody(outing(duration = 0))["p_minutes"]!!.jsonPrimitive.int)
        assertEquals(1440, DriveNotices.reportBody(outing(duration = Long.MAX_VALUE))["p_minutes"]!!.jsonPrimitive.int)
    }
    @Test fun `ended_at is utc iso`() {
        val body = DriveNotices.reportBody(outing())
        assertEquals("2026-10-27T09:00:00Z", body["p_ended_at"]!!.jsonPrimitive.content)
        assertTrue(body["p_outing_key"]!!.jsonPrimitive.content.matches(Regex("[0-9]{10,16}")))
        assertEquals(setOf("p_outing_key", "p_ended_at", "p_minutes", "p_rested"), body.keys)
    }
    @Test fun `sharing line hides at zero`() {
        assertNull(DriveNotices.sharingLine(0))
        assertEquals("운전 마침 소식을 2명에게 보내고 있어요", DriveNotices.sharingLine(2))
    }
    @Test fun `future timestamps and invalid outing keys are not reported`() {
        assertTrue(DriveNotices.toReport(listOf(outing(now + 300001), outing().copy(startMs = 1)), now).isEmpty())
        assertEquals(1, DriveNotices.toReport(listOf(outing(now + 300000)), now).size)
    }
}
