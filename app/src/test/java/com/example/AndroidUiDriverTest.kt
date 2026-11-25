package com.example

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.data.AndroidUiDriver
import com.example.data.AppStateCommandException
import com.example.data.AppUiController
import com.example.data.UiNode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.coroutines.EmptyCoroutineContext

/** Drives a real Compose screen through [AndroidUiDriver]: semantics in, touch and key events out. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class AndroidUiDriverTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private var clicks by mutableIntStateOf(0)
    private var switchOn by mutableStateOf(false)
    private var message by mutableStateOf("")
    private var secret by mutableStateOf("hunter2")
    private var sent by mutableStateOf<String?>(null)
    private var menuOpen by mutableStateOf(false)
    private var menuPicked by mutableStateOf(false)
    private var dialogOpen by mutableStateOf(false)
    private var backPresses by mutableIntStateOf(0)
    private var firstVisibleRow = 0

    // The test runs on the main thread, so the driver stays on it instead of hopping to Dispatchers.Main.
    private val driver = AndroidUiDriver(activity = { composeRule.activity }, uiContext = EmptyCoroutineContext)
    private val controller = AppUiController(driver)

    private fun setContent() {
        composeRule.setContent {
            val listState = rememberLazyListState()
            firstVisibleRow = listState.firstVisibleItemIndex
            BackHandler { backPresses++ }
            Column(Modifier.fillMaxSize()) {
                Button(onClick = { clicks++ }, modifier = Modifier.testTag("count_button")) { Text("Clicked $clicks") }
                Switch(checked = switchOn, onCheckedChange = { switchOn = it }, modifier = Modifier.testTag("a_switch"))
                TextField(
                    value = message,
                    onValueChange = { message = it },
                    label = { Text("Message") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { sent = message }),
                    modifier = Modifier.testTag("message_input"),
                )
                TextField(
                    value = secret,
                    onValueChange = { secret = it },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.testTag("secret_input"),
                )
                Button(onClick = { menuOpen = true }, modifier = Modifier.testTag("menu_button")) { Text("Menu") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Menu item") },
                        onClick = {
                            menuPicked = true
                            menuOpen = false
                        },
                        modifier = Modifier.testTag("menu_item"),
                    )
                }
                Button(onClick = { dialogOpen = true }, modifier = Modifier.testTag("dialog_button")) { Text("Dialog") }
                LazyColumn(state = listState, modifier = Modifier.height(300.dp).testTag("rows")) {
                    items(100) { Text("Row $it", Modifier.height(40.dp)) }
                }
            }
            if (dialogOpen) {
                AlertDialog(
                    onDismissRequest = { dialogOpen = false },
                    confirmButton = {
                        Button(onClick = { dialogOpen = false }, modifier = Modifier.testTag("dialog_ok")) { Text("OK") }
                    },
                    text = { Text("Are you sure?") },
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun run(command: String, args: Map<String, Any?> = emptyMap()): Map<String, Any?> {
        val result = runBlocking { controller.handle(command, args) }
        composeRule.waitForIdle()
        return result
    }

    private fun nodes(): List<UiNode> = runBlocking { driver.snapshot() }.nodes

    private fun node(tag: String): UiNode = nodes().single { it.tag == tag }

    @Test
    fun snapshotDescribesTheScreenInScreenshotPixels() {
        setContent()
        val snap = runBlocking { driver.snapshot() }
        val decor = composeRule.activity.window.decorView
        assertTrue("window should be wider than a screenshot: ${decor.width}", decor.width > 1080)
        assertEquals(1080, snap.width)
        assertEquals((decor.height * 1080f / decor.width).toInt(), snap.height)
        assertEquals(listOf("activity"), snap.windows.map { it.type })

        val button = snap.nodes.single { it.tag == "count_button" }
        assertEquals("Clicked 0", button.text)
        assertTrue(button.clickable)
        assertTrue(button.right <= 1080 && button.bottom <= snap.height && button.left >= 0 && button.right > button.left)

        assertEquals("off", snap.nodes.single { it.tag == "a_switch" }.toggle)
        val input = snap.nodes.single { it.tag == "message_input" }
        assertTrue(input.editable)
        assertEquals("Message", input.text)
        val secretField = snap.nodes.single { it.tag == "secret_input" }
        assertTrue(secretField.password)
        assertNull("password content must not leave the app", secretField.value)
        assertFalse(snap.nodes.any { it.text?.contains("hunter2") == true || it.value?.contains("hunter2") == true })
        assertTrue(snap.nodes.single { it.tag == "rows" }.scrollable)
        assertNotNull(snap.nodes.firstOrNull { it.text == "Row 0" })
    }

    @Test
    fun tapClicksByTagTextAndCoordinates() {
        setContent()
        run("tap", mapOf("tag" to "count_button"))
        assertEquals(1, clicks)
        run("tap", mapOf("text" to "Clicked 1", "exact" to true))
        assertEquals(2, clicks)
        val button = node("count_button")
        run("tap", mapOf("x" to button.centerX, "y" to button.centerY))
        assertEquals(3, clicks)
        assertEquals("Clicked 3", node("count_button").text)

        run("tap", mapOf("tag" to "a_switch"))
        assertTrue(switchOn)
        assertEquals("on", node("a_switch").toggle)
    }

    @Test
    fun typeEntersTextAndSubmits() {
        setContent()
        run("type", mapOf("text" to "hello", "tag" to "message_input"))
        assertEquals("hello", message)
        assertEquals("hello", node("message_input").value)
        assertTrue("typing focuses the field", node("message_input").focused)
        // The focused field is the default target; text is inserted at the cursor.
        run("type", mapOf("text" to " world"))
        assertEquals("hello world", message)
        run("type", mapOf("text" to "bye", "replace" to true, "submit" to true))
        assertEquals("bye", message)
        assertEquals("bye", sent)
        message = "again"
        composeRule.waitForIdle()
        run("key", mapOf("key" to "enter"))
        assertEquals("again", sent)
    }

    @Test
    fun popupMenusAreSeparateWindowsThatCanBeTappedAndDismissed() {
        setContent()
        run("tap", mapOf("tag" to "menu_button"))
        assertTrue(menuOpen)
        val snap = runBlocking { driver.snapshot() }
        assertEquals(listOf("activity", "popup"), snap.windows.map { it.type })
        val item = snap.nodes.single { it.tag == "menu_item" }
        assertEquals(1, item.window)
        assertEquals("nodes of the top window come first", 1, snap.nodes.first().window)

        val tapped = run("tap", mapOf("tag" to "menu_item"))
        assertEquals("popup", (tapped["window"] as Map<*, *>)["type"])
        assertTrue(menuPicked)
        assertFalse(menuOpen)

        // Tapping outside of an open menu closes it without clicking what is underneath.
        run("tap", mapOf("tag" to "menu_button"))
        assertTrue(menuOpen)
        val count = node("count_button")
        run("tap", mapOf("x" to count.centerX, "y" to count.centerY))
        assertFalse(menuOpen)
        assertEquals(0, clicks)

        // Back closes it too.
        run("tap", mapOf("tag" to "menu_button"))
        assertTrue(menuOpen)
        assertEquals("handled", run("key", mapOf("key" to "back"))["outcome"])
        assertFalse(menuOpen)
        assertEquals(0, backPresses)
    }

    @Test
    fun dialogsAreWindowsTooAndBackGoesToTheFrontMostOne() {
        setContent()
        run("tap", mapOf("tag" to "dialog_button"))
        assertTrue(dialogOpen)
        assertEquals(listOf("activity", "dialog"), runBlocking { driver.snapshot() }.windows.map { it.type })
        assertNotNull(nodes().firstOrNull { it.text == "Are you sure?" })
        run("tap", mapOf("tag" to "dialog_ok"))
        assertFalse(dialogOpen)

        run("tap", mapOf("tag" to "dialog_button"))
        run("key", mapOf("key" to "back"))
        assertFalse("Back should close the dialog", dialogOpen)
        assertEquals(0, backPresses)

        // With nothing on top, Back reaches the screen's own handler.
        run("key", mapOf("key" to "back"))
        assertEquals(1, backPresses)
    }

    @Test
    fun scrollDragsTheList() {
        setContent()
        assertEquals(0, firstVisibleRow)
        val result = run("scroll", mapOf("direction" to "down"))
        assertEquals("rows", (result["node"] as Map<*, *>)["tag"])
        assertTrue("list should have scrolled, first visible row = $firstVisibleRow", firstVisibleRow > 0)
        val after = firstVisibleRow
        run("scroll", mapOf("direction" to "up", "tag" to "rows"))
        assertTrue(firstVisibleRow < after)
    }

    @Test
    fun aBackgroundedAppIsReportedNotDriven() {
        val noActivity = AppUiController(AndroidUiDriver(activity = { null }, uiContext = EmptyCoroutineContext))
        val error = try {
            runBlocking { noActivity.handle("tap", mapOf("x" to 1, "y" to 1)) }
            null
        } catch (e: AppStateCommandException) {
            e.message
        }
        assertTrue(error.orEmpty().contains("background"))
    }
}
