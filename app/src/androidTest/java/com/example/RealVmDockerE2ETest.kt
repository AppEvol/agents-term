package com.example

import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.printToString
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.model.Statement

/**
 * End-to-end instrumented tests connecting the real Android app on the Emulator to a real Linux
 * Docker VM container over `adb reverse` (real OpenSSH -> real daemon install -> real HTTP/WS ->
 * real bash & deterministic agent CLIs -> real live app debugging via `agents_term_app_cli`).
 *
 * Activated when instrumentation argument `realVmSshPort` is supplied (see `.github/workflows/ci.yml`).
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class RealVmDockerE2ETest {

    @get:Rule(order = 0)
    val windowFocusDiagnostics = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                try {
                    base.evaluate()
                } catch (t: Throwable) {
                    val focus = runCatching {
                        shell("dumpsys window windows").lineSequence()
                            .filter { "mCurrentFocus" in it || "mFocusedApp" in it || "mObscuringWindow" in it }
                            .joinToString(" | ") { it.trim() }
                    }.getOrElse { "unavailable: $it" }
                    throw AssertionError("${t.message}\n[window focus at failure] $focus", t)
                }
            }
        }
    }

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    private var scenario: ActivityScenario<MainActivity>? = null

    private fun arg(key: String, default: String = ""): String = InstrumentationRegistry.getArguments()
        .getString(key)?.takeIf { it.isNotBlank() } ?: default

    private fun requireDockerVmArgs() {
        assumeTrue(
            "Skipping RealVmDockerE2ETest: pass -Pandroid.testInstrumentationRunnerArguments.realVmSshPort=...",
            arg("realVmSshPort").isNotBlank(),
        )
    }

    private fun shell(command: String): String {
        val fd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(fd).bufferedReader().use { it.readText() }
    }

    @Before
    fun setUp() {
        requireDockerVmArgs()
        shell("am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("herdr_mobile_prefs", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @After
    fun tearDown() {
        scenario?.close()
        scenario = null
    }

    private fun waitForText(text: String, substring: Boolean = false, timeoutMs: Long = 30_000L) {
        val matcher = hasText(text, substring = substring)
        var lastError: Throwable? = null
        try {
            composeRule.waitUntil(timeoutMillis = timeoutMs) {
                runCatching {
                    composeRule.onAllNodesWithText(text, substring = substring)
                        .fetchSemanticsNodes().isNotEmpty()
                }.onFailure { lastError = it }.getOrDefault(false)
            }
        } catch (e: Throwable) {
            val tree = runCatching {
                composeRule.onNode(matcher).printToString()
            }.getOrElse { lastError?.message ?: e.message }
            throw AssertionError("Timed out waiting for text '$text' (substring=$substring): $tree", e)
        }
    }

    private fun waitForTag(tag: String, timeoutMs: Long = 20_000L) {
        try {
            composeRule.waitUntil(timeoutMillis = timeoutMs) {
                runCatching {
                    composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
                }.getOrDefault(false)
            }
        } catch (e: Throwable) {
            throw AssertionError("Timed out waiting for tag '$tag'", e)
        }
    }

    private fun waitForTagGone(tag: String, timeoutMs: Long = 20_000L) {
        try {
            composeRule.waitUntil(timeoutMillis = timeoutMs) {
                runCatching {
                    composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty()
                }.getOrDefault(false)
            }
        } catch (e: Throwable) {
            throw AssertionError("Timed out waiting for tag '$tag' to disappear", e)
        }
    }

    private fun bootstrapOverRealSsh() {
        val host = arg("realVmSshHost", "127.0.0.1")
        val sshPort = arg("realVmSshPort")
        val user = arg("realVmSshUser", "vmuser")
        val pass = arg("realVmSshPass", "agentsterm-e2e-pass")
        val daemonPort = arg("realVmDaemonPort", "7591")

        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitForTag("ssh_host_input")

        composeRule.onNodeWithTag("ssh_host_input").performScrollTo().performTextReplacement(host)
        composeRule.onNodeWithTag("ssh_username_input").performScrollTo().performTextReplacement(user)
        composeRule.onNodeWithTag("ssh_port_input").performScrollTo().performTextReplacement(sshPort)
        composeRule.onNodeWithTag("ssh_daemon_port_input").performScrollTo().performTextReplacement(daemonPort)
        composeRule.onNodeWithTag("auth_password").performScrollTo().performClick()
        waitForTag("ssh_password_input")
        composeRule.onNodeWithTag("ssh_password_input").performScrollTo().performTextReplacement(pass)
        Espresso.closeSoftKeyboard()

        composeRule.onNodeWithTag("ssh_bootstrap_button").performScrollTo().performClick()
        // Wait until ConnectScreen closes and the workspace top bar appears (works with 0 or >0 tabs).
        waitForTag("top_new_tab_button", timeoutMs = 60_000L)
    }

    private fun createTab(agentType: String, name: String) {
        val hasEmptyButton = runCatching {
            composeRule.onAllNodes(hasTestTag("empty_new_tab_button")).fetchSemanticsNodes().isNotEmpty()
        }.getOrDefault(false)
        if (hasEmptyButton) {
            composeRule.onNodeWithTag("empty_new_tab_button").performClick()
        } else {
            composeRule.onNodeWithTag("top_new_tab_button").performClick()
        }
        waitForText("New Persistent Agent Tab")
        composeRule.onNodeWithTag("agent_option_$agentType").performScrollTo().performClick()
        composeRule.onNodeWithTag("new_tab_name_input").performScrollTo().performTextReplacement(name)
        Espresso.closeSoftKeyboard()
        composeRule.onNodeWithTag("create_tab_button").performScrollTo().performClick()
        // Wait for NewTabSheet to dismiss first: otherwise waitForText(name) can match
        // new_tab_name_input while POST /api/tabs is still in flight.
        waitForTagGone("create_tab_button")
        waitForText(name)
        waitForTag("composer_input")
    }

    @Test
    fun test01_sshBootstrapRealDockerVmAndRunShellAndClaudeTabs() {
        bootstrapOverRealSsh()

        // 1. Real Linux shell tab executing inside the Docker container.
        createTab("shell", "Real Shell")
        composeRule.onNodeWithTag("composer_input").performTextInput("echo REAL_DOCKER_SHELL_\$(uname -s)")
        composeRule.onNodeWithTag("send_button").performClick()
        waitForText("REAL_DOCKER_SHELL_Linux", substring = true, timeoutMs = 20_000L)

        // 2. Real Claude runner executing the deterministic fake CLI inside Docker.
        createTab("claude", "Real Claude")
        composeRule.onNodeWithTag("composer_input").performTextInput("Verify container status")
        composeRule.onNodeWithTag("send_button").performClick()
        waitForText("Claude reply [sess=claude-sess-e2e-1", substring = true, timeoutMs = 20_000L)
        waitForText("Docker E2E Claude Task", substring = true, timeoutMs = 15_000L)
    }

    @Test
    fun test02_idempotentReBootstrapAndLiveAppDebuggingFromDockerVm() {
        // Re-running SSH bootstrap against the already-running Docker daemon must succeed idempotently.
        bootstrapOverRealSsh()

        // Enable remote inspection in Settings so agents_term_app_cli inside Docker can query this live app.
        composeRule.onNodeWithTag("overflow_menu_button").performClick()
        composeRule.onNodeWithTag("menu_app_settings").performClick()
        waitForTag("debug_sharing_switch")
        composeRule.onNodeWithTag("debug_sharing_switch").performScrollTo().performClick()
        composeRule.onNodeWithTag("debug_remote_inspection_switch").performScrollTo().performClick()
        composeRule.onNodeWithTag("settings_back_button").performClick()
        waitForTag("top_new_tab_button")

        // Invoke native agents_term_app_cli symlink from inside the zero-dependency Docker VM via a real Shell tab.
        val daemonPort = arg("realVmDaemonPort", "7591")
        val cliCmd = arg("realVmAppStateCliCmd", "agents_term_app_cli")
        createTab("shell", "Live Debug Shell")
        composeRule.onNodeWithTag("composer_input")
            .performTextInput("$cliCmd --port $daemonPort state --pretty")
        composeRule.onNodeWithTag("send_button").performClick()
        waitForText("\"connectionState\": \"CONNECTED\"", substring = true, timeoutMs = 25_000L)
    }
}
