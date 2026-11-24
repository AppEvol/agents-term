package com.example

import com.example.data.AgentModelOption
import com.example.data.AvailableAgentInfo
import com.example.data.ChatMessage
import com.example.data.ConnectionConfig
import com.example.data.DebugDumpUploadRequest
import com.example.data.DebugDumpUploadResponse
import com.example.data.DebugRecorder
import com.example.data.FsListResponse
import com.example.data.ModelSlashCommand
import com.example.data.SshAuthMethod
import com.example.data.SshBootstrapConfig
import com.example.data.SshProfile
import com.example.data.TabRecord
import com.example.data.ToolCallItem
import com.example.data.UpdateTabRequest
import com.example.data.WorkspaceState
import com.example.data.WsServerEnvelope
import com.example.data.buildAgyBootstrapCommand
import com.example.data.buildSanitizedWorkspaceJson
import com.example.data.decodeHerdrJson
import com.example.data.encodeBase64Utf8
import com.example.data.encodeHerdrJson
import com.example.data.extractPairingUriFromOutput
import com.example.data.formatModelChipLabel
import com.example.data.normalizeFsPath
import com.example.data.parseAgyStreamJsonLine
import com.example.data.parseModelSlashCommand
import com.example.data.parseSshSetupInput
import com.example.data.parseSshSetupUri
import com.example.data.reduceWorkspaceEvent
import com.example.data.resolveAgentModelCatalog
import com.example.data.resolveModelIdFromQuery
import com.example.data.resolvePreferredNewTabCwd
import com.example.data.sanitizeDebugText
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
        assertTrue(decodedScript.contains("--output-format stream-json"))
        assertTrue(decodedScript.contains(encodeBase64Utf8(trickyPrompt)))
        assertTrue(decodedScript.contains("pairing --port 7501"))
        assertTrue(decodedScript.contains("DAEMON_OK"))
    }

    @Test
    fun bootstrapTargetsPythonDaemonInstaller() {
        val cmd = buildAgyBootstrapCommand(prompt = "p", daemonPort = 7600)
        val script = String(
            Base64.getDecoder().decode(cmd.removePrefix("printf '%s' '").removeSuffix("' | base64 -d | bash -s")),
            Charsets.UTF_8,
        )
        assertTrue(script.contains("\$HOME/.local/share/agentsterm/agents-term/daemon/agentsterm-daemon"))
        assertTrue(script.contains("python3 \"\$AGENTSTERM_DAEMON\" pairing --host \"\$TS_IP\" --port 7600"))
        assertFalse(script.contains("agent-terminal/server"))
        assertFalse(script.contains("node "))

        val prompt = com.example.data.defaultAgyBootstrapPrompt(daemonPort = 7600)
        assertTrue(prompt.contains("git clone --depth 1 --branch main https://github.com/nedn/agents-term.git"))
        assertTrue(prompt.contains("bash \"\$HOME/.local/share/agentsterm/agents-term/daemon/install.sh\" --port 7600 --ref main"))
        assertTrue(prompt.contains("Do not write your own daemon"))
        assertFalse(prompt.contains("Node.js"))
    }

    @Test
    fun parseAgyStreamJsonLineHandlesInitToolsDeltasAndResult() {
        assertNull(parseAgyStreamJsonLine(">>> plain shell output"))

        val initEvent = parseAgyStreamJsonLine(
            """{"event":"init","conversation_id":"9fedea5e-6c97-4678-b333-f40fa796d7ed","init":{"cwd":"/home/ned"}}"""
        )
        assertNotNull(initEvent)
        assertEquals("9fedea5e-6c97-4678-b333-f40fa796d7ed", initEvent!!.conversationId)
        assertTrue(initEvent.displayLines.single().contains("9fedea5e"))

        val activeTool = parseAgyStreamJsonLine(
            """{"event":"step_update","step_update":{"state":"ACTIVE","step_type":"tool","tool_name":"run_command","tool_info":{"name":"run_command","parameters":{"CommandLine":"npm run build","toolSummary":"Build server"}}}}"""
        )
        assertNotNull(activeTool)
        assertEquals("[tool] run_command: Build server", activeTool!!.displayLines.single())

        val doneTool = parseAgyStreamJsonLine(
            """{"event":"step_update","step_update":{"state":"DONE","step_type":"tool","tool_name":"run_command","duration_seconds":1.25,"tool_info":{"output":"herdr://connect?host=100.71.219.26&port=7501&token=abc"}}}"""
        )
        assertNotNull(doneTool)
        assertEquals("[tool done] run_command (1.3s)", doneTool!!.displayLines.single())
        assertNotNull(extractPairingUriFromOutput(doneTool.pairingSourceText!!))

        val delta = parseAgyStreamJsonLine(
            """{"event":"step_update","step_update":{"state":"ACTIVE","step_type":"agent_response","text_delta":"Setup done\n"}}"""
        )
        assertNotNull(delta)
        assertEquals("Setup done\n", delta!!.textDelta)

        val result = parseAgyStreamJsonLine(
            """{"event":"result","result":{"conversation_id":"9fedea5e-6c97-4678-b333-f40fa796d7ed","status":"SUCCESS","response":"herdr://connect?host=100.71.219.26&port=7501&token=tok123\n","duration_seconds":5.3,"num_turns":2}}"""
        )
        assertNotNull(result)
        assertTrue(result!!.isResult)
        assertEquals("SUCCESS", result.resultStatus)
        assertTrue(result.displayLines.single().contains("5.3s"))
        assertEquals("tok123", extractPairingUriFromOutput(result.pairingSourceText!!)!!.second.token)
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

    @Test
    fun debugRecorderRedactsSecretsEnforcesRingLimitAndSerializesNdjson() {
        val rawSecret = "Authorization: Bearer super_secret_tok_99 and ws://100.1.2.3:7501/ws?token=abc_secret_2 and Token: -PpQT9y2xIYJ0u9U"
        val cleaned = sanitizeDebugText(rawSecret)
        assertFalse(cleaned.contains("super_secret_tok_99"))
        assertFalse(cleaned.contains("abc_secret_2"))
        assertFalse(cleaned.contains("-PpQT9y2xIYJ0u9U"))
        assertTrue(cleaned.contains("Bearer [REDACTED]"))
        assertTrue(cleaned.contains("token=[REDACTED]"))
        assertTrue(cleaned.contains("Token: [REDACTED]"))

        val recorder = DebugRecorder(maxEvents = 3)
        for (i in 1..5) {
            recorder.record(
                category = "ws",
                event = "evt_$i",
                detail = "detail $i token=secret_$i",
            )
        }
        assertEquals(3, recorder.count)
        val events = recorder.snapshot()
        assertEquals(listOf("evt_3", "evt_4", "evt_5"), events.map { it.event })
        assertFalse(events.any { it.detail.contains("secret_") })

        val ndjsonLines = recorder.toNdjson().trim().lines()
        assertEquals(3, ndjsonLines.size)
        assertTrue(ndjsonLines.first().contains("evt_3"))
    }

    @Test
    fun debugDumpUploadSerializationAndWorkspaceRedaction() {
        val state = WorkspaceState(
            config = ConnectionConfig(host = "100.71.219.26", port = 7501, token = "my_private_bearer_token"),
            tabs = listOf(
                TabRecord(id = "t1", title = "Agy", agent = "agy", cwd = "/home/ned/app", status = "working")
            ),
            selectedTabId = "t1",
        )
        val stateJson = buildSanitizedWorkspaceJson(state)
        assertFalse(stateJson.contains("my_private_bearer_token"))
        assertTrue(stateJson.contains("100.71.219.26"))

        val req = DebugDumpUploadRequest(
            dumpId = "abcd1234",
            createdAt = 1700000000000L,
            note = "agy agent mode glitched",
            appVersion = "1.0",
            deviceInfo = "Pixel 8",
            connectionSummary = "100.71.219.26:7501 (CONNECTED)",
            workspaceStateJson = stateJson,
            eventsNdjson = "",
        )
        val encoded = com.example.data.encodeHerdrJson(req)
        val decoded = decodeHerdrJson<DebugDumpUploadRequest>(encoded)
        assertEquals("abcd1234", decoded.dumpId)
        assertEquals("agy agent mode glitched", decoded.note)

        val resp = decodeHerdrJson<DebugDumpUploadResponse>(
            """{"ok":true,"dumpId":"abcd1234","remoteDir":"/home/ned/.config/herdr-mobile/debug-dumps/latest","latestSymlink":"/home/ned/.config/herdr-mobile/debug-dumps/latest","files":["summary.md","events.ndjson"]}"""
        )
        assertTrue(resp.ok)
        assertEquals("abcd1234", resp.dumpId)
        assertEquals(2, resp.files.size)
    }

    @Test
    fun sshSetupUriRoundTripAndDoesNotCollideWithPairingUri() {
        val profile = SshProfile(
            host = "100.71.219.26",
            sshPort = 2222,
            username = "ned",
            authMethod = SshAuthMethod.PRIVATE_KEY,
            daemonPort = 7502,
        )
        val uri = profile.toSetupUri()
        assertNotNull(uri)
        assertTrue(uri!!.startsWith("agentsterm://ssh?"))
        assertNull(ConnectionConfig.parsePairingUri(uri))

        val parsed = parseSshSetupUri(uri)
        assertNotNull(parsed)
        assertEquals("100.71.219.26", parsed!!.host)
        assertEquals("ned", parsed.username)
        assertEquals(2222, parsed.sshPort)
        assertEquals(7502, parsed.daemonPort)
        assertEquals(SshAuthMethod.PRIVATE_KEY, parsed.authMethod)

        val herdrUri = parseSshSetupUri(
            "herdr://ssh?host=ned-vm&username=ubuntu&port=2200&daemonPort=7505&authMethod=password"
        )
        assertNotNull(herdrUri)
        assertEquals("ned-vm", herdrUri!!.host)
        assertEquals("ubuntu", herdrUri.username)
        assertEquals(2200, herdrUri.sshPort)
        assertEquals(7505, herdrUri.daemonPort)
        assertEquals(SshAuthMethod.PASSWORD, herdrUri.authMethod)

        // Reject shell-injection payloads and non-SSH schemes
        assertNull(parseSshSetupUri("agentsterm://ssh?host=100.1.2.3;rm&user=ned"))
        assertNull(parseSshSetupUri("agentsterm://ssh?host=100.1.2.3&user=ned%20%26%26%20id"))
        assertNull(parseSshSetupUri("herdr://connect?host=100.1.2.3&port=7501&token=abc"))
    }

    @Test
    fun parseSshSetupInputHandlesCliCommandsSshUriAndUserAtHost() {
        val cliSimple = parseSshSetupInput("ssh ned@100.71.219.26")
        assertNotNull(cliSimple)
        assertEquals("100.71.219.26", cliSimple!!.host)
        assertEquals("ned", cliSimple.username)
        assertNull(cliSimple.sshPort)

        val cliWithFlags = parseSshSetupInput("ssh -p 2222 -i ~/.ssh/id_ed25519 ned@ned-vm.tail1234.ts.net")
        assertNotNull(cliWithFlags)
        assertEquals("ned-vm.tail1234.ts.net", cliWithFlags!!.host)
        assertEquals("ned", cliWithFlags.username)
        assertEquals(2222, cliWithFlags.sshPort)

        val cliCompactFlags = parseSshSetupInput("ssh -p2200 -l ubuntu 100.71.219.26")
        assertNotNull(cliCompactFlags)
        assertEquals("100.71.219.26", cliCompactFlags!!.host)
        assertEquals("ubuntu", cliCompactFlags.username)
        assertEquals(2200, cliCompactFlags.sshPort)

        val sshScheme = parseSshSetupInput("ssh://ned@100.71.219.26:2222")
        assertNotNull(sshScheme)
        assertEquals("100.71.219.26", sshScheme!!.host)
        assertEquals("ned", sshScheme.username)
        assertEquals(2222, sshScheme.sshPort)

        val shorthand = parseSshSetupInput("ned@100.71.219.26:2201")
        assertNotNull(shorthand)
        assertEquals("100.71.219.26", shorthand!!.host)
        assertEquals("ned", shorthand.username)
        assertEquals(2201, shorthand.sshPort)

        // Plain hostname/IP without user@ or ssh prefix should not trigger false autofill
        assertNull(parseSshSetupInput("100.71.219.26"))
        assertNull(parseSshSetupInput("ned-vm"))
    }

    @Test
    fun modelSlashCommandsCatalogResolutionAndUpdateSerializationWork() {
        // Slash command parsing
        assertEquals(ModelSlashCommand.OpenPicker, parseModelSlashCommand("/model"))
        assertEquals(ModelSlashCommand.OpenPicker, parseModelSlashCommand("  /MODEL  "))
        assertEquals(ModelSlashCommand.ResetToDefault, parseModelSlashCommand("/model default"))
        assertEquals(ModelSlashCommand.ResetToDefault, parseModelSlashCommand("/model reset"))
        assertEquals(ModelSlashCommand.ResetToDefault, parseModelSlashCommand("/model auto"))
        assertEquals(
            ModelSlashCommand.SetModel("gemini-4-neon-high"),
            parseModelSlashCommand("/model gemini-4-neon-high"),
        )
        assertNull(parseModelSlashCommand("/models"))
        assertNull(parseModelSlashCommand("Explain /model usage"))

        // Server catalog + backward-compatible snapshot deserialization
        val raw = """
            {
              "type": "snapshot",
              "server": {
                "version": "0.1.0",
                "hostname": "ned-vm",
                "agents": [
                  {
                    "type": "claude",
                    "name": "Claude Code",
                    "available": true,
                    "defaultModel": "claude-sonnet-5-5",
                    "models": [
                      {"id": "claude-opus-5-5", "name": "Opus 5.5", "description": "Complex work"},
                      {"id": "claude-sonnet-5-5", "name": "Sonnet 5.5", "isDefault": true}
                    ]
                  },
                  {
                    "type": "codex",
                    "name": "Codex",
                    "available": true
                  }
                ]
              },
              "tabs": []
            }
        """.trimIndent()
        val env = decodeHerdrJson<WsServerEnvelope>(raw)
        val agents = env.server!!.agents

        val (claudeModels, claudeDefault) = resolveAgentModelCatalog("claude", agents)
        assertEquals("claude-sonnet-5-5", claudeDefault)
        assertEquals(2, claudeModels.size)
        assertEquals("claude-opus-5-5", resolveModelIdFromQuery("Opus 5.5", claudeModels))
        assertEquals("claude-opus-5-5", resolveModelIdFromQuery("CLAUDE-OPUS-5-5", claudeModels))
        assertEquals("custom-slug", resolveModelIdFromQuery("custom-slug", claudeModels))

        assertEquals("Opus 5.5", formatModelChipLabel("claude-opus-5-5", claudeModels, claudeDefault))
        assertEquals("Default · Sonnet 5.5", formatModelChipLabel(null, claudeModels, claudeDefault))
        assertEquals("custom-slug", formatModelChipLabel("custom-slug", claudeModels, claudeDefault))

        // Older daemon without models falls back to built-in suggestions; shell stays empty
        val (codexModels, _) = resolveAgentModelCatalog("codex", agents)
        assertTrue(codexModels.isNotEmpty())
        val (shellModels, shellDefault) = resolveAgentModelCatalog("shell", agents)
        assertTrue(shellModels.isEmpty())
        assertNull(shellDefault)

        // UpdateTabRequest clearing model sends explicit empty string while title-only omits model
        val clearJson = encodeHerdrJson(UpdateTabRequest(model = ""))
        assertTrue(clearJson.contains("\"model\":\"\""))
        val renameJson = encodeHerdrJson(UpdateTabRequest(title = "Renamed"))
        assertFalse(renameJson.contains("\"model\""))
    }

    @Test
    fun preferredNewTabCwdPrioritizesLastFolderThenActiveTabThenHome() {
        assertEquals(
            "/home/ned/projects/app",
            resolvePreferredNewTabCwd(
                lastCwd = "/home/ned/projects/app",
                activeTabCwd = "/home/ned",
                homeCwd = "/home/ned",
            ),
        )
        assertEquals(
            "/home/ned/projects/active",
            resolvePreferredNewTabCwd(
                lastCwd = "   ",
                activeTabCwd = "/home/ned/projects/active",
                homeCwd = "/home/ned",
            ),
        )
        assertEquals(
            "/home/ned",
            resolvePreferredNewTabCwd(
                lastCwd = null,
                activeTabCwd = null,
                homeCwd = "/home/ned",
            ),
        )
        assertEquals(
            "/home",
            resolvePreferredNewTabCwd(lastCwd = null, activeTabCwd = null, homeCwd = null),
        )

        assertEquals("/", normalizeFsPath("/"))
        assertEquals("/", normalizeFsPath("///"))
        assertEquals("/home/ned/projects", normalizeFsPath(" /home/ned/projects/ "))

        val legacyFs = decodeHerdrJson<FsListResponse>(
            """{"path":"/home/ned","parent":"/home","directories":[]}"""
        )
        assertNull(legacyFs.exists)
        val explicitFs = decodeHerdrJson<FsListResponse>(
            """{"path":"/home/ned/gone","parent":"/home/ned","directories":[],"exists":false}"""
        )
        assertEquals(false, explicitFs.exists)
    }
}
