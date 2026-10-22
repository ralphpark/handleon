package ai.handleon.car.centers

import ai.handleon.driveprint.Geo
import ai.handleon.driveprint.LatLon

data class Center(val name: String, val address: String, val phone: String, val pos: LatLon)

object Centers {
    fun parse(csv: String): List<Center> {
        val rows = csv.lineSequence().filter { it.isNotBlank() }.iterator()
        if (!rows.hasNext()) return emptyList()
        require(parseRow(rows.next().removePrefix("\uFEFF")) == listOf("name", "address", "phone", "lat", "lon")) {
            "센터 자료의 머리줄을 확인해 주세요"
        }
        return rows.asSequence().mapNotNull { row ->
            val fields = parseRow(row)?.takeIf { it.size == 5 } ?: return@mapNotNull null
            val lat = fields[3].toDoubleOrNull() ?: return@mapNotNull null
            val lon = fields[4].toDoubleOrNull() ?: return@mapNotNull null
            if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) {
                return@mapNotNull null
            }
            Center(fields[0], fields[1], fields[2], LatLon(lat, lon))
        }.toList()
    }

    fun nearest(all: List<Center>, from: LatLon, n: Int = 3): List<Pair<Center, Double>> =
        all.map { it to Geo.haversineKm(from, it.pos) }.sortedBy { it.second }.take(n.coerceAtLeast(0))

    /** 한 줄이 한 기관인 CSV. 큰따옴표 안의 쉼표와 두 번 쓴 큰따옴표를 보존한다. */
    private fun parseRow(row: String): List<String>? {
        val fields = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var closedQuote = false
        var index = 0
        while (index < row.length) {
            val char = row[index]
            if (quoted) {
                if (char != '"') field.append(char)
                else if (row.getOrNull(index + 1) == '"') {
                    field.append('"')
                    index++
                } else {
                    quoted = false
                    closedQuote = true
                }
            } else when {
                char == ',' -> {
                    fields.add(field.toString().trim())
                    field.clear()
                    closedQuote = false
                }
                char == '"' && field.isBlank() && !closedQuote -> {
                    field.clear()
                    quoted = true
                }
                char == '"' || (closedQuote && !char.isWhitespace()) -> return null
                else -> field.append(char)
            }
            index++
        }
        if (quoted) return null
        fields.add(field.toString().trim())
        return fields
    }
}
