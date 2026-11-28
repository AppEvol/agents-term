package com.appevol.agentsterm

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.appevol.agentsterm.data.AgentsTermApiClient
import com.appevol.agentsterm.data.AgentsTermRepository
import com.appevol.agentsterm.data.ChatMessage
import com.appevol.agentsterm.data.ClosedTabsResponse
import com.appevol.agentsterm.data.ConnectionConfig
import com.appevol.agentsterm.data.ConnectionSettings
import com.appevol.agentsterm.data.ConnectionState
import com.appevol.agentsterm.data.DeleteTabResponse
import com.appevol.agentsterm.data.MAX_OPEN_TABS
import com.appevol.agentsterm.data.ReviveTabResponse
import com.appevol.agentsterm.data.TabRecord
import com.appevol.agentsterm.data.WorkspaceState
import com.appevol.agentsterm.data.WsServerEnvelope
import com.appevol.agentsterm.data.formatRelativeClosedTime
import com.appevol.agentsterm.data.reduceWorkspaceEvent
import com.appevol.agentsterm.ui.RecentlyClosedTabsContent
import com.appevol.agentsterm.ui.TabSidebarBody
import com.appevol.agentsterm.ui.theme.AgentsTermTheme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.Request
import okhttp3.WebSocket
import okio.ByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h915dp")
class RecentlyClosedTabsTest {

    @get:Rule
    val composeRule = createComposeRule()

    private class InMemorySettings(
        private var connection: ConnectionConfig = ConnectionConfig(host = "100.71.219.26", port = 7501),
        private var lastTabId: String? = null,
    ) : ConnectionSettings {
        override fun loadConnection(): ConnectionConfig = connection
        override fun saveConnection(config: ConnectionConfig) {
            connection = config
        }

        override fun loadLastTabId(): String? = lastTabId
        override fun saveLastTabId(tabId: String?) {
            lastTabId = tabId
        }

        override fun loadLastCwd(host: String): String? = null
        override fun saveLastCwd(host: String, cwd: String?) = Unit
    }

    private class FakeClosedTabsApi : AgentsTermApiClient() {
        val openTabs = mutableListOf<TabRecord>()
        val closedTabs = mutableListOf<TabRecord>()
        val messagesByTab = mutableMapOf<String, List<ChatMessage>>()
        var reviveCalls = 0
        var lastOnEvent: ((WsServerEnvelope) -> Unit)? = null

        override fun openWebSocket(
            config: ConnectionConfig,
            onOpen: () -> Unit,
            onEvent: (WsServerEnvelope) -> Unit,
            onClosed: (String?, Int?) -> Unit,
        ): WebSocket {
            lastOnEvent = onEvent
            onOpen()
            return object : WebSocket {
                override fun request(): Request = Request.Builder().url("http://100.71.219.26:7501/ws").build()
                override fun queueSize(): Long = 0L
                override fun send(text: String): Boolean = true
                override fun send(bytes: ByteString): Boolean = true
                override fun close(code: Int, reason: String?): Boolean = true
                override fun cancel() = Unit
            }
        }

        override suspend fun deleteTab(config: ConnectionConfig, tabId: String): DeleteTabResponse {
            val removed = openTabs.firstOrNull { it.id == tabId }
            openTabs.removeAll { it.id == tabId }
            val archived = removed?.copy(closedAt = 1_700_000_000_000L)
            if (archived != null) {
                closedTabs.removeAll { it.id == tabId }
                closedTabs.add(0, archived)
            }
            return DeleteTabResponse(
                ok = true,
                closedTab = archived,
                closedTabs = closedTabs.toList(),
            )
        }

        override suspend fun listClosedTabs(config: ConnectionConfig): ClosedTabsResponse = ClosedTabsResponse(
            closedTabs = closedTabs.toList(),
        )

        override suspend fun reviveClosedTab(config: ConnectionConfig, tabId: String): ReviveTabResponse {
            reviveCalls++
            val target = closedTabs.first { it.id == tabId }
            closedTabs.removeAll { it.id == tabId }
            val restored = target.copy(closedAt = null)
            openTabs.add(restored)
            return ReviveTabResponse(
                tab = restored,
                messages = messagesByTab[tabId].orEmpty(),
                closedTabs = closedTabs.toList(),
            )
        }
    }

    private val sampleClosedA = TabRecord(
        id = "tab_closed_a",
        title = "Claude · backend",
        agent = "claude",
        cwd = "/home/ned/projects/backend",
        model = "sonnet",
        taskSummary = "Investigate database deadlock",
        closedAt = 1_700_000_000_000L - 5 * 60_000L,
    )

    private val sampleClosedB = TabRecord(
        id = "tab_closed_b",
        title = "Antigravity · agents-term",
        agent = "agy",
        cwd = "/home/ned/projects/agents-term",
        taskSummary = "Add recently closed tab revival",
        closedAt = 1_700_000_000_000L - 3 * 3_600_000L,
    )

    @Test
    fun formatRelativeClosedTimeFormatsExpectedRanges() {
        val now = 1_700_000_000_000L
        assertEquals("recently", formatRelativeClosedTime(null, now))
        assertEquals("recently", formatRelativeClosedTime(0L, now))
        assertEquals("just now", formatRelativeClosedTime(now - 25_000L, now))
        assertEquals("5m ago", formatRelativeClosedTime(now - 5 * 60_000L, now))
        assertEquals("4h ago", formatRelativeClosedTime(now - 4 * 3_600_000L, now))
        assertEquals("1d ago", formatRelativeClosedTime(now - 24 * 3_600_000L, now))
    }

    @Test
    fun reduceWorkspaceEventSyncsClosedTabsOnSnapshotDeleteAndRevive() {
        val openTab = TabRecord(
            id = "tab_open",
            title = "Shell · home",
            agent = "shell",
            cwd = "/home/ned",
        )
        var state = reduceWorkspaceEvent(
            WorkspaceState(),
            WsServerEnvelope(
                type = "snapshot",
                tabs = listOf(openTab),
                closedTabs = listOf(sampleClosedA),
                activeTabId = openTab.id,
            ),
        )
        assertEquals(listOf(sampleClosedA), state.closedTabs)

        val archivedOpen = openTab.copy(closedAt = 1_700_000_000_000L)
        state = reduceWorkspaceEvent(
            state,
            WsServerEnvelope(
                type = "tab_deleted",
                tabId = openTab.id,
                closedTab = archivedOpen,
                closedTabs = listOf(archivedOpen, sampleClosedA),
            ),
        )
        assertTrue(state.tabs.isEmpty())
        assertEquals(listOf(archivedOpen, sampleClosedA), state.closedTabs)

        val revivedA = sampleClosedA.copy(closedAt = null)
        state = reduceWorkspaceEvent(
            state,
            WsServerEnvelope(
                type = "tab_created",
                tab = revivedA,
                closedTabs = listOf(archivedOpen),
            ),
        )
        assertEquals(listOf(revivedA), state.tabs)
        assertEquals(listOf(archivedOpen), state.closedTabs)
        assertEquals(revivedA.id, state.selectedTabId)
    }

    @Test
    fun repositoryDeleteAndReviveClosedTabRestoresHistoryAndRespectsOpenLimit() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val api = FakeClosedTabsApi()
        val settings = InMemorySettings()
        val tab = TabRecord(
            id = "tab_1",
            title = "Claude · app",
            agent = "claude",
            cwd = "/home/ned/app",
            taskSummary = "Refactor parser",
        )
        val history = listOf(
            ChatMessage(id = "m1", tabId = tab.id, role = "user", text = "Refactor parser"),
            ChatMessage(id = "m2", tabId = tab.id, role = "assistant", text = "Done!"),
        )
        api.openTabs.add(tab)
        api.messagesByTab[tab.id] = history

        val repo = AgentsTermRepository(api = api, settings = settings, ioDispatcher = dispatcher)
        val delRes = repo.deleteTab(tab.id)
        assertTrue(delRes.isSuccess)
        assertEquals(tab.id, delRes.getOrNull()?.id)
        assertEquals(1, repo.state.value.closedTabs.size)

        val revRes = repo.reviveClosedTab(tab.id)
        assertTrue(revRes.isSuccess)
        assertEquals(tab.id, repo.state.value.selectedTabId)
        assertEquals(history, repo.state.value.messagesByTab[tab.id])
        assertTrue(repo.state.value.closedTabs.isEmpty())

        // Verify 10-tab open limit blocks reviving without dropping the closed tab.
        val limitApi = FakeClosedTabsApi()
        val tenOpen = (1..MAX_OPEN_TABS).map { i ->
            TabRecord(id = "open_$i", title = "Open $i", agent = "shell", cwd = "/home/ned")
        }
        limitApi.openTabs.addAll(tenOpen)
        limitApi.closedTabs.add(sampleClosedA)
        val limitRepo = AgentsTermRepository(api = limitApi, settings = settings, ioDispatcher = dispatcher)
        limitRepo.connect()
        limitApi.lastOnEvent?.invoke(
            WsServerEnvelope(
                type = "snapshot",
                tabs = tenOpen,
                closedTabs = listOf(sampleClosedA),
                activeTabId = "open_1",
            ),
        )
        assertFalse(limitRepo.state.value.canCreateTab)
        val blocked = limitRepo.reviveClosedTab(sampleClosedA.id)
        assertTrue(blocked.isFailure)
        assertEquals(0, limitApi.reviveCalls)
        assertEquals(listOf(sampleClosedA), limitRepo.state.value.closedTabs)
    }

    @Test
    fun recentlyClosedTabsUiShowsEmptyState() {
        composeRule.setContent {
            AgentsTermTheme {
                RecentlyClosedTabsContent(
                    closedTabs = emptyList(),
                    canReviveTab = true,
                    isRefreshing = false,
                    revivingTabId = null,
                    onRefresh = {},
                    onRevive = {},
                )
            }
        }

        composeRule.onNodeWithTag("recently_closed_empty").assertIsDisplayed()
    }

    @Test
    fun recentlyClosedTabsUiShowsEmptyListFilterAndReviveAction() {
        var revivedId: String? = null
        val sixClosed = (1..6).map { i ->
            TabRecord(
                id = "closed_$i",
                title = if (i == 4) "Special Fix Tab" else "Closed Tab $i",
                agent = "agy",
                cwd = "/home/ned/repo",
                taskSummary = "Task $i",
                closedAt = 1_700_000_000_000L - i * 60_000L,
            )
        }
        composeRule.setContent {
            AgentsTermTheme {
                RecentlyClosedTabsContent(
                    closedTabs = sixClosed,
                    canReviveTab = true,
                    isRefreshing = false,
                    revivingTabId = null,
                    onRefresh = {},
                    onRevive = { revivedId = it },
                    nowMs = 1_700_000_000_000L,
                )
            }
        }

        composeRule.onNodeWithTag("recently_closed_search_input").assertIsDisplayed()
        composeRule.onNodeWithTag("closed_tab_closed_1").assertIsDisplayed()
        composeRule.onNodeWithTag("recently_closed_search_input").performTextInput("Special")
        composeRule.onNodeWithTag("closed_tab_closed_4").assertIsDisplayed()
        composeRule.onNodeWithTag("revive_tab_closed_4").assertIsEnabled().performClick()
        assertEquals("closed_4", revivedId)
    }

    @Test
    fun recentlyClosedTabsUiDisablesReviveWhenOpenTabLimitReached() {
        var revived = false
        composeRule.setContent {
            AgentsTermTheme {
                RecentlyClosedTabsContent(
                    closedTabs = listOf(sampleClosedA, sampleClosedB),
                    canReviveTab = false,
                    isRefreshing = false,
                    revivingTabId = null,
                    onRefresh = {},
                    onRevive = { revived = true },
                    nowMs = 1_700_000_000_000L,
                )
            }
        }

        composeRule.onNodeWithTag("recently_closed_limit_banner").assertIsDisplayed()
        composeRule.onNodeWithTag("revive_tab_tab_closed_a").assertIsNotEnabled()
        composeRule.onNodeWithTag("revive_tab_tab_closed_a").performClick()
        assertEquals(false, revived)
    }

    @Test
    fun drawerDisplaysRecentlyClosedCountAndTriggersCallback() {
        var opened = false
        composeRule.setContent {
            AgentsTermTheme {
                TabSidebarBody(
                    state = WorkspaceState(
                        config = ConnectionConfig(host = "100.71.219.26", port = 7501),
                        connectionState = ConnectionState.CONNECTED,
                        tabs = emptyList(),
                        closedTabs = listOf(sampleClosedA, sampleClosedB),
                    ),
                    onSelectTab = {},
                    onNewTab = {},
                    onDeleteTab = {},
                    onOpenRecentlyClosed = { opened = true },
                    onOpenSettings = {},
                    onReconnect = {},
                )
            }
        }

        composeRule.onNodeWithTag("drawer_recently_closed_button").assertIsDisplayed()
        composeRule.onNodeWithTag("drawer_recently_closed_count", useUnmergedTree = true)
            .assertIsDisplayed()
            .assertTextEquals("2")
        composeRule.onNodeWithTag("drawer_recently_closed_button").performClick()
        assertTrue(opened)
    }
}
