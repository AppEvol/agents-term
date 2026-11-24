package com.example

import com.example.data.ConnectionConfig
import com.example.data.ConnectionSettings
import com.example.data.ConnectionState
import com.example.data.CreateTabRequest
import com.example.data.FsDirectoryEntry
import com.example.data.FsListResponse
import com.example.data.HerdrApiClient
import com.example.data.HerdrRepository
import com.example.data.ServerInfo
import com.example.data.TabMessagesResponse
import com.example.data.TabRecord
import com.example.data.WsServerEnvelope
import com.example.data.resolveInitialDirectoryListing
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.Request
import okhttp3.WebSocket
import okio.ByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HerdrRepositoryReconnectTest {

    private class InMemorySettings(
        private var connection: ConnectionConfig = ConnectionConfig(),
        private var lastTabId: String? = null,
        private val lastCwdByHost: MutableMap<String, String> = mutableMapOf(),
    ) : ConnectionSettings {
        override fun loadConnection(): ConnectionConfig = connection
        override fun saveConnection(config: ConnectionConfig) {
            connection = config
        }
        override fun loadLastTabId(): String? = lastTabId
        override fun saveLastTabId(tabId: String?) {
            lastTabId = tabId
        }
        override fun loadLastCwd(host: String): String? = lastCwdByHost[host.trim().lowercase()]
        override fun saveLastCwd(host: String, cwd: String?) {
            val key = host.trim().lowercase()
            if (cwd.isNullOrBlank()) {
                lastCwdByHost.remove(key)
            } else {
                lastCwdByHost[key] = cwd.trim()
            }
        }
    }

    private class FakeWebSocketHandle(
        val index: Int,
        private val onOpenCb: () -> Unit,
        private val onEventCb: (WsServerEnvelope) -> Unit,
        private val onClosedCb: (String?) -> Unit,
        private val fireClosedOnCancel: Boolean,
        private val cancelReason: String = "Canceled",
    ) : WebSocket {
        var cancelled = false
            private set
        var closed = false
            private set

        fun emitOpen() = onOpenCb()
        fun emitEvent(envelope: WsServerEnvelope) = onEventCb(envelope)
        fun emitClosed(reason: String?) = onClosedCb(reason)

        override fun request(): Request = Request.Builder().url("http://100.71.219.26:7501/ws").build()
        override fun queueSize(): Long = 0L
        override fun send(text: String): Boolean = !cancelled && !closed
        override fun send(bytes: ByteString): Boolean = !cancelled && !closed
        override fun close(code: Int, reason: String?): Boolean {
            closed = true
            return true
        }
        override fun cancel() {
            cancelled = true
            if (fireClosedOnCancel) {
                onClosedCb(cancelReason)
            }
        }
    }

    private class FakeApiClient(
        var fireClosedOnCancel: Boolean = true,
        var cancelReason: String = "Canceled",
        private val fsMap: MutableMap<String, FsListResponse> = mutableMapOf(),
    ) : HerdrApiClient() {
        val sockets = mutableListOf<FakeWebSocketHandle>()
        val listDirCalls = mutableListOf<String?>()

        override fun openWebSocket(
            config: ConnectionConfig,
            onOpen: () -> Unit,
            onEvent: (WsServerEnvelope) -> Unit,
            onClosed: (String?) -> Unit,
        ): WebSocket {
            val handle = FakeWebSocketHandle(
                index = sockets.size + 1,
                onOpenCb = onOpen,
                onEventCb = onEvent,
                onClosedCb = onClosed,
                fireClosedOnCancel = fireClosedOnCancel,
                cancelReason = cancelReason,
            )
            sockets.add(handle)
            return handle
        }

        override suspend fun createTab(config: ConnectionConfig, body: CreateTabRequest): TabRecord {
            val cwd = body.cwd ?: "/home/test"
            return TabRecord(
                id = "tab_created",
                title = body.title ?: "Tab",
                agent = body.agent,
                cwd = cwd,
                permissionMode = body.permissionMode,
                model = body.model,
            )
        }

        override suspend fun listDirectories(config: ConnectionConfig, path: String?): FsListResponse {
            listDirCalls.add(path)
            val resolved = path ?: "/home/test"
            return fsMap[resolved] ?: FsListResponse(
                path = resolved,
                parent = resolved.substringBeforeLast('/').ifBlank { "/" },
                directories = emptyList(),
            )
        }

        override suspend fun getTabMessages(config: ConnectionConfig, tabId: String): TabMessagesResponse {
            return TabMessagesResponse(
                tab = TabRecord(id = tabId, title = "Tab", agent = "agy", cwd = "/home/test"),
                messages = emptyList(),
            )
        }
    }

    private val testConfig = ConnectionConfig(host = "100.71.219.26", port = 7501, token = "tok_test")

    @Test
    fun supersededSocketFailureDoesNotTriggerReconnectStorm() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val api = FakeApiClient(fireClosedOnCancel = true, cancelReason = "Canceled")
        val settings = InMemorySettings(connection = testConfig)
        val repo = HerdrRepository(
            api = api,
            settings = settings,
            ioDispatcher = dispatcher,
        )

        // Socket 1 starts connecting
        repo.connect(testConfig)
        assertEquals(1, api.sockets.size)
        assertEquals(ConnectionState.CONNECTING, repo.state.value.connectionState)

        // Overlapping reconnect cancels Socket 1 (firing onClosed("Canceled")) and opens Socket 2
        repo.connect(testConfig)
        assertEquals(2, api.sockets.size)
        assertTrue(api.sockets[0].cancelled)
        assertFalse(api.sockets[1].cancelled)
        assertEquals(ConnectionState.CONNECTING, repo.state.value.connectionState)
        assertNull(repo.state.value.connectionError)

        // Socket 2 connects cleanly
        api.sockets[1].emitOpen()
        assertEquals(ConnectionState.CONNECTED, repo.state.value.connectionState)

        // Advance well past multiple 1.5s reconnect intervals — no storm may occur
        advanceTimeBy(10_000L)
        runCurrent()

        assertEquals(2, api.sockets.size)
        assertFalse(api.sockets[1].cancelled)
        assertEquals(ConnectionState.CONNECTED, repo.state.value.connectionState)
        assertNull(repo.state.value.connectionError)
    }

    @Test
    fun asyncStaleSocketCallbacksAfterReplacementAreIgnored() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val api = FakeApiClient(fireClosedOnCancel = false)
        val settings = InMemorySettings(connection = testConfig)
        val repo = HerdrRepository(
            api = api,
            settings = settings,
            ioDispatcher = dispatcher,
        )

        repo.connect(testConfig)
        val socket1 = api.sockets[0]
        socket1.emitOpen()
        assertEquals(ConnectionState.CONNECTED, repo.state.value.connectionState)

        // Replace with Socket 2 while Socket 1's async callbacks arrive afterwards
        repo.connect(testConfig)
        val socket2 = api.sockets[1]
        assertTrue(socket1.cancelled)
        socket2.emitOpen()
        assertEquals(ConnectionState.CONNECTED, repo.state.value.connectionState)

        // Stale callbacks from Socket 1 arrive out-of-order after Socket 2 is already live
        socket1.emitEvent(
            WsServerEnvelope(
                type = "snapshot",
                tabs = listOf(TabRecord(id = "stale_tab", title = "Stale", agent = "agy", cwd = "/tmp")),
                activeTabId = "stale_tab",
            )
        )
        socket1.emitClosed("Socket closed")

        advanceTimeBy(10_000L)
        runCurrent()

        assertEquals(2, api.sockets.size)
        assertFalse(socket2.cancelled)
        assertEquals(ConnectionState.CONNECTED, repo.state.value.connectionState)
        assertNull(repo.state.value.connectionError)
        assertTrue(repo.state.value.tabs.isEmpty())
    }

    @Test
    fun foregroundResumeKeepsFreshConnectingSocketButRecoversStaleAttempt() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var nowMs = 10_000L
        val api = FakeApiClient(fireClosedOnCancel = true, cancelReason = "Canceled")
        val settings = InMemorySettings(connection = testConfig)
        val repo = HerdrRepository(
            api = api,
            settings = settings,
            clockMs = { nowMs },
            ioDispatcher = dispatcher,
        )

        // Socket 1 begins connecting at t=10_000ms
        repo.connect(testConfig)
        assertEquals(1, api.sockets.size)

        // 19ms later (matching debug dump 8721ebe4), app resumes to foreground: should keep Socket 1
        nowMs += 19L
        repo.ensureConnectedOnForeground()
        assertEquals(1, api.sockets.size)
        assertFalse(api.sockets[0].cancelled)

        // 3s later, Socket 1 is still stuck CONNECTING: foreground resume should supersede it cleanly
        nowMs += 3_000L
        repo.ensureConnectedOnForeground()
        assertEquals(2, api.sockets.size)
        assertTrue(api.sockets[0].cancelled)

        api.sockets[1].emitOpen()
        advanceTimeBy(10_000L)
        runCurrent()

        assertEquals(2, api.sockets.size)
        assertEquals(ConnectionState.CONNECTED, repo.state.value.connectionState)
    }

    @Test
    fun activeSocketDropReconnectsWithBackoffAndStabilizesOnceConnected() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val api = FakeApiClient(fireClosedOnCancel = true)
        val settings = InMemorySettings(connection = testConfig)
        val repo = HerdrRepository(
            api = api,
            settings = settings,
            ioDispatcher = dispatcher,
        )

        repo.connect(testConfig)
        api.sockets[0].emitOpen()
        assertEquals(ConnectionState.CONNECTED, repo.state.value.connectionState)

        // Active socket drops -> schedules 1.5s reconnect
        api.sockets[0].emitClosed("Software caused connection abort")
        assertEquals(ConnectionState.ERROR, repo.state.value.connectionState)

        advanceTimeBy(1_499L)
        runCurrent()
        assertEquals(1, api.sockets.size)

        advanceTimeBy(1L)
        runCurrent()
        assertEquals(2, api.sockets.size)

        // Second attempt fails -> schedules 3.0s backoff
        api.sockets[1].emitClosed("timeout")
        advanceTimeBy(2_999L)
        runCurrent()
        assertEquals(2, api.sockets.size)

        advanceTimeBy(1L)
        runCurrent()
        assertEquals(3, api.sockets.size)

        // Third attempt connects and stays connected
        api.sockets[2].emitOpen()
        assertEquals(ConnectionState.CONNECTED, repo.state.value.connectionState)

        advanceTimeBy(20_000L)
        runCurrent()
        assertEquals(3, api.sockets.size)
        assertEquals(ConnectionState.CONNECTED, repo.state.value.connectionState)
    }

    @Test
    fun disconnectCancelsPendingReconnectAndIgnoresLateFailure() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val api = FakeApiClient(fireClosedOnCancel = false)
        val settings = InMemorySettings(connection = testConfig)
        val repo = HerdrRepository(
            api = api,
            settings = settings,
            ioDispatcher = dispatcher,
        )

        repo.connect(testConfig)
        val socket1 = api.sockets[0]
        socket1.emitOpen()
        socket1.emitClosed("connection lost")
        assertEquals(ConnectionState.ERROR, repo.state.value.connectionState)

        // User disconnects while reconnect timer is pending
        repo.disconnect()
        assertEquals(ConnectionState.DISCONNECTED, repo.state.value.connectionState)

        // Late failure callback from closed socket should not change state or reconnect
        socket1.emitClosed("Socket closed")
        advanceTimeBy(30_000L)
        runCurrent()

        assertEquals(1, api.sockets.size)
        assertEquals(ConnectionState.DISCONNECTED, repo.state.value.connectionState)
    }

    @Test
    fun resolveInitialDirectoryKeepsExistingEmptyFolderAndFallsBackWhenDeleted() = runTest {
        val homeListing = FsListResponse(
            path = "/home/ned",
            parent = "/home",
            directories = listOf(FsDirectoryEntry("projects", "/home/ned/projects")),
        )
        val projectsListing = FsListResponse(
            path = "/home/ned/projects",
            parent = "/home/ned",
            directories = listOf(FsDirectoryEntry("empty-repo", "/home/ned/projects/empty-repo")),
        )
        val emptyRepoListing = FsListResponse(
            path = "/home/ned/projects/empty-repo",
            parent = "/home/ned/projects",
            directories = emptyList(),
        )
        val fs = mutableMapOf(
            "/home/ned" to homeListing,
            "/home/ned/projects" to projectsListing,
            "/home/ned/projects/empty-repo" to emptyRepoListing,
        )
        suspend fun fetch(path: String?): Result<FsListResponse> {
            val key = path ?: "/home/ned"
            val listing = fs[key] ?: FsListResponse(
                path = key,
                parent = key.substringBeforeLast('/').ifBlank { "/" },
                directories = emptyList(),
            )
            return Result.success(listing)
        }

        // 1) Existing folder with subdirs stays without fallback
        val withSubdirs = resolveInitialDirectoryListing("/home/ned/projects", "/home/ned", ::fetch).getOrThrow()
        assertEquals("/home/ned/projects", withSubdirs.listing.path)
        assertFalse(withSubdirs.fellBackToHome)

        // 2) Existing empty folder (verified via parent) stays without fallback
        val emptyExisting = resolveInitialDirectoryListing("/home/ned/projects/empty-repo", "/home/ned", ::fetch).getOrThrow()
        assertEquals("/home/ned/projects/empty-repo", emptyExisting.listing.path)
        assertFalse(emptyExisting.fellBackToHome)

        // 3) Deleted folder on legacy daemon (empty listing & missing from parent) falls back to home
        val deletedLegacy = resolveInitialDirectoryListing("/home/ned/projects/deleted-repo", "/home/ned", ::fetch).getOrThrow()
        assertEquals("/home/ned", deletedLegacy.listing.path)
        assertTrue(deletedLegacy.fellBackToHome)

        // 4) Daemon returning explicit exists=false falls back to home
        val deletedExplicit = resolveInitialDirectoryListing("/home/ned/.hidden-gone", "/home/ned") { p ->
            if (p == "/home/ned/.hidden-gone") {
                Result.success(FsListResponse(path = p, parent = "/home/ned", directories = emptyList(), exists = false))
            } else {
                Result.success(homeListing)
            }
        }.getOrThrow()
        assertEquals("/home/ned", deletedExplicit.listing.path)
        assertTrue(deletedExplicit.fellBackToHome)

        // 5) Daemon returning HTTP 404/error for missing dir falls back to home
        val deletedError = resolveInitialDirectoryListing("/home/ned/projects/missing", "/home/ned") { p ->
            if (p == "/home/ned/projects/missing") {
                Result.failure(IOException("HTTP 404: Not Found"))
            } else {
                Result.success(homeListing)
            }
        }.getOrThrow()
        assertEquals("/home/ned", deletedError.listing.path)
        assertTrue(deletedError.fellBackToHome)
    }

    @Test
    fun repositoryPersistsLastCwdOnCreateAndResetsToHomeWhenFolderDeleted() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val homeListing = FsListResponse(
            path = "/home/test",
            parent = "/home",
            directories = listOf(FsDirectoryEntry("projects", "/home/test/projects")),
        )
        val projectsListing = FsListResponse(
            path = "/home/test/projects",
            parent = "/home/test",
            directories = listOf(FsDirectoryEntry("worktree-a", "/home/test/projects/worktree-a")),
        )
        val worktreeListing = FsListResponse(
            path = "/home/test/projects/worktree-a",
            parent = "/home/test/projects",
            directories = emptyList(),
        )
        val fsMap = mutableMapOf(
            "/home/test" to homeListing,
            "/home/test/projects" to projectsListing,
            "/home/test/projects/worktree-a" to worktreeListing,
        )
        val api = FakeApiClient(fsMap = fsMap)
        val settings = InMemorySettings(connection = testConfig)
        val repo = HerdrRepository(api = api, settings = settings, ioDispatcher = dispatcher)

        repo.connect(testConfig)
        api.sockets[0].emitOpen()
        api.sockets[0].emitEvent(
            WsServerEnvelope(
                type = "snapshot",
                server = ServerInfo(version = "0.1.0", hostname = "vm", defaultCwd = "/home/test"),
                tabs = emptyList(),
            )
        )

        // Creating a tab persists lastCwd in both WorkspaceState and ConnectionSettings
        val created = repo.createTab(CreateTabRequest(agent = "agy", cwd = "/home/test/projects/worktree-a"))
        assertTrue(created.isSuccess)
        assertEquals("/home/test/projects/worktree-a", repo.state.value.lastCwd)
        assertEquals("/home/test/projects/worktree-a", settings.loadLastCwd(testConfig.host))

        // New repository instance (simulating app restart) restores lastCwd from settings
        val restartedRepo = HerdrRepository(api = api, settings = settings, ioDispatcher = dispatcher)
        assertEquals("/home/test/projects/worktree-a", restartedRepo.state.value.lastCwd)

        // While worktree-a still exists, loadInitialDirectories keeps it
        val initialExisting = repo.loadInitialDirectories(repo.state.value.lastCwd).getOrThrow()
        assertEquals("/home/test/projects/worktree-a", initialExisting.path)
        assertEquals("/home/test/projects/worktree-a", repo.state.value.lastCwd)

        // Delete worktree-a on the VM -> loadInitialDirectories falls back to home and updates lastCwd
        fsMap.remove("/home/test/projects/worktree-a")
        fsMap["/home/test/projects"] = projectsListing.copy(directories = emptyList())

        val afterDelete = repo.loadInitialDirectories(repo.state.value.lastCwd).getOrThrow()
        assertEquals("/home/test", afterDelete.path)
        assertEquals("/home/test", repo.state.value.lastCwd)
        assertEquals("/home/test", settings.loadLastCwd(testConfig.host))
    }
}
