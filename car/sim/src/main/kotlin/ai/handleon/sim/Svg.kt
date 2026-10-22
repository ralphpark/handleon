package ai.handleon.sim

object Svg {
    fun bars(title: String, labels: List<String>, values: List<Double>): String {
        require(labels.size == values.size && values.all { it.isFinite() && it >= 0 })
        val max = maxOf(1.0, values.maxOrNull() ?: 1.0)
        val body = buildString {
            append("""<path d="M60 70V300H610" fill="none" stroke="#64748b"/>""")
            values.forEachIndexed { i, value ->
                val width = 480.0 / values.size
                val x = 80 + i * width
                val height = 200 * value / max
                val y = 300 - height
                val barWidth = width * 0.6
                append("""<rect x="$x" y="$y" width="$barWidth" height="$height" fill="#0f766e"/>""")
                append(text(x, 325.0, labels[i]))
                append(text(x, y - 10, value.toString()))
            }
        }
        return root(title, body)
    }

    fun lines(title: String, xLabels: List<String>, series: Map<String, List<Double?>>): String {
        require(series.values.all { it.size == xLabels.size && it.all { v -> v == null || v.isFinite() } })
        val known = series.values.flatten().filterNotNull()
        val low = minOf(0.0, known.minOrNull() ?: 0.0)
        val high = maxOf(low + 1, known.maxOrNull() ?: 1.0)
        val colors = listOf("#0f766e", "#b45309", "#4338ca", "#be123c")
        val body = buildString {
            append("""<path d="M60 70V300H610" fill="none" stroke="#64748b"/>""")
            series.entries.forEachIndexed { index, (name, values) ->
                val color = colors[index % colors.size]
                val segment = mutableListOf<Pair<Double, Double>>()
                fun flushSegment() {
                    if (segment.size > 1) {
                        val points = segment.joinToString(" ") { (x, y) -> "$x,$y" }
                        append("""<polyline points="$points" fill="none" stroke="$color" stroke-width="2"/>""")
                    } else if (segment.size == 1) {
                        val (x, y) = segment.single()
                        append("""<circle cx="$x" cy="$y" r="3" fill="$color"/>""")
                    }
                    segment.clear()
                }
                values.forEachIndexed { i, value ->
                    if (value == null) flushSegment() else segment.add(
                        (60 + 540.0 * i / maxOf(1, values.lastIndex)) to (300 - 220 * (value - low) / (high - low)))
                }
                flushSegment()
                append(text(70.0 + index * 140, 65.0, name, color))
            }
            if (xLabels.isNotEmpty()) {
                append(text(60.0, 325.0, xLabels.first()))
                append(text(530.0, 325.0, xLabels.last()))
            }
            append(text(5.0, 90.0, high.toString().take(6)))
            append(text(5.0, 300.0, low.toString().take(6)))
        }
        return root(title, body)
    }

    private fun root(title: String, body: String): String {
        val safe = escape(title)
        return """<svg xmlns="http://www.w3.org/2000/svg" width="640" height="360" viewBox="0 0 640 360"><title>$safe</title><rect width="640" height="360" fill="white"/><text x="30" y="32" font-family="sans-serif" font-size="20" fill="#0f172a">$safe</text>$body</svg>"""
    }
    private fun text(x: Double, y: Double, value: String, color: String = "#334155"): String {
        val safe = escape(value)
        return """<text x="$x" y="$y" fill="$color" font-family="sans-serif" font-size="12">$safe</text>"""
    }
    private fun escape(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
