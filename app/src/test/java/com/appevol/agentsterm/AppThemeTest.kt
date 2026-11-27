package com.appevol.agentsterm

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.appevol.agentsterm.data.AppThemeId
import com.appevol.agentsterm.data.SettingsStore
import com.appevol.agentsterm.ui.ThemePickerCard
import com.appevol.agentsterm.ui.theme.AppTheme
import com.appevol.agentsterm.ui.theme.DaylightTheme
import com.appevol.agentsterm.ui.theme.HerdrTheme
import com.appevol.agentsterm.ui.theme.MidnightTheme
import com.appevol.agentsterm.ui.theme.ThemedAlertDialog
import com.appevol.agentsterm.ui.theme.ThemedButton
import com.appevol.agentsterm.ui.theme.ThemedFilterChip
import com.appevol.agentsterm.ui.theme.ThemedSwitch
import com.appevol.agentsterm.ui.theme.ThemedTextButton
import com.appevol.agentsterm.ui.theme.ThemedTextField
import com.appevol.agentsterm.ui.theme.TitleBarIconButton
import com.appevol.agentsterm.ui.theme.XpLunaTheme
import com.appevol.agentsterm.ui.theme.appThemeSpec
import com.appevol.agentsterm.ui.theme.themedCorner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
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

    @Test
    fun windowsXpLookDrawsItsOwnControlsAndTheyStillWork() {
        var theme by mutableStateOf(AppThemeId.XP_LUNA)
        var checked by mutableStateOf(false)
        var chip by mutableStateOf(false)
        var clicks = 0
        composeRule.setContent {
            HerdrTheme(theme = theme) {
                Column {
                    ThemedButton(onClick = { clicks++ }, modifier = Modifier.testTag("button")) { Text("Go") }
                    ThemedButton(onClick = { clicks++ }, enabled = false, modifier = Modifier.testTag("disabled")) {
                        Text("No")
                    }
                    ThemedSwitch(checked = checked, onCheckedChange = { checked = it }, modifier = Modifier.testTag("switch"))
                    ThemedFilterChip(
                        selected = chip,
                        onClick = { chip = !chip },
                        label = { Text("Chip") },
                        modifier = Modifier.testTag("chip"),
                    )
                    TitleBarIconButton(onClick = { clicks++ }, modifier = Modifier.testTag("caption")) { Text("+") }
                }
            }
        }

        // The same taps do the same things whichever theme draws the controls.
        for (id in listOf(AppThemeId.XP_LUNA, AppThemeId.MIDNIGHT)) {
            theme = id
            checked = false
            chip = false
            clicks = 0
            composeRule.waitForIdle()

            composeRule.onNodeWithTag("button").assertHasClickAction().performClick()
            composeRule.onNodeWithTag("caption").assertHasClickAction().performClick()
            composeRule.onNodeWithTag("disabled").assertIsNotEnabled().performClick()
            composeRule.runOnIdle { assertEquals("$id", 2, clicks) }

            composeRule.onNodeWithTag("switch").assertIsOff().performClick()
            composeRule.onNodeWithTag("switch").assertIsOn()
            composeRule.runOnIdle { assertTrue("$id", checked) }

            composeRule.onNodeWithTag("chip").assertIsNotSelected().performClick()
            composeRule.onNodeWithTag("chip").assertIsSelected()
        }
    }

    @Test
    fun windowsXpTextBoxTakesTypedTextAndKeepsItsLabel() {
        var text by mutableStateOf("")
        composeRule.setContent {
            HerdrTheme(theme = AppThemeId.XP_LUNA) {
                ThemedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Host") },
                    modifier = Modifier.testTag("field"),
                )
            }
        }

        composeRule.onNodeWithText("Host").assertIsDisplayed()
        composeRule.onNodeWithTag("field").performTextInput("devbox")
        composeRule.runOnIdle { assertEquals("devbox", text) }
    }

    @Test
    fun windowsXpDialogIsAWindowWhoseCloseButtonDismissesIt() {
        var dismissed = 0
        var saved = 0
        composeRule.setContent {
            HerdrTheme(theme = AppThemeId.XP_LUNA) {
                ThemedAlertDialog(
                    onDismissRequest = { dismissed++ },
                    title = { Text("Rename tab") },
                    text = { Text("Pick a new name.") },
                    confirmButton = {
                        ThemedTextButton(onClick = { saved++ }, modifier = Modifier.testTag("save")) { Text("Save") }
                    },
                    dismissButton = { ThemedTextButton(onClick = { dismissed++ }) { Text("Cancel") } },
                )
            }
        }

        composeRule.onNodeWithText("Rename tab").assertIsDisplayed()
        composeRule.onNodeWithText("Pick a new name.").assertIsDisplayed()
        composeRule.onNodeWithTag("save").performClick()
        composeRule.onNodeWithContentDescription("Close").performClick()
        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.runOnIdle {
            assertEquals(1, saved)
            assertEquals(2, dismissed)
        }
    }
}
