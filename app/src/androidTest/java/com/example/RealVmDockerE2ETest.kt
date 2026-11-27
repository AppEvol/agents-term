package com.example

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.ParcelFileDescriptor
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.printToString
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.appWindowRoots
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.model.Statement
import java.io.File

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
                    val focus = queryWindowFocusDiagnostics()
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

    private fun queryWindowFocusDiagnostics(): String = runCatching {
        val lines = sequenceOf("dumpsys window displays", "dumpsys window")
            .map { shell(it) }
            .map { output ->
                output.lineSequence()
                    .filter { "mCurrentFocus" in it || "mFocusedApp" in it || "mObscuringWindow" in it }
                    .map { it.trim() }
                    .distinct()
                    .toList()
            }
            .firstOrNull { it.isNotEmpty() }
            .orEmpty()
        if (lines.isEmpty()) "(none found)" else lines.joinToString(" | ")
    }.getOrElse { "unavailable: $it" }

    private fun closeSoftKeyboard() {
        scenario?.onActivity { activity ->
            val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            for (root in appWindowRoots(activity).asReversed()) {
                val focused = root.findFocus()
                val token = focused?.windowToken ?: root.windowToken
                focused?.clearFocus()
                WindowCompat.getInsetsController(activity.window, root)
                    .hide(WindowInsetsCompat.Type.ime())
                imm?.hideSoftInputFromWindow(token, 0)
            }
        }
        composeRule.waitUntil(timeoutMillis = 3_000L) {
            var imeVisible = false
            scenario?.onActivity { activity ->
                imeVisible = appWindowRoots(activity).any { root ->
                    ViewCompat.getRootWindowInsets(root)
                        ?.isVisible(WindowInsetsCompat.Type.ime()) == true
                }
            }
            !imeVisible
        }
        composeRule.waitForIdle()
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
        context.getSharedPreferences("herdr_mobile_local_prefs", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @After
    fun tearDown() {
        scenario?.close()
        scenario = null
    }

    private fun formatFailureDiagnostics(header: String): String {
        val roots = runCatching { composeRule.onAllNodes(isRoot()).printToString(maxDepth = Int.MAX_VALUE) }
            .getOrElse { it.toString() }
        return "$header\nCompose roots:\n$roots"
    }

    private fun waitForCondition(
        description: String,
        timeoutMs: Long,
        condition: () -> Boolean,
    ) {
        try {
            composeRule.waitUntil(timeoutMillis = timeoutMs, condition = condition)
        } catch (e: ComposeTimeoutException) {
            throw AssertionError(
                formatFailureDiagnostics("Timed out waiting for $description."),
                e,
            )
        } catch (e: Throwable) {
            throw AssertionError(
                formatFailureDiagnostics(
                    "Error while waiting for $description (${e::class.simpleName}: ${e.message}).",
                ),
                e,
            )
        }
    }

    private fun waitForText(text: String, substring: Boolean = false, timeoutMs: Long = 30_000L) {
        waitForCondition("text '$text' (substring=$substring)", timeoutMs) {
            composeRule.onAllNodesWithText(text, substring = substring)
                .fetchSemanticsNodes(atLeastOneRootRequired = false)
                .isNotEmpty()
        }
    }

    private fun waitForTag(tag: String, timeoutMs: Long = 20_000L) {
        waitForCondition("tag '$tag'", timeoutMs) {
            composeRule.onAllNodesWithTag(tag)
                .fetchSemanticsNodes(atLeastOneRootRequired = false)
                .isNotEmpty()
        }
    }

    private fun waitForTagGone(tag: String, timeoutMs: Long = 20_000L) {
        waitForCondition("tag '$tag' to disappear", timeoutMs) {
            composeRule.onAllNodesWithTag(tag)
                .fetchSemanticsNodes()
                .isEmpty()
        }
    }

    private fun sendComposerMessage(text: String) {
        composeRule.onNodeWithTag("composer_input").performTextInput(text)
        composeRule.onNodeWithTag("send_button").performSemanticsAction(SemanticsActions.OnClick)
        waitForCondition("composer_input to clear after sending '$text'", timeoutMs = 5_000L) {
            val nodes = composeRule.onAllNodesWithTag("composer_input").fetchSemanticsNodes()
            nodes.isEmpty() || nodes.all { node ->
                node.config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty().isEmpty()
            }
        }
        closeSoftKeyboard()
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
        closeSoftKeyboard()

        composeRule.onNodeWithTag("ssh_bootstrap_button")
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.OnClick)
        // Wait until ConnectScreen closes and the workspace top bar appears (works with 0 or >0 tabs).
        waitForTag("top_new_tab_button", timeoutMs = 60_000L)
    }

    private fun createTab(agentType: String, name: String) {
        val hasEmptyButton = composeRule.onAllNodes(hasTestTag("empty_new_tab_button"))
            .fetchSemanticsNodes()
            .isNotEmpty()
        if (hasEmptyButton) {
            composeRule.onNodeWithTag("empty_new_tab_button").performClick()
        } else {
            composeRule.onNodeWithTag("top_new_tab_button").performClick()
        }
        waitForText("New Persistent Agent Tab")
        composeRule.onNodeWithTag("agent_option_$agentType").performScrollTo().performClick()
        composeRule.onNodeWithTag("new_tab_name_input").performScrollTo().performTextReplacement(name)
        closeSoftKeyboard()
        composeRule.onNodeWithTag("create_tab_button")
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.OnClick)
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
        sendComposerMessage("echo REAL_DOCKER_SHELL_\$(uname -s)")
        waitForText("REAL_DOCKER_SHELL_Linux", substring = true, timeoutMs = 20_000L)

        // 2. Real Claude runner executing the deterministic fake CLI inside Docker.
        createTab("claude", "Real Claude")
        sendComposerMessage("Verify container status")
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
        sendComposerMessage("$cliCmd --port $daemonPort state --pretty")
        waitForText("\"connectionState\": \"CONNECTED\"", substring = true, timeoutMs = 25_000L)
    }

    /** Puts a real PNG on the clipboard as a content URI, the way copying a screenshot does. */
    private fun copyScreenshotToClipboard(name: String, color: Int): Int {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(File(context.cacheDir, "camera_captures").apply { mkdirs() }, name)
        val bitmap = Bitmap.createBitmap(540, 960, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.debugfileprovider", file)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newUri(context.contentResolver, "screenshot", uri))
        }
        return file.length().toInt()
    }

    private fun waitForReadyAttachments(count: Int) {
        waitForCondition("$count uploaded image(s)", timeoutMs = 30_000L) {
            composeRule.onAllNodesWithContentDescription("Attached image")
                .fetchSemanticsNodes(atLeastOneRootRequired = false)
                .size == count
        }
    }

    @Test
    fun test03_pastedImagesAreStoredOnTheVmShownInTheChatAndOpenedByTheAgent() {
        bootstrapOverRealSsh()
        createTab("claude", "Real Images")
        waitForTag("attach_button")

        // Paste two copied screenshots into the composer; each uploads to the VM straight away.
        val firstSize = copyScreenshotToClipboard("e2e_shot_1.png", Color.rgb(20, 90, 200))
        composeRule.onNodeWithTag("composer_input").performSemanticsAction(SemanticsActions.PasteText)
        waitForReadyAttachments(1)
        val secondSize = copyScreenshotToClipboard("e2e_shot_2.png", Color.rgb(200, 60, 20))
        composeRule.onNodeWithTag("composer_input").performSemanticsAction(SemanticsActions.PasteText)
        waitForReadyAttachments(2)

        // One of them can be removed again before sending.
        val pendingTags = composeRule.onAllNodesWithContentDescription("Attached image").fetchSemanticsNodes()
            .map { it.config[SemanticsProperties.TestTag] }
        composeRule.onNodeWithTag(pendingTags.last().replace("pending_attachment_", "remove_attachment_")).performClick()
        waitForReadyAttachments(1)
        composeRule.onNodeWithTag("composer_input").performSemanticsAction(SemanticsActions.PasteText)
        waitForReadyAttachments(2)

        sendComposerMessage("What is wrong in these?")

        // The agent CLI on the VM opened both files at the paths it was handed. Small
        // screenshots are uploaded untouched, so the sizes it saw are the sizes that were pasted.
        waitForText("Claude reply [sess=claude-sess-e2e-1", substring = true, timeoutMs = 20_000L)
        waitForText(".png:$firstSize", substring = true)
        waitForText(".png:$secondSize", substring = true)
        assertTrue(composeRule.onAllNodesWithText("image-unreadable", substring = true).fetchSemanticsNodes().isEmpty())
        assertTrue(
            composeRule.onAllNodesWithText("image-outside-add-dir", substring = true).fetchSemanticsNodes().isEmpty(),
        )

        // The transcript shows the text and both images; the composer is empty again.
        composeRule.onNodeWithTag("chat_message_list").performScrollToIndex(0)
        waitForText("What is wrong in these?")
        waitForTag("message_attachments")
        composeRule.onNodeWithContentDescription("Sent image 1 of 2").assertExists()
        composeRule.onNodeWithContentDescription("Sent image 2 of 2").assertExists()
        assertTrue(composeRule.onAllNodesWithTag("pending_attachments_row").fetchSemanticsNodes().isEmpty())

        // Tapping a sent image opens it full size, downloaded back from the VM.
        // Opened through its click action rather than a touch at screen coordinates: the list
        // is still settling under the keyboard as the reply's footer arrives, and a touch aimed
        // at where the thumbnail was a moment ago can miss it.
        composeRule.onNodeWithContentDescription("Sent image 1 of 2")
            .performSemanticsAction(SemanticsActions.OnClick)
        waitForTag("attachment_viewer")
        waitForTag("attachment_viewer_image")
        composeRule.onNodeWithTag("attachment_viewer_close").performClick()
        waitForTagGone("attachment_viewer", timeoutMs = 10_000L)

        // An image with no text at all is a complete message.
        copyScreenshotToClipboard("e2e_shot_3.png", Color.rgb(30, 160, 60))
        composeRule.onNodeWithTag("composer_input").performSemanticsAction(SemanticsActions.PasteText)
        waitForReadyAttachments(1)
        composeRule.onNodeWithTag("send_button").performSemanticsAction(SemanticsActions.OnClick)
        waitForText("resumed=yes", substring = true, timeoutMs = 20_000L)
        composeRule.onNodeWithContentDescription("Sent image 1 of 1").assertExists()
    }

    @Test
    fun test04_interactivePtyShellTabSupportsAutocompleteAndCtrlC() {
        bootstrapOverRealSsh()

        // Create a Shell tab opting into Interactive terminal (PTY).
        val hasEmptyButton = composeRule.onAllNodes(hasTestTag("empty_new_tab_button"))
            .fetchSemanticsNodes()
            .isNotEmpty()
        if (hasEmptyButton) {
            composeRule.onNodeWithTag("empty_new_tab_button").performClick()
        } else {
            composeRule.onNodeWithTag("top_new_tab_button").performClick()
        }
        waitForText("New Persistent Agent Tab")
        composeRule.onNodeWithTag("agent_option_shell").performScrollTo().performClick()
        waitForTag("new_tab_shell_mode_pty")
        composeRule.onNodeWithTag("new_tab_shell_mode_pty").performScrollTo().performClick()
        composeRule.onNodeWithTag("new_tab_name_input").performScrollTo().performTextReplacement("Real PTY")
        closeSoftKeyboard()
        composeRule.onNodeWithTag("create_tab_button")
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.OnClick)
        waitForTagGone("create_tab_button")

        // Verify the native libghostty-vt terminal canvas and mobile extra-keys bar render.
        waitForTag("pty_terminal_canvas", timeoutMs = 20_000L)
        waitForTag("pty_control_toolbar", timeoutMs = 10_000L)
        waitForTag("pty_key_tab")
        waitForTag("pty_key_ctrl_c")
        waitForTag("pty_key_esc")
        waitForTag("pty_key_up")

        // Wait for the live bash prompt from the Docker VM to render on Ghostty's grid.
        waitForCondition("initial PTY prompt output", timeoutMs = 20_000L) {
            composeRule.onAllNodesWithTag("pty_terminal_canvas")
                .fetchSemanticsNodes(atLeastOneRootRequired = false)
                .any { node ->
                    node.config.getOrNull(SemanticsProperties.Text)
                        ?.joinToString("") { it.text }
                        ?.isNotBlank() == true
                }
        }

        // Tap the terminal canvas to focus the IME view and verify Ctrl+C & Tab toolbar keys work without crashing.
        composeRule.onNodeWithTag("pty_terminal_canvas").performClick()
        composeRule.onNodeWithTag("pty_key_tab").performClick()
        composeRule.onNodeWithTag("pty_key_ctrl_c").performClick()
        composeRule.waitForIdle()
    }
}
