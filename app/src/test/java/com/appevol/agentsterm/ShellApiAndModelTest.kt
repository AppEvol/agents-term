package com.appevol.agentsterm

import com.appevol.agentsterm.data.ChatMessage
import com.appevol.agentsterm.data.ConnectionConfig
import com.appevol.agentsterm.data.ConnectionSettings
import com.appevol.agentsterm.data.CreateTabRequest
import com.appevol.agentsterm.data.HerdrApiClient
import com.appevol.agentsterm.data.HerdrRepository
import com.appevol.agentsterm.data.HttpStatusException
import com.appevol.agentsterm.data.SendStdinRequest
import com.appevol.agentsterm.data.decodeHerdrJson
import com.appevol.agentsterm.data.encodeHerdrJson
import com.appevol.agentsterm.ui.NewTabController
import com.appevol.agentsterm.ui.shouldShowAssistantErrorBanner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Wire format, HTTP API, and repository tests for Shell exit codes, interactive stdin, and createTab errors. */
class ShellApiAndModelTest {

    private val server = MockWebServer()
    private val api = HerdrApiClient()
    private lateinit var config: ConnectionConfig

    @Before
    fun setUp() {
        server.start()
        config = ConnectionConfig(host = server.hostName, port = server.port, token = "secret-token")
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun chatMessageDeserializesExitCodeZeroNonZeroAndAbsent() {
        val okMsg = decodeHerdrJson<ChatMessage>(
            """{"id":"m_ok","tabId":"t_sh","role":"assistant","text":"","status":"complete","exitCode":0}""",
        )
        assertEquals(0, okMsg.exitCode)
        assertEquals(okMsg, decodeHerdrJson<ChatMessage>(encodeHerdrJson(okMsg)))

        val errMsg = decodeHerdrJson<ChatMessage>(
            """{"id":"m_err","tabId":"t_sh","role":"assistant","text":"","status":"error","error":"Exited with code 1","exitCode":1}""",
        )
        assertEquals(1, errMsg.exitCode)
        assertEquals("Exited with code 1", errMsg.error)

        val legacyMsg = decodeHerdrJson<ChatMessage>(
            """{"id":"m_legacy","tabId":"t_sh","role":"assistant","text":"hello","status":"complete"}""",
        )
        assertNull(legacyMsg.exitCode)
    }

    @Test
    fun sendStdinPostsDataAndEofPayloadsAndSurfacesErrors() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"ok":true}"""))
        server.enqueue(MockResponse().setBody("""{"ok":true}"""))
        server.enqueue(
            MockResponse()
                .setResponseCode(409)
                .setBody("""{"error":"No running shell command accepts stdin"}"""),
        )

        api.sendStdin(config, "tab_sh", data = "hello\n", eof = false)
        api.sendStdin(config, "tab_sh", data = null, eof = true)

        val dataReq = server.takeRequest()
        assertEquals("POST", dataReq.method)
        assertEquals("/api/tabs/tab_sh/stdin", dataReq.path)
        assertEquals("Bearer secret-token", dataReq.getHeader("Authorization"))
        assertEquals(
            SendStdinRequest(data = "hello\n", eof = false),
            decodeHerdrJson<SendStdinRequest>(dataReq.body.readUtf8()),
        )

        val eofReq = server.takeRequest()
        assertEquals("POST", eofReq.method)
        assertEquals("/api/tabs/tab_sh/stdin", eofReq.path)
        assertEquals(
            SendStdinRequest(data = null, eof = true),
            decodeHerdrJson<SendStdinRequest>(eofReq.body.readUtf8()),
        )

        val err = assertThrows(HttpStatusException::class.java) {
            runBlocking { api.sendStdin(config, "tab_sh", data = "late\n", eof = false) }
        }
        assertEquals(409, err.statusCode)
        assertEquals("No running shell command accepts stdin", err.message)
    }

    @Test
    fun repositorySurfacesCreateTabAndSendStdinErrors() = runBlocking {
        val repo = HerdrRepository(
            api = api,
            settings = InMemoryConnectionSettings(config),
        )

        server.enqueue(
            MockResponse()
                .setResponseCode(400)
                .setBody("""{"error":"Working directory does not exist: /bad/path"}"""),
        )
        val createRes = repo.createTab(CreateTabRequest(agent = "shell", cwd = "/bad/path"))
        assertTrue(createRes.isFailure)
        assertEquals("Working directory does not exist: /bad/path", createRes.exceptionOrNull()?.message)
        assertEquals("Working directory does not exist: /bad/path", repo.state.value.bannerMessage)
        assertTrue(repo.state.value.tabs.isEmpty())

        repo.clearBanner()
        server.enqueue(
            MockResponse()
                .setResponseCode(409)
                .setBody("""{"error":"No running shell command accepts stdin"}"""),
        )
        val stdinRes = repo.sendStdin("tab_sh", data = "yes\n", eof = false)
        assertTrue(stdinRes.isFailure)
        assertEquals("No running shell command accepts stdin", repo.state.value.bannerMessage)
    }

    @Test
    fun newTabControllerKeepsSheetOpenAndSurfacesErrorUntilClearedOrSucceeded() = runBlocking {
        val repo = HerdrRepository(
            api = api,
            settings = InMemoryConnectionSettings(config),
        )
        val controller = NewTabController(repo = repo, scope = this)
        var createdCount = 0

        server.enqueue(
            MockResponse()
                .setResponseCode(400)
                .setBody("""{"error":"Working directory does not exist: /missing"}"""),
        )
        controller.createTab(
            agent = "shell",
            cwd = "/missing",
            permissionMode = "full-access",
            title = null,
            onCreated = { createdCount++ },
        )
        val surfacedError = withTimeout(5_000L) {
            controller.error.first { it != null }
        }
        withTimeout(5_000L) {
            controller.isCreating.first { !it }
        }
        assertEquals(0, createdCount)
        assertEquals("Working directory does not exist: /missing", surfacedError)

        controller.clearError()
        assertNull(controller.error.value)

        server.enqueue(
            MockResponse().setBody(
                """{"tab":{"id":"tab_ok","title":"Shell","agent":"shell","cwd":"/home/ned","status":"idle"}}""",
            ),
        )
        val created = CompletableDeferred<Unit>()
        controller.createTab(
            agent = "shell",
            cwd = "/home/ned",
            permissionMode = "full-access",
            title = null,
            onCreated = {
                createdCount++
                created.complete(Unit)
            },
        )
        withTimeout(5_000L) {
            created.await()
            controller.isCreating.first { !it }
        }
        assertEquals(1, createdCount)
        assertNull(controller.error.value)
    }

    @Test
    fun errorBannerDeduplicationHidesOnlyRedundantExitCodeMessage() {
        assertFalse(
            shouldShowAssistantErrorBanner(
                ChatMessage(
                    id = "m1",
                    tabId = "t1",
                    role = "assistant",
                    status = "error",
                    error = "Exited with code 1",
                    exitCode = 1,
                ),
            ),
        )
        assertFalse(
            shouldShowAssistantErrorBanner(
                ChatMessage(
                    id = "m2",
                    tabId = "t1",
                    role = "assistant",
                    status = "error",
                    error = "  Exited with code 127  ",
                    exitCode = 127,
                ),
            ),
        )
        assertTrue(
            shouldShowAssistantErrorBanner(
                ChatMessage(
                    id = "m3",
                    tabId = "t1",
                    role = "assistant",
                    status = "error",
                    error = "Working directory does not exist: /bad",
                    exitCode = null,
                ),
            ),
        )
        assertTrue(
            shouldShowAssistantErrorBanner(
                ChatMessage(
                    id = "m4",
                    tabId = "t1",
                    role = "assistant",
                    status = "error",
                    error = "Killed by signal 9",
                    exitCode = 137,
                ),
            ),
        )
    }

    private class InMemoryConnectionSettings(
        private var saved: ConnectionConfig,
    ) : ConnectionSettings {
        private var lastTabId: String? = null
        private val lastCwdByHost = mutableMapOf<String, String>()

        override fun loadConnection(): ConnectionConfig = saved

        override fun saveConnection(config: ConnectionConfig) {
            saved = config
        }

        override fun loadLastTabId(): String? = lastTabId

        override fun saveLastTabId(tabId: String?) {
            lastTabId = tabId
        }

        override fun loadLastCwd(host: String): String? = lastCwdByHost[host]

        override fun saveLastCwd(host: String, cwd: String?) {
            if (cwd == null) {
                lastCwdByHost.remove(host)
            } else {
                lastCwdByHost[host] = cwd
            }
        }
    }
}
