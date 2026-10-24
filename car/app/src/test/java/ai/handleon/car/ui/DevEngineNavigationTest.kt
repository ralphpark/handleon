package ai.handleon.car.ui

import kotlin.test.*

class DevEngineNavigationTest {
    @Test fun drivingExceptionOnlyAppliesToEnabledDevEngine() {
        for (screen in listOf(Screen.Home, Screen.Settings, Screen.Replay, Screen.DevEngine)) {
            assertEquals(screen != Screen.DevEngine, showDrivingScreen(true, true, screen))
            assertTrue(showDrivingScreen(true, false, screen))
            assertFalse(showDrivingScreen(false, true, screen))
        }
    }

    @Test fun disablingPresentationReturnsDevEngineToSettings() {
        assertEquals(Screen.Settings, presentationScreen(Screen.DevEngine, false))
        assertEquals(Screen.DevEngine, presentationScreen(Screen.DevEngine, true))
        assertEquals(Screen.Home, presentationScreen(Screen.Home, false))
        assertEquals(Screen.DevEngine, Screen.from("dev-engine"))
    }
}
