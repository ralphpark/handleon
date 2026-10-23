package ai.handleon.car.sync

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.serialization.json.*

class Pairing(private val client: SupabaseClient) {
    suspend fun newCode(): String {
        val code = (client.rpc("create_pairing_code") as? JsonPrimitive)?.contentOrNull
        if (code == null || !code.matches(Regex("[A-Z0-9]{6}"))) throw SupabaseException(200, "invalid_response")
        return code
    }
    suspend fun linkedDriver(): String? {
        val uid = client.ensureSession()
        return (client.select("car_links", "select=driver_id&car_id=eq.$uid").firstOrNull()
            as? JsonObject)?.get("driver_id")?.jsonPrimitive?.contentOrNull
    }
}

fun qrMatrix(code: String): BitMatrix = QRCodeWriter().encode("handleon://pair?code=$code", BarcodeFormat.QR_CODE, 0, 0,
    mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 1))
