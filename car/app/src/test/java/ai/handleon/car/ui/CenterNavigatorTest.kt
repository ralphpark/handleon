package ai.handleon.car.ui

import ai.handleon.car.centers.Center
import ai.handleon.driveprint.LatLon
import ai.pleos.playground.navi.data.RouteInfo
import org.junit.Test
import kotlin.test.*

class CenterNavigatorTest {
    private class Session : NavigationSession {
        lateinit var connected: () -> Unit
        val routes = mutableListOf<RouteInfo>()
        var closed = false
        var failRoute = false
        override fun connect(onConnected: () -> Unit, onFailed: () -> Unit) { connected = onConnected }
        override fun route(info: RouteInfo) { if (failRoute) error("private SDK failure"); routes += info }
        override fun close() { closed = true }
    }
    @Test fun `home route waits for SDK connection and stops if driving begins`() {
        val session = Session()
        val navigator = CenterNavigator({ session }, { it() })
        var parked = true
        navigator.request(LatLon(37.4, 127.1), canRequest = { parked }) { }
        assertTrue(session.routes.isEmpty())
        parked = false
        session.connected()
        assertTrue(session.routes.isEmpty())
    }
    @Test fun `home coordinates and existing center fields reach the SDK without stale callbacks`() {
        val sessions = mutableListOf<Session>()
        val navigator = CenterNavigator({ Session().also { sessions += it } }, { it() })
        navigator.request(LatLon(37.4, 127.1)) { }
        val home = sessions.last()
        home.connected()
        assertEquals(37.4, home.routes.single().latitude)
        assertEquals(127.1, home.routes.single().longitude)
        assertEquals("집", home.routes.single().poiName)
        navigator.request(Center("센터", "센터 주소", "", LatLon(35.0, 128.0))) { }
        home.connected()
        assertEquals(1, home.routes.size)
        assertTrue(home.closed)
        sessions.last().connected()
        assertEquals("센터 주소", sessions.last().routes.single().address)
        assertEquals("센터", sessions.last().routes.single().poiName)
    }
    @Test fun `route failure shows the existing fixed message without SDK details`() {
        val session = Session().also { it.failRoute = true }
        val messages = mutableListOf<String>()
        val navigator = CenterNavigator({ session }, { it() })
        navigator.request(LatLon(37.4, 127.1)) { messages += it }
        session.connected()
        assertEquals("길안내를 요청하지 못했어요. 잠시 후 다시 시도해 주세요.", messages.last())
        assertFalse(messages.joinToString().contains("private"))
    }
}
