package ai.handleon.car.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** 24 × 24 SVG paths from the supplied Midnight HTML. No icon dependency. */
object MidnightIcons {
    private fun icon(name: String, paths: String) = ImageVector.Builder(name, 32.dp, 32.dp, 24f, 24f)
        .addPath(addPathNodes(paths), fill = null, stroke = SolidColor(Color.White),
            strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round).build()
    val Wheel = icon("Wheel", "M3.5 12a8.5 8.5 0 1 0 17 0a8.5 8.5 0 1 0 -17 0 M9.8 12a2.2 2.2 0 1 0 4.4 0a2.2 2.2 0 1 0 -4.4 0 M3.8 10.5h6 M14.2 10.5h6 M12 14.2V20.5")
    val Settings = icon("Settings", "M9 12a3 3 0 1 0 6 0a3 3 0 1 0 -6 0 M12 2v3 M12 19v3 M2 12h3 M19 12h3 M4.9 4.9l2.1 2.1 M17 17l2.1 2.1 M4.9 19.1L7 17 M17 7l2.1-2.1")
    val Tag = icon("Tag", "M3 12V4h8l9 9-8 8z M6 7.5a1.5 1.5 0 1 0 3 0a1.5 1.5 0 1 0-3 0")
    val Pin = icon("Pin", "M12 21s-7-6.2-7-11.5A7 7 0 0 1 19 9.5C19 14.8 12 21 12 21z M9.5 9.5a2.5 2.5 0 1 0 5 0a2.5 2.5 0 1 0-5 0")
    val Route = icon("Route", "M4 19c4-1 4-6 8-7s4-6 8-7 M16 5h4v4 M2.5 19a1.5 1.5 0 1 0 3 0a1.5 1.5 0 1 0-3 0")
    val Turn = icon("Turn", "M10 7l-5 5 5 5 M5 12h9a5 5 0 0 1 5 5v2")
    val Moon = icon("Moon", "M20 14.5A8 8 0 1 1 9.5 4a6.5 6.5 0 0 0 10.5 10.5z")
    val Back = icon("Back", "M15 5l-7 7 7 7")
    val Next = icon("Next", "M9 6l6 6-6 6")
    val Check = icon("Check", "M5 12l4 4L19 6")
    val Close = icon("Close", "M6 6l12 12 M18 6L6 18")
    val Medicine = icon("Medicine", "M6 9h12a3 3 0 0 1 0 6H6a3 3 0 0 1 0-6z M12 9v6")
    val Car = icon("Car", "M5 16l1.5-5h11L19 16 M4.5 16h15a1.5 1.5 0 0 1 0 3h-15a1.5 1.5 0 0 1 0-3z M8 4l3 3-3 3 M16 4l-3 3 3 3")
    val Thermometer = icon("Thermometer", "M12 3v11 M8 17a4 4 0 1 0 8 0a4 4 0 1 0-8 0 M9 3h6")
    val Travel = icon("Travel", "M3 20l18-8L3 4l3 8z")
    val Sleep = icon("Sleep", "M20 14.5A8 8 0 0 1 9.5 4a8 8 0 1 0 10.5 10.5z")
    val Heart = icon("Heart", "M20.8 4.6a5.5 5.5 0 0 0-7.8 0L12 5.7l-1.1-1.1a5.5 5.5 0 0 0-7.8 7.8L12 21l8.8-8.6a5.5 5.5 0 0 0 0-7.8z")
}
