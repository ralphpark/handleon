package ai.handleon.car.sync

import ai.handleon.driveprint.Source
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Test
import kotlin.test.*

class ImportFetcherTest {
    private val server = MockWebServer()
    private val repo = MemoryTrips()
    private val trip = """{"start":"2024-03-01T08:12:00+09:00","end":"2024-03-01T08:40:00+09:00","startLat":37.412,"startLon":127.095,"endLat":37.395,"endLon":127.111,"distanceKm":12.3}"""
    private val file = """{"version":1,"source":"google_timeline","trips":[$trip,${trip.replace("03-01", "03-02") }]}"""
    private fun enqueueFile(body: String = file) {
        server.reply("""[{"name":"past.json","id":"file-id"}]""")
        server.reply(body)
        server.reply("{}")
    }
    @After fun close() = server.shutdown()

    @Test fun importSavesTripsAndDeletesFile() = runTest {
        enqueueFile()
        assertEquals(2, ImportFetcher(server.client(), repo).fetchAll("driver"))
        assertEquals(2, repo.trips().size)
        assertTrue(repo.trips().all { it.source == Source.TIMELINE && it.familiarDest && it.maxFromHomeKm != null })
        assertTrue(repo.keys.all { it.startsWith("TIMELINE:") })
        assertEquals("/storage/v1/object/list/imports", server.takeRequest().path)
        assertEquals("/storage/v1/object/authenticated/imports/driver/past.json", server.takeRequest().path)
        val deleted = server.takeRequest()
        assertEquals("DELETE", deleted.method)
        assertEquals("/storage/v1/object/imports/driver/past.json", deleted.path)
    }

    @Test fun sameImportTwiceSavesOnce() = runTest {
        enqueueFile(); enqueueFile()
        val fetcher = ImportFetcher(server.client(), repo)
        assertEquals(2, fetcher.fetchAll("driver"))
        assertEquals(0, fetcher.fetchAll("driver"))
        assertEquals(2, repo.trips().size)
        assertEquals(6, server.requestCount)
    }

    @Test fun malformedImportIsDeletedButSavesNothing() = runTest {
        val fetcher = ImportFetcher(server.client(), repo)
        for (body in listOf("not-json", """{"version":2,"trips":[$trip]}""", """{"version":1,"trips":[{}]}""")) {
            enqueueFile(body)
            assertEquals(0, fetcher.fetchAll("driver"))
            server.takeRequest(); server.takeRequest()
            assertEquals("DELETE", server.takeRequest().method)
        }
        assertTrue(repo.trips().isEmpty())
    }

    @Test fun invalidTripsAreSkippedIndividually() = runTest {
        val bad = listOf(trip.replace("08:40", "08:12"), trip.replace("37.412", "91.0"),
            trip.replace("127.111", "181.0"), trip.replace("37.395", "\"NaN\""),
            trip.replace("12.3", "-1"), trip.replace("12.3", "1000.1"), trip.replace("12.3", "\"NaN\""))
        enqueueFile("""{"version":1,"trips":[${(bad + trip).joinToString(",")}]}""")
        assertEquals(1, ImportFetcher(server.client(), repo).fetchAll("driver"))
    }

    @Test fun downloadFailureDoesNotDeleteFile() = runTest {
        server.reply("""[{"name":"past.json"}]"""); server.reply("{}", 500)
        assertFailsWith<SupabaseException> { ImportFetcher(server.client(), repo).fetchAll("driver") }
        assertEquals(2, server.requestCount)
    }

    @Test fun saveFailureStillDeletesFile() = runTest {
        enqueueFile(); repo.failSave = true
        assertFailsWith<IllegalStateException> { ImportFetcher(server.client(), repo).fetchAll("driver") }
        server.takeRequest(); server.takeRequest()
        assertEquals("DELETE", server.takeRequest().method)
    }
}
