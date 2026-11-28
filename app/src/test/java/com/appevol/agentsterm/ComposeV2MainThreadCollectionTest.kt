package com.appevol.agentsterm

import android.os.Looper
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ComposeV2MainThreadCollectionTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun stateFlowEmittedFromIoAndRawBackgroundThreadCollectsOnMainThread() {
        val flow = MutableStateFlow("initial")
        val compositionChecks = CopyOnWriteArrayList<Pair<String, Boolean>>()
        val sideEffectChecks = CopyOnWriteArrayList<Pair<String, Boolean>>()
        val launchedEffectChecks = CopyOnWriteArrayList<Pair<String, Boolean>>()

        composeRule.setContent {
            val value by flow.collectAsStateWithLifecycle()
            compositionChecks += value to Looper.getMainLooper().isCurrentThread
            SideEffect {
                sideEffectChecks += value to Looper.getMainLooper().isCurrentThread
            }
            LaunchedEffect(value) {
                launchedEffectChecks += value to Looper.getMainLooper().isCurrentThread
            }
            Text(text = value, modifier = Modifier.testTag("collected_value"))
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("collected_value").assertTextEquals("initial")

        val ioEmittedOffMain = AtomicBoolean(false)
        runBlocking(Dispatchers.IO) {
            ioEmittedOffMain.set(!Looper.getMainLooper().isCurrentThread)
            flow.value = "from-io"
        }
        assertTrue("Expected Dispatchers.IO emitter to run off main thread", ioEmittedOffMain.get())
        waitForText("from-io")
        composeRule.onNodeWithTag("collected_value").assertTextEquals("from-io")

        val rawEmittedOffMain = AtomicBoolean(false)
        val rawThread = Thread(
            {
                rawEmittedOffMain.set(!Looper.getMainLooper().isCurrentThread)
                flow.value = "from-raw-thread"
            },
            "test-bg-stateflow-emitter",
        ).apply { start() }
        rawThread.join(WAIT_TIMEOUT_MS)
        assertTrue("Expected raw background Thread emitter to run off main thread", rawEmittedOffMain.get())
        waitForText("from-raw-thread")
        composeRule.onNodeWithTag("collected_value").assertTextEquals("from-raw-thread")

        val expectedValues = listOf("initial", "from-io", "from-raw-thread")
        assertAllObservedOnMainThread("composition", compositionChecks, expectedValues)
        assertAllObservedOnMainThread("SideEffect", sideEffectChecks, expectedValues)
        assertAllObservedOnMainThread("LaunchedEffect", launchedEffectChecks, expectedValues)
    }

    private fun assertAllObservedOnMainThread(
        label: String,
        checks: List<Pair<String, Boolean>>,
        expectedValues: List<String>,
    ) {
        for (expected in expectedValues) {
            assertTrue(
                "Expected $label to observe '$expected', saw $checks",
                checks.any { it.first == expected },
            )
        }
        assertTrue(
            "All $label calls must run on main thread, saw $checks",
            checks.all { it.second },
        )
    }

    @Test
    fun produceStateResumingFromIoUpdatesComposeStateOnMainThread() {
        val ioRanOffMain = AtomicBoolean(false)
        val produceStateWriteOnMain = AtomicBoolean(false)
        val launchedEffectChecks = CopyOnWriteArrayList<Pair<String, Boolean>>()

        composeRule.setContent {
            val loaded by produceState(initialValue = "loading") {
                val result = withContext(Dispatchers.IO) {
                    ioRanOffMain.set(!Looper.getMainLooper().isCurrentThread)
                    "loaded-from-io"
                }
                produceStateWriteOnMain.set(Looper.getMainLooper().isCurrentThread)
                value = result
            }
            LaunchedEffect(loaded) {
                launchedEffectChecks += loaded to Looper.getMainLooper().isCurrentThread
            }
            Text(text = loaded, modifier = Modifier.testTag("produced_value"))
        }

        waitForText("loaded-from-io")
        composeRule.onNodeWithTag("produced_value").assertTextEquals("loaded-from-io")

        assertTrue("Expected withContext(Dispatchers.IO) work to run off main thread", ioRanOffMain.get())
        assertTrue("Expected produceState state write to run on main thread", produceStateWriteOnMain.get())
        assertTrue(
            "Expected LaunchedEffect to observe 'loaded-from-io', saw $launchedEffectChecks",
            launchedEffectChecks.any { it.first == "loaded-from-io" },
        )
        assertTrue(
            "All LaunchedEffects must run on main thread, saw $launchedEffectChecks",
            launchedEffectChecks.all { it.second },
        )
    }

    private fun waitForText(text: String) {
        composeRule.waitUntil(timeoutMillis = WAIT_TIMEOUT_MS) {
            composeRule.onAllNodesWithText(text)
                .fetchSemanticsNodes(atLeastOneRootRequired = false)
                .isNotEmpty()
        }
        composeRule.waitForIdle()
    }

    companion object {
        private const val WAIT_TIMEOUT_MS = 5_000L
    }
}
