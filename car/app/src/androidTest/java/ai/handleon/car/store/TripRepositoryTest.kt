package ai.handleon.car.store

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import ai.handleon.driveprint.Geo
import ai.handleon.driveprint.LatLon
import ai.handleon.driveprint.Source
import ai.handleon.driveprint.TripFeatures
import java.time.LocalDate
import java.time.ZonedDateTime
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class TripRepositoryTest {
    private lateinit var db: Db
    private lateinit var repo: TripRepository
    private val today = LocalDate.of(2026, 10, 22)

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), Db::class.java).build()
        repo = TripRepository(db) { ZonedDateTime.parse("2026-10-21T16:00:00Z") }
    }

    @After fun tearDown() { db.close() }

    @Test fun duplicateImportIsIgnored() = runTest {
        assertTrue(repo.saveTrip(t1, "TIMELINE:1:2"))
        assertFalse(repo.saveTrip(t1, "TIMELINE:1:2"))
        assertEquals(1, repo.trips().size)
    }

    @Test fun concurrentDuplicateImportIsIgnored() = runTest {
        val saved = List(8) { async { repo.saveTrip(t1, "TIMELINE:1:2") } }.awaitAll()
        assertEquals(1, saved.count { it })
        assertEquals(1, repo.trips().size)
    }

    @Test fun tripsWithoutImportKeysAreNotDeduplicated() = runTest {
        assertTrue(repo.saveTrip(t1))
        assertTrue(repo.saveTrip(t1))
        assertEquals(2, repo.trips().size)
    }

    @Test fun movingHomeRecordsReset() = runTest {
        repo.setPlaces(LatLon(37.40, 127.10), emptyList())
        assertTrue(repo.resets().isEmpty())
        repo.setPlaces(LatLon(37.50, 127.00), emptyList())
        assertEquals(today to "HOME_CHANGED", repo.resets().single())
    }

    @Test fun nearbyHomeUpdateDoesNotReset() = runTest {
        repo.setPlaces(LatLon(37.40, 127.10), emptyList())
        repo.setPlaces(LatLon(37.401, 127.10), emptyList())
        assertTrue(repo.resets().isEmpty())
        assertEquals(LatLon(37.401, 127.10), repo.home())
    }

    @Test fun placesInTheSameCellDoNotOverwriteHome() = runTest {
        val home = LatLon(37.40, 127.10)
        val familiar = LatLon(37.4001, 127.1001)
        assertEquals(Geo.cell(home), Geo.cell(familiar))
        repo.setPlaces(home, listOf(familiar, familiar))
        assertEquals(home, repo.home())
        assertEquals(setOf(Geo.cell(familiar)), repo.familiarCells())
        repo.setPlaces(null, emptyList())
        assertNull(repo.home())
        assertTrue(repo.familiarCells().isEmpty())
    }

    @Test fun tripRoundTripsThroughJson() = runTest {
        repo.saveTrip(t1)
        assertEquals(t1, repo.trips().single())
    }

    @Test fun nullableTripValuesAndSourceRoundTrip() = runTest {
        val imported = t1.copy(source = Source.TIMELINE, endCell = null, maxFromHomeKm = null,
            speedingRatio = null, steeringStd = null, steeringReversalsPerMin = null)
        repo.saveTrip(imported, "TIMELINE:10:20")
        assertEquals(imported, repo.trips().single())
    }

    @Test fun labelsCanBeReplacedAndRemoved() = runTest {
        repo.setLabel(today, "SLEEP")
        repo.setLabel(today, "TRAVEL")
        assertEquals(mapOf(today to "TRAVEL"), repo.labels())
        repo.setLabel(today, null)
        assertTrue(repo.labels().isEmpty())
        assertTrue(repo.resets().isEmpty())
    }

    @Test fun carChangedLabelAlsoRecordsReset() = runTest {
        repo.setLabel(today, "CAR_CHANGED")
        assertEquals(mapOf(today to "CAR_CHANGED"), repo.labels())
        assertEquals(listOf(today to "CAR_CHANGED"), repo.resets())
    }

    @Test fun bothResetKindsOnSameDayAreRetainedWithoutDuplicates() = runTest {
        repo.addReset(today, "HOME_CHANGED")
        repo.addReset(today, "CAR_CHANGED")
        repo.addReset(today, "CAR_CHANGED")
        assertEquals(setOf(today to "HOME_CHANGED", today to "CAR_CHANGED"), repo.resets().toSet())
        assertEquals(2, repo.resets().size)
    }

    @Test fun reportReplacesOnlyItsOwnKind() = runTest {
        assertNull(repo.report("LONG"))
        repo.saveReport("LONG", "{\"stage\":\"BUILDING\"}")
        repo.saveReport("CONDITION", "{\"status\":\"OK\"}")
        repo.saveReport("LONG", "{\"stage\":\"STABLE\"}")
        assertEquals("{\"stage\":\"STABLE\"}", repo.report("LONG"))
        assertEquals("{\"status\":\"OK\"}", repo.report("CONDITION"))
    }

    private val t1 = TripFeatures(
        startMs = 10, endMs = 20, source = Source.CAR, distanceKm = 12.5,
        night = false, rain = true, offDay = false, endCell = 123456789012L,
        maxFromHomeKm = 8.2, familiarDest = true, guided = true, guidedKm = 11.2,
        reroutes = 2, leftTurns = 4, rightTurns = 5, announcedTurns = 8, signalMisses = 1,
        speedingRatio = 0.1, hardBrakes = 3, steeringStd = 2.2, steeringReversalsPerMin = 1.2,
        lkaEvents = 1, fcaEvents = 2,
    )
}
