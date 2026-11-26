package com.example

import com.example.data.AttachmentLimits
import com.example.data.ChatMessage
import com.example.data.ConnectionConfig
import com.example.data.HerdrApiClient
import com.example.data.QueuedMessage
import com.example.data.SendMessageRequest
import com.example.data.ServerInfo
import com.example.data.TabRecord
import com.example.data.attachmentLimitsForTab
import com.example.data.decodeHerdrJson
import com.example.data.encodeHerdrJson
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/** The wire format of image attachments, in both directions and against older daemons. */
class AttachmentApiTest {

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
    fun uploadPostsTheRawImageAndReturnsItsId() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"attachment":{"id":"att_1f","name":"att_1f.png","mimeType":"image/png","bytes":4,"path":"/vm/att_1f.png"}}""",
            ),
        )
        val bytes = byteArrayOf(1, 2, 3, 4)

        val attachment = api.uploadAttachment(config, "tab_9", bytes, "image/png")

        assertEquals("att_1f", attachment.id)
        assertEquals("/vm/att_1f.png", attachment.path)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/tabs/tab_9/attachments", request.path)
        assertEquals("Bearer secret-token", request.getHeader("Authorization"))
        assertEquals("image/png", request.getHeader("Content-Type"))
        assertArrayEquals(bytes, request.body.readByteArray())
    }

    @Test
    fun uploadSurfacesTheDaemonsReasonWhenAnImageIsRefused() {
        server.enqueue(MockResponse().setResponseCode(413).setBody("""{"error":"Images can be at most 8 MB"}"""))

        val err = assertThrows(IOException::class.java) {
            runBlocking { api.uploadAttachment(config, "tab_9", ByteArray(8), "image/jpeg") }
        }
        assertEquals("Images can be at most 8 MB", err.message)
    }

    @Test
    fun sendMessageRefersToUploadedImagesById() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"queued":false}"""))
        server.enqueue(MockResponse().setBody("""{"queued":false}"""))

        api.sendMessage(config, "tab_9", "what is this?", attachmentIds = listOf("att_1", "att_2"))
        api.sendMessage(config, "tab_9", "text only")

        val withImages = server.takeRequest()
        assertEquals("/api/tabs/tab_9/messages", withImages.path)
        assertEquals(
            SendMessageRequest(text = "what is this?", attachments = listOf("att_1", "att_2")),
            decodeHerdrJson<SendMessageRequest>(withImages.body.readUtf8()),
        )
        val textOnly = server.takeRequest().body.readUtf8()
        assertFalse("A plain message looks exactly as it did before attachments: $textOnly", "attachments" in textOnly)
    }

    @Test
    fun interruptCarriesTheImagesOfTheMessageBeingSentNow() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"ok":true}"""))

        api.interruptTab(config, "tab_9", processQueue = true, text = null, attachmentIds = listOf("att_7"))

        val body = server.takeRequest().body.readUtf8()
        assertTrue(body, """"attachments":["att_7"]""" in body)
        assertTrue(body, """"processQueue":true""" in body)
    }

    @Test
    fun downloadReturnsTheImageBytesAndFailsOnAMissingImage() {
        val bytes = ByteArray(2048) { (it % 251).toByte() }
        server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(bytes)))
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":"Attachment not found"}"""))

        val downloaded = runBlocking { api.downloadAttachment(config, "tab_9", "att_1") }
        assertArrayEquals(bytes, downloaded)
        val request = server.takeRequest()
        assertEquals("/api/tabs/tab_9/attachments/att_1", request.path)
        assertEquals("Bearer secret-token", request.getHeader("Authorization"))

        assertThrows(IOException::class.java) {
            runBlocking { api.downloadAttachment(config, "tab_9", "att_gone") }
        }
    }

    @Test
    fun messagesAndQueuedMessagesCarryTheirImages() {
        val message = decodeHerdrJson<ChatMessage>(
            """{"id":"m1","tabId":"t1","role":"user","text":"","attachments":[{"id":"att_1","mimeType":"image/jpeg","bytes":12}]}""",
        )
        assertEquals(listOf("att_1"), message.attachments.map { it.id })
        assertEquals("image/jpeg", message.attachments.single().mimeType)

        val queued = decodeHerdrJson<QueuedMessage>("""{"id":"q1","text":"","attachments":[{"id":"att_2"}]}""")
        assertEquals("att_2", queued.attachments.single().id)

        // History written before attachments existed still loads.
        val old = decodeHerdrJson<ChatMessage>("""{"id":"m0","tabId":"t1","role":"user","text":"hi"}""")
        assertTrue(old.attachments.isEmpty())
        assertEquals(old, decodeHerdrJson<ChatMessage>(encodeHerdrJson(old)))
    }

    @Test
    fun attachingIsOfferedOnlyWhenTheDaemonSupportsItAndTheTabRunsAnAgent() {
        val agentTab = TabRecord(id = "t1", title = "Claude", agent = "claude", cwd = "/home")
        val shellTab = agentTab.copy(id = "t2", agent = "shell")
        val newDaemon = decodeHerdrJson<ServerInfo>(
            """{"version":"0.3.0","attachments":{"maxBytes":8388608,"maxPerMessage":8,"mimeTypes":["image/png"]}}""",
        )
        val oldDaemon = decodeHerdrJson<ServerInfo>("""{"version":"0.3.0"}""")

        assertEquals(
            AttachmentLimits(maxBytes = 8_388_608, maxPerMessage = 8, mimeTypes = listOf("image/png")),
            attachmentLimitsForTab(newDaemon, agentTab),
        )
        assertNull("Shell tabs run commands and take no images", attachmentLimitsForTab(newDaemon, shellTab))
        assertNull("An older daemon cannot store images", attachmentLimitsForTab(oldDaemon, agentTab))
        assertNull(attachmentLimitsForTab(null, agentTab))
        assertNull(attachmentLimitsForTab(newDaemon, null))
    }
}
