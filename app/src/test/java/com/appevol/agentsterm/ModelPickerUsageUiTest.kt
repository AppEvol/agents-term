package com.appevol.agentsterm

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.appevol.agentsterm.data.AgentModelOption
import com.appevol.agentsterm.data.AgentUsageItem
import com.appevol.agentsterm.data.AgentUsageResponse
import com.appevol.agentsterm.data.AgentUsageUiState
import com.appevol.agentsterm.ui.ModelPickerSheetContent
import com.appevol.agentsterm.ui.theme.AgentsTermTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h915dp")
class ModelPickerUsageUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val sampleModels = listOf(
        AgentModelOption("gemini-4-neon-high", "Gemini 4 Neon (High)"),
        AgentModelOption("claude-opus-5-5-high", "Claude Opus 5.5 (High)"),
    )

    private fun sampleUsageResponse(nowMs: Long) = AgentUsageResponse(
        agent = "agy",
        fetchedAt = nowMs,
        plan = "pro",
        items = listOf(
            AgentUsageItem(
                id = "gemini-3-flash",
                label = "Gemini Models",
                remainingPercent = 76.0,
                usedPercent = 24.0,
                resetsAtMs = nowMs + (2L * 3_600_000L) + (35L * 60_000L),
                modelPrefixes = listOf("gemini-"),
            ),
            AgentUsageItem(
                id = "claude-opus-5-5-high",
                label = "Claude Opus 5.5",
                remainingPercent = 100.0,
                usedPercent = 0.0,
                resetsAtMs = nowMs + (5L * 3_600_000L),
                modelIds = listOf("claude-opus-5-5-high"),
            ),
        ),
    )

    @Test
    fun throughputAppearsOnTheMatchingModelWithoutCheckingQuota() {
        var rates by mutableStateOf(emptyMap<String, String>())
        composeRule.setContent {
            AgentsTermTheme {
                ModelPickerSheetContent(
                    agentLabel = "Antigravity",
                    currentModel = null,
                    defaultModel = null,
                    models = sampleModels,
                    isRefreshing = false,
                    throughput = rates,
                    onRefresh = {},
                    onSelectModel = {},
                )
            }
        }
        composeRule.runOnIdle { rates = mapOf(sampleModels.first().id to "24.5 tokens/s") }
        composeRule.onNodeWithTag("model_option_${sampleModels.first().id}").assertTextContains("24.5 tokens/s")
        composeRule.runOnIdle { rates = emptyMap() }
        composeRule.onNodeWithText("24.5 tokens/s").assertDoesNotExist()
    }

    @Test
    fun clickingUsageButtonGeneratesUsageLeftAndResetTime() {
        val nowMs = 1_700_000_000_000L
        var checkClicks = 0
        var usageState by mutableStateOf(AgentUsageUiState())

        composeRule.setContent {
            AgentsTermTheme {
                ModelPickerSheetContent(
                    agentLabel = "Antigravity",
                    currentModel = "gemini-4-neon-high",
                    defaultModel = "gemini-4-neon-high",
                    models = sampleModels,
                    isRefreshing = false,
                    usageState = usageState,
                    onCheckUsage = {
                        checkClicks += 1
                        usageState = AgentUsageUiState(response = sampleUsageResponse(nowMs))
                    },
                    onRefresh = {},
                    onSelectModel = {},
                    nowMs = nowMs,
                )
            }
        }

        assertEquals(0, composeRule.onAllNodesWithTag("model_picker_usage_panel").fetchSemanticsNodes().size)

        composeRule.onNodeWithTag("model_picker_usage_button").assertIsDisplayed().performClick()
        assertEquals(1, checkClicks)

        composeRule.onNodeWithTag("model_picker_usage_panel").assertIsDisplayed()
        composeRule.onNodeWithText("PRO").assertIsDisplayed()
        composeRule.onNodeWithText("Gemini Models").assertIsDisplayed()
        composeRule.onNodeWithText("76% left").assertIsDisplayed()
        composeRule.onNodeWithText("Resets in 2h 35m").assertIsDisplayed()
        composeRule.onNodeWithText("Claude Opus 5.5").assertIsDisplayed()
        composeRule.onNodeWithText("100% left").assertIsDisplayed()
        composeRule.onNodeWithText("Resets in 5h").assertIsDisplayed()
        composeRule.onNodeWithText("100% left · Resets in 5h").assertIsDisplayed()
    }

    @Test
    fun usageErrorIsDisplayedInPanel() {
        composeRule.setContent {
            AgentsTermTheme {
                ModelPickerSheetContent(
                    agentLabel = "Claude",
                    currentModel = null,
                    defaultModel = "opus",
                    models = listOf(AgentModelOption("opus", "Opus")),
                    isRefreshing = false,
                    usageState = AgentUsageUiState(error = "Claude OAuth token expired"),
                    onCheckUsage = {},
                    onRefresh = {},
                    onSelectModel = {},
                )
            }
        }

        composeRule.onNodeWithTag("model_picker_usage_panel").assertIsDisplayed()
        composeRule.onNodeWithTag("model_picker_usage_error").assertIsDisplayed()
        composeRule.onNodeWithText("Claude OAuth token expired").assertIsDisplayed()
    }
}
