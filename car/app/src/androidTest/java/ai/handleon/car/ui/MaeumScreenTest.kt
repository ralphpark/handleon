package ai.handleon.car.ui

import ai.handleon.car.replay.ReplayParser
import ai.handleon.car.ui.theme.HandleOnTheme
import ai.handleon.driveprint.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

class MaeumScreenTest {
    @get:Rule val compose = createComposeRule()

    private fun father(): LongReport {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val trips = context.assets.open("replay/father.json").bufferedReader().use { ReplayParser.parse(it.readText()).trips }
        val end = Instant.ofEpochMilli(trips.maxOf { it.startMs }).atZone(ZoneId.of("Asia/Seoul"))
        return Driveprint().longReport(trips, emptySet(), emptySet(), YearMonth.from(end).plusMonths(1).atDay(1))
    }

    @Test fun talkStageShowsTitleTopThreeAndNotice() {
        val report = father()
        assertEquals(Stage.TALK, report.stage)
        assertEquals(3, report.top.size)
        compose.setContent { HandleOnTheme { MaeumScreen(MaeumUi.from(report, LatLon(37.4, 127.1))) } }
        compose.onNodeWithText(Copy.stageTitle(Stage.TALK)).performScrollTo().assertIsDisplayed()
        report.top.forEach { compose.onNodeWithText(Copy.line(it)).performScrollTo().assertIsDisplayed() }
        compose.onRoot().performTouchInput { swipeDown() }
        compose.onNodeWithText("가까운 곳 보기").assertIsDisplayed()
        compose.onNodeWithText(DISCLAIMER).assertIsDisplayed()
    }

    @Test fun talkReplayShowsCenterWithoutScroll() {
        val report = father()
        compose.setContent { HandleOnTheme { ReplayScreen(ReplayUi(maeum = MaeumUi.from(report, null), frame = 24), {}, {}) } }
        compose.onNodeWithText("가까운 곳 보기").assertIsDisplayed()
    }

    @Test fun noHomeShowsHint() {
        val report = father()
        compose.setContent { HandleOnTheme { MaeumScreen(MaeumUi.from(report, null)) } }
        compose.onNodeWithText(HOME_HINT).performScrollTo().assertIsDisplayed()
    }
}
