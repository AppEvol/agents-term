package com.example

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppThemeId
import com.example.data.SettingsStore
import com.example.ui.ThemePickerCard
import com.example.ui.theme.AppTheme
import com.example.ui.theme.DaylightTheme
import com.example.ui.theme.HerdrTheme
import com.example.ui.theme.MidnightTheme
import com.example.ui.theme.XpLunaTheme
import com.example.ui.theme.appThemeSpec
import com.example.ui.theme.themedCorner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class AppThemeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun windowsXpLookIsTheDefaultTheme() {
        assertEquals(AppThemeId.XP_LUNA, AppThemeId.Default)
        assertEquals(AppThemeId.XP_LUNA, AppThemeId.fromStorageKey(null))
        assertEquals(AppThemeId.XP_LUNA, AppThemeId.fromStorageKey("a_theme_from_a_newer_build"))

        val store = SettingsStore(ApplicationProvider.getApplicationContext<Context>())
        assertEquals(AppThemeId.XP_LUNA, store.loadAppTheme())
    }

    @Test
    fun chosenThemeSurvivesARestart() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        for (theme in AppThemeId.entries) {
            SettingsStore(context).saveAppTheme(theme)
            assertEquals(theme, SettingsStore(context).loadAppTheme())
            assertEquals(theme, AppThemeId.fromStorageKey(theme.storageKey))
        }
    }

    @Test
    fun matchSystemFollowsThePhonesDarkMode() {
        assertSame(MidnightTheme, appThemeSpec(AppThemeId.SYSTEM, systemInDarkTheme = true))
        assertSame(DaylightTheme, appThemeSpec(AppThemeId.SYSTEM, systemInDarkTheme = false))
        for (dark in listOf(true, false)) {
            assertSame(XpLunaTheme, appThemeSpec(AppThemeId.XP_LUNA, dark))
            assertSame(MidnightTheme, appThemeSpec(AppThemeId.MIDNIGHT, dark))
            assertSame(DaylightTheme, appThemeSpec(AppThemeId.DAYLIGHT, dark))
        }
    }

    @Test
    fun eachThemeAppliesItsOwnColorsAndCorners() {
        var theme by mutableStateOf(AppThemeId.XP_LUNA)
        var background = Color.Unspecified
        var success = Color.Unspecified
        var corner: Shape? = null
        composeRule.setContent {
            HerdrTheme(theme = theme) {
                background = MaterialTheme.colorScheme.background
                success = AppTheme.colors.success
                corner = themedCorner(14.dp)
            }
        }

        composeRule.waitForIdle()
        assertEquals(XpLunaTheme.colorScheme.background, background)
        assertEquals(XpLunaTheme.colors.success, success)
        // The XP look squares off every corner the app asks for.
        assertEquals(RoundedCornerShape(4.dp), corner)

        theme = AppThemeId.MIDNIGHT
        composeRule.waitForIdle()
        assertEquals(MidnightTheme.colorScheme.background, background)
        assertNotEquals(XpLunaTheme.colors.success, success)
        assertEquals(RoundedCornerShape(14.dp), corner)
    }

    @Test
    fun themePickerOffersEveryThemeAndReportsTheTappedOne() {
        var selected by mutableStateOf(AppThemeId.XP_LUNA)
        composeRule.setContent {
            HerdrTheme(theme = selected) {
                ThemePickerCard(selected = selected, onSelect = { selected = it })
            }
        }

        composeRule.onNodeWithTag("theme_option_xp_luna").assertIsSelected()
        for (theme in AppThemeId.entries.reversed()) {
            composeRule.onNodeWithTag("theme_option_${theme.storageKey}").performClick()
            composeRule.waitForIdle()
            assertEquals(theme, selected)
            composeRule.onNodeWithTag("theme_option_${theme.storageKey}").assertIsSelected()
        }
        composeRule.onNodeWithTag("theme_option_midnight").assertIsNotSelected()
    }
}
