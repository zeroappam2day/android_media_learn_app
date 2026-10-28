package com.example

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.lifecycle.ViewModelStore
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.example.data.VideoRepository
import com.example.ui.VideoViewModel
import com.example.ui.theme.MyApplicationTheme
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class GreetingScreenshotTest {

  @get:Rule val composeTestRule = createComposeRule()

  @Test
  fun greeting_screenshot() {
    val application = ApplicationProvider.getApplicationContext<Application>()
    val store = ViewModelStore()
    val model = VideoViewModel(application, VideoRepository(application,
      credentialPreferences = application.getSharedPreferences("screenshot_test_credentials", 0)))
    store.put("screenshot", model)
    try {
      composeTestRule.setContent { MyApplicationTheme { MainScreen(viewModel = model, isDark = false, onToggleTheme = {}) } }
      composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/greeting.png")
    } finally {
      composeTestRule.runOnUiThread { store.clear() }
    }
  }
}
