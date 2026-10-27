package ai.handleon.car.gleo

import ai.handleon.car.ui.PendingConfirm
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Test
import kotlin.test.*

class GleoReceiverTest {
    @Test fun `broadcast handler asks without loading or changing driver and sharing data`() = runTest {
        var pending: PendingConfirm? = null
        val callback = dispatchGleo("MARK_OTHER_DRIVER", "{}", { error("must not read or mutate data") },
            { action -> if (pending == null) { pending = action; GleoAskResult.QUEUED } else GleoAskResult.BUSY })
        assertEquals("asking", callback.status)
        assertEquals(PendingConfirm.MarkOtherDriver, pending)
        val second = dispatchGleo("PAUSE_FAMILY_SHARING", "{}", { error("must not read") }, { GleoAskResult.BUSY })
        assertEquals("fail", second.status)
        assertEquals(PendingConfirm.MarkOtherDriver, pending)
    }
    @Test fun `invalid requests and storage errors never echo input or exception details`() = runTest {
        val invalid = dispatchGleo("GET_DRIVING_SUMMARY", "bad private location", { error("must not read") }, { error("must not ask") })
        assertEquals("fail", invalid.status)
        val failed = dispatchGleo("GET_DRIVING_SUMMARY", """{"period":"today"}""",
            { throw java.io.IOException("private coordinates and token") }, { error("must not ask") })
        assertEquals("error", failed.status)
        assertFalse(failed.toString().contains("private"))
        val unavailable = dispatchGleo("MARK_OTHER_DRIVER", "{}", { error("must not load summaries") }, { GleoAskResult.UNAVAILABLE })
        assertEquals("fail", unavailable.status)
        val targetFailure = dispatchGleo("MARK_OTHER_DRIVER", "{}", { error("must not load summaries") },
            { throw java.io.IOException("private record") })
        assertEquals("error", targetFailure.status)
        assertFalse(targetFailure.toString().contains("private"))
    }
    @Test fun `wire uses documented response action extras and json array string`() {
        val callback = GleoCallback("GET_PROMISE_STATUS", "success", "Here are the weekly promise counts.", "[]")
        assertEquals("ACTION_PARAM", GleoWire.PARAM)
        assertEquals("ai.pleos.playground.intent.action.APP_ACTION_RESPONSE", GleoWire.RESPONSE_ACTION)
        val extras = GleoWire.extras(callback, "ai.handleon.car")
        assertEquals(setOf("result", "packageName", "response"), extras.keys)
        assertEquals("success", extras["result"])
        val response = Json.parseToJsonElement(extras.getValue("response")).jsonObject
        assertEquals("[]", response["data"]!!.jsonPrimitive.content)
        assertEquals(extras["response"], GleoWire.mockResponse("ai.handleon.car", extras["packageName"], extras["response"]))
        assertNull(GleoWire.mockResponse("ai.handleon.car", "another.app", extras["response"]))
        assertNull(GleoWire.mockResponse("ai.handleon.car", "ai.handleon.car", """{"message":"private location"}"""))
    }
    @Test fun `mock panel is hidden while driving even in the presentation engine screen`() {
        assertTrue(gleoMockVisible(true, false))
        assertFalse(gleoMockVisible(true, true))
        assertFalse(gleoMockVisible(false, false))
    }
}
