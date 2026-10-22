package ai.handleon.sim

import ai.handleon.driveprint.Stage
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.*

class CohortRunTest {
    @Test fun detectionAndLeadUseFirstTalkInsideOnsetWindow() {
        val onset = CohortRun.START.plusMonths(10)
        fun outcome(vararg talkMonths: Int) = CohortRun.outcome(1, onset,
            List(30) { if (it in talkMonths) Stage.TALK else Stage.STABLE }, emptyList())

        val detected = outcome(4, 16, 20)
        assertEquals(CohortRun.START.plusMonths(4), detected.firstTalk)
        assertTrue(detected.detected)
        assertEquals(6.0, detected.leadMonths)
        assertFalse(detected.censored)
        assertFalse(outcome(4, 23).detected)
        assertNull(outcome(4, 23).leadMonths)
        assertEquals(12.0, outcome(10).leadMonths)
        assertEquals(0.0, outcome(22).leadMonths)
    }
    @Test fun smallCohortRunsAndProducesSaneNumbers() {
        val r = CohortRun.run(drivers = 20, months = 24, seed = 7)
        assertTrue(r.sensitivity in 0.0..1.0); assertTrue(r.falseAlarmsPerPersonYear >= 0.0)
        assertEquals(20, r.perPersona.size)
        for (p in r.perPersona) assertEquals(p.stages, RuleComparison.stages(p.monthly, CohortRun.START, 24, Rule.A))
        assertEquals(r.sensitivity, r.ruleComparison.getValue("A").sensitivity)
        assertEquals(setOf("A", "D", "K"), r.ruleComparison.keys)
    }
    @Test fun permanentLifestyleChangeKeepsBaselineAndThinsLaterMonths() {
        val persona = Persona(0, null, 60.0, 12, 30.0, 7)
        val original = Generator.trips(persona, CohortRun.START, 18)
        val variant = CohortRun.lifestyleTrips(original, persona.seed)
        fun month(t: ai.handleon.driveprint.TripFeatures) = java.time.YearMonth.from(
            java.time.Instant.ofEpochMilli(t.startMs).atZone(java.time.ZoneId.of("Asia/Seoul")))
        val onset = CohortRun.START.plusMonths(12)
        assertEquals(original.filter { month(it) < onset }, variant.filter { month(it) < onset })
        for ((m, trips) in original.groupBy(::month).filterKeys { it >= onset })
            assertEquals((trips.size * .8).toInt(), variant.count { month(it) == m })
        assertTrue(variant.all { it in original })
        assertTrue(variant.zipWithNext().all { (a, b) -> a.startMs <= b.startMs })
    }
    @Test fun svgIsWellFormed() = assertTrue(Svg.bars("t", listOf("a"), listOf(1.0)).startsWith("<svg"))
    @Test fun svgEscapesTextAndKeepsMissingValuesAsGaps() {
        val chart = Svg.lines("a<&", listOf("1", "2", "3"), mapOf("x<&" to listOf(1.0, null, 2.0)))
        val factory = DocumentBuilderFactory.newInstance()
        val document = factory.newDocumentBuilder().parse(ByteArrayInputStream(chart.toByteArray()))
        assertEquals("svg", document.documentElement.tagName)
        assertTrue("a&lt;&amp;" in chart)
        assertEquals(0, document.getElementsByTagName("polyline").length)
    }
}
