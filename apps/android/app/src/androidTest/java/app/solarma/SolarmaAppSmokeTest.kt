package app.solarma

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke test: verifies the app launches and the main UI renders.
 *
 * Run on a device/emulator:
 *   ./gradlew connectedAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class SolarmaAppSmokeTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun appLaunches_andShowsSolarmaTitle() {
        // The main screen should display the app name somewhere.
        // This validates the entire Hilt → Compose → Navigation chain boots.
        composeTestRule
            .onNodeWithText("Solarma", substring = true, ignoreCase = true)
            .assertIsDisplayed()
    }
}
