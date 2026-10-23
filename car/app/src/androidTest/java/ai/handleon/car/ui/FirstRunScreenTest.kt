package ai.handleon.car.ui

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import ai.handleon.car.ui.theme.HandleOnTheme
import java.time.LocalDate
import org.junit.Rule
import org.junit.Test

class FirstRunScreenTest {
    @get:Rule val compose = createComposeRule()
    private val today = LocalDate.of(2026, 10, 23)

    private fun content() = compose.setContent {
        var ui by remember { mutableStateOf(FirstRunUi()) }
        HandleOnTheme {
            FirstRunScreen(ui, today, onChange = { ui = it }, onStart = {})
        }
    }

    @Test fun startDisabledUntilAllConsents() {
        content()
        compose.onNodeWithTag("birth-year").performScrollTo().performTextInput("1956")
        compose.onNodeWithTag("start").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("consent-0").performScrollTo().performClick()
        compose.onNodeWithTag("consent-1").performScrollTo().performClick()
        compose.onNodeWithTag("start").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("consent-2").performScrollTo().performClick()
        compose.onNodeWithTag("start").performScrollTo().assertIsEnabled()
    }

    @Test fun age70PicksMaeumMode() {
        content()
        compose.onNodeWithTag("birth-year").performScrollTo().performTextInput("1956")
        compose.onNodeWithTag("mode-MAEUM").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("mode-CONDITION").assertIsNotSelected()
    }
}
