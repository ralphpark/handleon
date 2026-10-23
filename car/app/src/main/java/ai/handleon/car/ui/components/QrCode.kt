package ai.handleon.car.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.google.zxing.common.BitMatrix
import kotlin.math.floor

@Composable
fun QrCode(matrix: BitMatrix, modifier: Modifier = Modifier) {
    Canvas(modifier.size(240.dp).semantics { contentDescription = "휴대폰 연결용 QR 코드" }) {
        drawRoundRect(Color.White, cornerRadius = CornerRadius(24.dp.toPx()))
        // Extra light padding outside ZXing's one-module margin keeps rounded corners clear.
        val module = floor((minOf(size.width, size.height) - 32.dp.toPx()) / maxOf(matrix.width, matrix.height)).coerceAtLeast(1f)
        val origin = Offset(floor((size.width - matrix.width * module) / 2), floor((size.height - matrix.height * module) / 2))
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
            if (matrix[x, y]) drawRect(Color.Black, origin + Offset(x * module, y * module), Size(module, module))
        }
    }
}
