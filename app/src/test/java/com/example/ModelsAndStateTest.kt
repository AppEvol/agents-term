package com.example

import com.example.data.ChatMessage
import com.example.data.ConnectionConfig
import com.example.data.SshBootstrapConfig
import com.example.data.TabRecord
import com.example.data.ToolCallItem
import com.example.data.WorkspaceState
import com.example.data.WsServerEnvelope
import com.example.data.buildAgyBootstrapCommand
import com.example.data.decodeHerdrJson
import com.example.data.encodeBase64Utf8
import com.example.data.extractPairingUriFromOutput
import com.example.data.reduceWorkspaceEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class ModelsAndStateTest {

    @Test
    fun parsePairingUriExtractsHostPortAndToken() {
        val cfg = ConnectionConfig.parsePairingUri(
            "herdr://connect?host=100.71.219.26&port=7501&token=abc_123"
        )
        assertNotNull(cfg)
        assertEquals("100.71.219.26", cfg!!.host)
        assertEquals(7501, cfg.port)
        assertEquals("abc_123", cfg.token)
        assertEquals("http://100.71.219.26:7501", cfg.httpBaseUrl())
        assertEquals("ws://100.71.219.26:7501/ws?token=abc_123", cfg.wsUrl())
    }

    @Test
    fun parsePairingUriRejectsInvalidStrings() {
        assertNull(ConnectionConfig.parsePairingUri("https://example.com"))
        assertNull(ConnectionConfig.parsePairingUri("herdr://connect?port=7501"))
    }

    @Test
    fun reduceWorkspaceEventStreamsDeltasAndToolUpdates() {
        val tab = TabRecord(
            id = "tab_1",
            title = "Antigravity · app",
            agent = "agy",
            cwd = "/home/ned/app",
            status = "working",
        )
        var state = reduceWorkspaceEvent(
            WorkspaceState(),
            WsServerEnvelope(type = "snapshot", tabs = listOf(tab), activeTabId = "tab_1"),
        )
        assertEquals("tab_1", state.selectedTabId)

        val msg = ChatMessage(
            id = "msg_1",
            tabId = "tab_1",
            role = "assistant",
            text = "",
            status = "streaming",
        )
        state = reduceWorkspaceEvent(
            state,
            WsServerEnvelope(type = "message_created", tabId = "tab_1", message = msg),
        )
        state = reduceWorkspaceEvent(
            state,
            WsServerEnvelope(
                type = "message_delta",
                tabId = "tab_1",
                messageId = "msg_1",
                textDelta = "Hello ",
                thinkingDelta = "Step 1",
            ),
        )
        state = reduceWorkspaceEvent(
            state,
            WsServerEnvelope(
                type = "message_delta",
                tabId = "tab_1",
                messageId = "msg_1",
                textDelta = "Android!",
            ),
        )
        state = reduceWorkspaceEvent(
            state,
            WsServerEnvelope(
                type = "tool_update",
                tabId = "tab_1",
                messageId = "msg_1",
                toolCall = ToolCallItem(
                    id = "t1",
                    name = "Bash",
                    inputSummary = "npm test",
                    status = "running",
                ),
            ),
        )
        state = reduceWorkspaceEvent(
            state,
            WsServerEnvelope(
                type = "tool_update",
                tabId = "tab_1",
                messageId = "msg_1",
                toolCall = ToolCallItem(
                    id = "t1",
                    name = "Bash",
                    inputSummary = "npm test",
                    output = "pass",
                    status = "done",
                ),
            ),
        )

        val finalMsg = state.activeMessages.single()
        assertEquals("Hello Android!", finalMsg.text)
        assertEquals("Step 1", finalMsg.thinking)
        assertEquals(1, finalMsg.toolCalls.size)
        assertEquals("done", finalMsg.toolCalls[0].status)
        assertEquals("pass", finalMsg.toolCalls[0].output)
    }

    @Test
    fun deserializesServerSnapshotJsonEnvelope() {
        val raw = """
            {
              "type": "snapshot",
              "server": {
                "version": "0.1.0",
                "hostname": "ned-vm",
                "tailscaleIp": "100.71.219.26",
                "defaultCwd": "/home/ned",
                "agents": [{"type": "agy", "name": "Antigravity", "available": true}]
              },
              "tabs": [],
              "activeTabId": null
            }
        """.trimIndent()
        val env = decodeHerdrJson<WsServerEnvelope>(raw)
        assertEquals("snapshot", env.type)
        assertEquals("ned-vm", env.server?.hostname)
        assertEquals(1, env.server?.agents?.size)
    }

    @Test
    fun extractPairingUriHandlesAnsiAndMarkdownOutput() {
        val esc = '\u001B'
        val rawOutput = """
            >>> Invoking Antigravity (agy --dangerously-skip-permissions) on dev-vm...
            Setup complete! Connect using:
            ${esc}[32m`herdr://connect?host=100.88.12.4&port=7501&token=tok_xyz99`.${esc}[0m
        """.trimIndent()

        val extracted = extractPairingUriFromOutput(rawOutput)
        assertNotNull(extracted)
        val (uri, cfg) = extracted!!
        assertEquals("herdr://connect?host=100.88.12.4&port=7501&token=tok_xyz99", uri)
        assertEquals("100.88.12.4", cfg.host)
        assertEquals(7501, cfg.port)
        assertEquals("tok_xyz99", cfg.token)
    }

    @Test
    fun buildAgyBootstrapCommandSafelyEncodesPromptAndUsesSkipPermissions() {
        val trickyPrompt = "Setup VM with 'quotes' and \$(whoami) and `backticks`\nline2"
        val cmd = buildAgyBootstrapCommand(prompt = trickyPrompt, daemonPort = 7501)

        // Outer command passes base64 script to bash without raw prompt interpolation
        assertTrue(cmd.startsWith("printf '%s' '"))
        assertTrue(cmd.endsWith("' | base64 -d | bash -s"))
        assertFalse(cmd.contains("\$(whoami)"))

        val b64Payload = cmd.removePrefix("printf '%s' '").removeSuffix("' | base64 -d | bash -s")
        val decodedScript = String(Base64.getDecoder().decode(b64Payload), Charsets.UTF_8)
        assertTrue(decodedScript.contains("agy --dangerously-skip-permissions --print="))
        assertTrue(decodedScript.contains(encodeBase64Utf8(trickyPrompt)))
        assertTrue(decodedScript.contains("pairing --port 7501"))
    }

    @Test
    fun sshBootstrapConfigValidatesHostAndUsername() {
        assertTrue(
            SshBootstrapConfig(host = "100.71.219.26", username = "ned").isValid
        )
        assertTrue(
            SshBootstrapConfig(host = "dev-vm.tail1234.ts.net", username = "ubuntu").isValid
        )
        assertFalse(
            SshBootstrapConfig(host = "100.71.219.26; rm -rf /", username = "ned").isValid
        )
        assertFalse(
            SshBootstrapConfig(host = "100.71.219.26", username = "ned && id").isValid
        )
    }
}
