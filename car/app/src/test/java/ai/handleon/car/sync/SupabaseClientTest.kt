package ai.handleon.car.sync

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Test
import kotlin.test.*

class SupabaseClientTest {
    private val server = MockWebServer()
    @After fun close() = server.shutdown()

    @Test fun signsUpOnceThenReusesSession() = runTest {
        val tokens = MemoryTokens()
        server.reply(auth())
        val client = server.client(tokens)
        assertEquals("car-id", client.ensureSession())
        assertEquals("car-id", client.ensureSession())
        assertEquals(1, server.requestCount)
        val request = server.takeRequest()
        assertEquals("/auth/v1/signup", request.path)
        assertEquals("{}", request.body.readUtf8())
        assertEquals("anon-secret", request.getHeader("apikey"))
        assertEquals(4600, tokens.session!!.expiresAtSec)
    }

    @Test fun refreshesBeforeExpiryAndRetriesOn401() = runTest {
        server.reply(auth("access-1", "refresh-1"))
        server.reply("""{"code":"expired"}""", 401)
        server.reply(auth("access-2", "refresh-2"))
        server.reply("[]")
        val tokens = MemoryTokens(session(1060))
        server.client(tokens).select("car_links", "select=driver_id")
        assertEquals("/auth/v1/token?grant_type=refresh_token", server.takeRequest().path)
        assertEquals("Bearer access-1", server.takeRequest().getHeader("Authorization"))
        assertEquals("refresh-1", Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject["refresh_token"]!!.jsonPrimitive.content)
        assertEquals("Bearer access-2", server.takeRequest().getHeader("Authorization"))
        assertEquals(4, server.requestCount)
    }

    @Test fun second401IsNotRetried() = runTest {
        server.reply("{}", 401); server.reply(auth()); server.reply("{}", 401)
        assertEquals(401, assertFailsWith<SupabaseException> { server.client().rpc("test") }.status)
        assertEquals(3, server.requestCount)
    }

    @Test fun rejectedRefreshClearsSessionUntilNextCall() = runTest {
        val tokens = MemoryTokens(session(1001))
        val client = server.client(tokens)
        server.reply("""{"error":"refresh_token_not_found"}""", 400)
        assertEquals(400, assertFailsWith<SupabaseException> { client.ensureSession() }.status)
        assertNull(tokens.session)
        assertEquals(1, server.requestCount)
        server.reply(auth())
        client.ensureSession()
        server.takeRequest()
        assertEquals("/auth/v1/signup", server.takeRequest().path)
    }

    @Test fun errorsNeverContainTokens() = runTest {
        val client = server.client()
        for (body in listOf("""{"code":"access-secret","error":"refresh-secret","message":"anon-secret"}""",
            """{"message":"access-secret refresh-secret anon-secret"}""", "access-secret anon-secret")) {
            server.reply(body, 500)
            val failure = assertFailsWith<SupabaseException> { client.rpc("test") }
            for (secret in listOf("access-secret", "refresh-secret", "anon-secret")) assertFalse(secret in failure.toString())
        }
        server.reply("""{"code":"summary_changed"}""", 409)
        assertEquals("summary_changed", assertFailsWith<SupabaseException> { client.rpc("test") }.code)
    }

    @Test fun concurrentCallersShareSignup() = runTest {
        server.reply(auth())
        val client = server.client(MemoryTokens())
        (1..5).map { async { client.ensureSession() } }.awaitAll()
        assertEquals(1, server.requestCount)
    }
}
