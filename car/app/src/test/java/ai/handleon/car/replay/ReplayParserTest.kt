package ai.handleon.car.replay

import ai.handleon.driveprint.Source
import java.io.File
import kotlinx.serialization.json.*
import org.junit.Test
import kotlin.test.*

class ReplayParserTest {
    @Test fun `new car asset uses the existing replay parser`() {
        val data = ReplayParser.parse(File("src/main/assets/replay_new_car.json").readText())
        assertEquals(1, data.version)
        assertEquals("new_car", data.persona)
        assertTrue(data.trips.isNotEmpty())
        assertTrue(data.trips.all { it.source == Source.SIM && it.endMs > it.startMs })
    }
    @Test fun `sudden asset uses the existing replay parser`() {
        val data = ReplayParser.parse(File("src/main/assets/replay_sudden.json").readText())
        assertEquals(1, data.version)
        assertEquals("sudden", data.persona)
        assertTrue(data.trips.isNotEmpty())
        assertTrue(data.trips.all { it.source == Source.SIM && it.endMs > it.startMs })
    }
    @Test fun `new replay assets contain summaries without raw coordinates or place names`() {
        val fields = setOf("startMs", "endMs", "source", "distanceKm", "night", "rain", "offDay", "endCell",
            "maxFromHomeKm", "familiarDest", "guided", "guidedKm", "reroutes", "leftTurns", "rightTurns",
            "announcedTurns", "signalMisses", "speedingRatio", "hardBrakes", "steeringStd", "steeringReversalsPerMin",
            "lkaEvents", "fcaEvents", "hardAccels")
        for (asset in listOf("replay_new_car.json", "replay_sudden.json")) {
            val raw = Json.parseToJsonElement(File("src/main/assets/$asset").readText()).jsonObject
            assertEquals(setOf("version", "persona", "trips"), raw.keys)
            raw.getValue("trips").jsonArray.forEach { item ->
                val trip = item.jsonObject
                assertTrue(trip.keys.all { it in fields })
                assertTrue(trip.values.all { it is JsonPrimitive })
                assertEquals(listOf(JsonPrimitive("SIM")), trip.values.filter { it.jsonPrimitive.isString })
                // Generator uses persona-id * 100 + a small destination ordinal, never a geographic cell.
                assertTrue(trip.getValue("endCell").jsonPrimitive.long in 300..599)
            }
        }
    }
}
