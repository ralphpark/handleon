package ai.handleon.driveprint

import kotlin.math.*

object Geo {
    fun haversineKm(a: LatLon, b: LatLon): Double {
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val dLat = lat2 - lat1
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLon / 2).pow(2)
        return 2 * 6371.0088 * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    fun cell(p: LatLon): Long {
        val row = floor(p.lat / 0.009).toLong()
        val column = floor(p.lon / 0.0113).toLong()
        return (row shl 32) or (column and 0xffffffffL)
    }

    fun headingDelta(fromDeg: Double, toDeg: Double): Double =
        ((toDeg - fromDeg + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
}
