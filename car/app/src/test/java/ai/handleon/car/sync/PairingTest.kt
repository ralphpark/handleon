package ai.handleon.car.sync

import com.google.zxing.qrcode.decoder.Decoder
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockWebServer
import org.junit.Test
import kotlin.test.*

class PairingTest {
    @Test fun pairingQrEncodesDeepLink() {
        val matrix = qrMatrix("ABC123")
        // Remove the one-module quiet zone; Decoder consumes the QR module grid itself.
        val rect = matrix.enclosingRectangle
        val grid = com.google.zxing.common.BitMatrix(rect[2], rect[3])
        for (y in 0 until rect[3]) for (x in 0 until rect[2]) if (matrix[x + rect[0], y + rect[1]]) grid.set(x, y)
        assertEquals("handleon://pair?code=ABC123", Decoder().decode(grid).text)
    }

    @Test fun pairingReadsRpcAndCurrentCarsLink() = runTest {
        MockWebServer().use { server ->
            server.reply("\"ABC123\""); server.reply("""[{"driver_id":"driver"}]""")
            val pairing = Pairing(server.client())
            assertEquals("ABC123", pairing.newCode())
            assertEquals("driver", pairing.linkedDriver())
            assertEquals("/rest/v1/rpc/create_pairing_code", server.takeRequest().path)
            val link = server.takeRequest()
            assertEquals("eq.car-id", link.requestUrl!!.queryParameter("car_id"))
        }
    }
}
