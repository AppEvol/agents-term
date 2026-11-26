package com.example

import com.example.data.AssistantDisplayBlock
import com.example.data.ChatMessage
import com.example.data.ConnectionConfig
import com.example.data.CreateTabRequest
import com.example.data.DEFAULT_AGENT_SYSTEM_PROMPT
import com.example.data.DEFAULT_FULL_ACCESS_AGENT_SYSTEM_PROMPT
import com.example.data.DebugDumpUploadRequest
import com.example.data.DebugDumpUploadResponse
import com.example.data.DebugRecorder
import com.example.data.FsListResponse
import com.example.data.InterruptTabRequest
import com.example.data.MessageSegment
import com.example.data.ModelSlashCommand
import com.example.data.QueueActionResponse
import com.example.data.QueuedMessage
import com.example.data.SendMessageResponse
import com.example.data.ServerInfo
import com.example.data.SshAuthMethod
import com.example.data.SshBootstrapConfig
import com.example.data.SshProfile
import com.example.data.TabActivity
import com.example.data.TabRecord
import com.example.data.ToolCallItem
import com.example.data.UpdateTabRequest
import com.example.data.WORK_DONE_TOKEN
import com.example.data.WorkspaceState
import com.example.data.WsServerEnvelope
import com.example.data.buildAssistantDisplayBlocks
import com.example.data.buildDaemonBootstrapCommand
import com.example.data.buildSanitizedWorkspaceJson
import com.example.data.decodeHerdrJson
import com.example.data.encodeBase64Utf8
import com.example.data.encodeHerdrJson
import com.example.data.extractPairingUriFromOutput
import com.example.data.formatModelChipLabel
import com.example.data.formatToolCallSummary
import com.example.data.formatToolRunSummary
import com.example.data.isTabTaskFinished
import com.example.data.isTaskFinishedText
import com.example.data.normalizeFsPath
import com.example.data.parseModelSlashCommand
import com.example.data.parseSshSetupInput
import com.example.data.parseSshSetupUri
import com.example.data.reduceWorkspaceEvent
import com.example.data.resolveAgentModelCatalog
import com.example.data.resolveDefaultSystemPrompt
import com.example.data.resolveModelIdFromQuery
import com.example.data.resolvePreferredNewTabCwd
import com.example.data.resolveTabLatestDetail
import com.example.data.resolveTabTaskSummary
import com.example.data.sanitizeDebugText
import com.example.data.stripWorkDoneToken
import com.example.data.summarizeCommandForDisplay
import com.example.data.summarizeTaskPromptForDisplay
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
            "herdr://connect?host=100.71.219.26&port=7501&token=abc_123",
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
            $esc[32m`herdr://connect?host=100.88.12.4&port=7501&token=tok_xyz99`.$esc[0m
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
    fun buildDaemonBootstrapCommandDownloadsVerifiesAndInstallsStaticBinary() {
        val cmd = buildDaemonBootstrapCommand(daemonPort = 7600, releaseBaseUrl = "https://example.invalid/releases")
        assertTrue(cmd.startsWith("printf '%s' '"))
        assertTrue(cmd.endsWith("' | base64 -d | sh -s"))

        val b64Payload = cmd.removePrefix("printf '%s' '").removeSuffix("' | base64 -d | sh -s")
        val script = String(Base64.getDecoder().decode(b64Payload), Charsets.UTF_8)
        assertTrue(script.contains("https://example.invalid/releases"))
        assertTrue(script.contains("agentsterm-daemon-linux-"))
        assertTrue(script.contains(".sha256"))
        assertTrue(script.contains("exec \"\$BIN_PATH\" install --port 7600"))
        assertFalse(script.contains("agy --dangerously-skip-permissions"))
        assertFalse(script.contains("git clone"))
        assertFalse(script.contains("python3"))
    }

    @Test
    fun bootstrapRejectsUnsafeReleaseUrlAndTreatsNonZeroExitAsFailure() {
        for (bad in listOf("https://a; rm -rf ~", "\$(whoami)", "a b", "x'y", "")) {
            assertTrue(runCatching { buildDaemonBootstrapCommand(releaseBaseUrl = bad) }.isFailure)
        }
        buildDaemonBootstrapCommand(releaseBaseUrl = "http://127.0.0.1:18080/releases/v0.3.1")

        assertFalse(com.example.data.bootstrapExitIsFailure(0))
        assertFalse(com.example.data.bootstrapExitIsFailure(-1)) // dropped connection: handled separately
        assertTrue(com.example.data.bootstrapExitIsFailure(3))
        assertTrue(com.example.data.bootstrapExitIsFailure(4))
        assertTrue(com.example.data.bootstrapExitIsFailure(127))
    }

    @Test
    fun sshBootstrapConfigValidatesHostAndUsername() {
        assertTrue(
            SshBootstrapConfig(host = "100.71.219.26", username = "ned").isValid,
        )
        assertTrue(
            SshBootstrapConfig(host = "dev-vm.tail1234.ts.net", username = "ubuntu").isValid,
        )
        assertFalse(
            SshBootstrapConfig(host = "100.71.219.26; rm -rf /", username = "ned").isValid,
        )
        assertFalse(
            SshBootstrapConfig(host = "100.71.219.26", username = "ned && id").isValid,
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
    fun everyTabIsWorkingWaitingOrFinishedAndTheBadgeCountsUnopenedResults() {
        fun tab(id: String, status: String = "idle", unread: Boolean = false) = TabRecord(
            id = id,
            title = id,
            agent = "claude",
            cwd = "/home/ned/app",
            status = status,
            unread = unread,
        )

        val state = WorkspaceState(
            tabs = listOf(
                tab("t1", unread = true),
                tab("t2", unread = true),
                tab("t3", status = "blocked"),
                tab("t4", status = "error", unread = true),
                tab("t5", status = "working"),
                tab("t6", status = "done", unread = true),
            ),
            selectedTabId = "t1",
        )
        assertEquals(
            listOf(
                TabActivity.WAITING,
                TabActivity.WAITING,
                TabActivity.WAITING,
                TabActivity.WAITING,
                TabActivity.WORKING,
                TabActivity.FINISHED,
            ),
            state.tabs.map { state.activityOf(it) },
        )
        // The badge is the unread tabs other than the one on screen, whatever state they are in.
        assertEquals(listOf("t2", "t4", "t6"), state.tabs.filter { state.isTabUnread(it) }.map { it.id })
        assertEquals(3, state.attentionCount)
        assertEquals(4, state.copy(selectedTabId = "t5").attentionCount)
        // With no explicit selection the first tab is the one shown.
        assertEquals(3, state.copy(selectedTabId = null).attentionCount)
        assertEquals(0, WorkspaceState(tabs = listOf(tab("t1", unread = true)), selectedTabId = "t1").attentionCount)
    }

    @Test
    fun debugDumpUploadSerializationAndWorkspaceRedaction() {
        val state = WorkspaceState(
            config = ConnectionConfig(host = "100.71.219.26", port = 7501, token = "my_private_bearer_token"),
            tabs = listOf(
                TabRecord(id = "t1", title = "Agy", agent = "agy", cwd = "/home/ned/app", status = "working"),
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
            """{"ok":true,"dumpId":"abcd1234","remoteDir":"/home/ned/.agents-term/debug-dumps/latest",""" +
                """"latestSymlink":"/home/ned/.agents-term/debug-dumps/latest","files":["summary.md","events.ndjson"]}""",
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
            "herdr://ssh?host=ned-vm&username=ubuntu&port=2200&daemonPort=7505&authMethod=password",
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
            """{"path":"/home/ned","parent":"/home","directories":[]}""",
        )
        assertNull(legacyFs.exists)
        val explicitFs = decodeHerdrJson<FsListResponse>(
            """{"path":"/home/ned/gone","parent":"/home/ned","directories":[],"exists":false}""",
        )
        assertEquals(false, explicitFs.exists)
    }

    @Test
    fun toolCallSummariesPreferDescriptionAndStripLeadingCd() {
        assertEquals(
            "Run unit tests",
            formatToolCallSummary(
                ToolCallItem(
                    id = "t1",
                    name = "Bash",
                    inputSummary = "cd ~/projects/agents-term-app-state && ./gradlew test",
                    inputJson = """{"command":"cd ~/projects/agents-term-app-state && ./gradlew test","description":"Run unit tests"}""",
                    status = "done",
                ),
            ),
        )
        assertEquals(
            "Check git status",
            formatToolCallSummary(
                ToolCallItem(
                    id = "t2",
                    name = "run_command",
                    inputSummary = "cd ~/projects/app && git status",
                    inputJson = """{"CommandLine":"cd ~/projects/app && git status","toolSummary":"Check git status"}""",
                    status = "done",
                ),
            ),
        )
        assertEquals(
            "git diff --stat",
            formatToolCallSummary(
                ToolCallItem(
                    id = "t3",
                    name = "Bash",
                    inputSummary = "cd ~/projects/agents-term-app-state && git diff --stat",
                    inputJson = """{"command":"cd ~/projects/agents-term-app-state && git diff --stat"}""",
                    status = "done",
                ),
            ),
        )
        assertEquals("pytest -q", summarizeCommandForDisplay("""cd "/tmp/my app" && cd sub && pytest -q"""))
        assertEquals("git log -n 5", summarizeCommandForDisplay("cd ~/projects/app\ngit log -n 5"))
        assertEquals("cd ~/projects/app", summarizeCommandForDisplay("cd ~/projects/app"))
        assertEquals("ls -la", summarizeCommandForDisplay("/bin/bash -lc 'cd /work/repo && ls -la'"))
    }

    @Test
    fun toolRunSummaryAndDisplayBlocksGroupAndInterleaveCalls() {
        val t1 = ToolCallItem("t1", "Bash", "git status", status = "done", durationMs = 100L)
        val t2 = ToolCallItem("t2", "Bash", "git diff", status = "done", durationMs = 150L)
        val t3 = ToolCallItem("t3", "Bash", "pytest", status = "running")
        val t4 = ToolCallItem("t4", "Bash", "lint", status = "error")
        val t5 = ToolCallItem("t5", "Write", "/etc/hosts", status = "denied")

        assertEquals("3 tool calls · 2 done · 1 running", formatToolRunSummary(listOf(t1, t2, t3)))
        assertEquals("2 tool calls · 2 done", formatToolRunSummary(listOf(t1, t2)))
        assertEquals(
            "4 tool calls · 1 done · 1 running · 1 failed · 1 blocked",
            formatToolRunSummary(listOf(t1, t3, t4, t5)),
        )

        val interleaved = ChatMessage(
            id = "m1",
            tabId = "tab_1",
            role = "assistant",
            text = "Step 1\n\nStep 2\n\nDone.",
            toolCalls = listOf(t1, t2, t3),
            segments = listOf(
                MessageSegment(type = "text", text = "Step 1"),
                MessageSegment(type = "tool", toolCallId = "t1"),
                MessageSegment(type = "text", text = "   "), // blank text must not split run
                MessageSegment(type = "tool", toolCallId = "t2"),
                MessageSegment(type = "text", text = "Step 2"),
                MessageSegment(type = "tool", toolCallId = "t3"),
                MessageSegment(type = "text", text = "Done."),
            ),
        )
        val blocks = buildAssistantDisplayBlocks(interleaved)
        assertEquals(
            listOf(
                AssistantDisplayBlock.TextBlock("Step 1"),
                AssistantDisplayBlock.ToolRunBlock(listOf(t1, t2)),
                AssistantDisplayBlock.TextBlock("Step 2"),
                AssistantDisplayBlock.ToolRunBlock(listOf(t3)),
                AssistantDisplayBlock.TextBlock("Done."),
            ),
            blocks,
        )

        // Legacy message without segments falls back to tool run then text
        val legacy = ChatMessage(
            id = "m_old",
            tabId = "tab_1",
            role = "assistant",
            text = "Final answer",
            toolCalls = listOf(t1, t2),
            segments = emptyList(),
        )
        assertEquals(
            listOf(
                AssistantDisplayBlock.ToolRunBlock(listOf(t1, t2)),
                AssistantDisplayBlock.TextBlock("Final answer"),
            ),
            buildAssistantDisplayBlocks(legacy),
        )
    }

    @Test
    fun assistantDisplayBlocksHideLeadingTaskSummaryTag() {
        val leaked = "<tab_task_summary>Retry the publish step.</tab_task_summary>\nI'll check GitHub access."
        val tool = ToolCallItem(id = "t1", name = "Bash", status = "done")
        val segmented = ChatMessage(
            id = "a1",
            tabId = "tab_1",
            role = "assistant",
            text = "$leaked\n\nPR is open.",
            toolCalls = listOf(tool),
            segments = listOf(
                MessageSegment(type = "text", text = leaked),
                MessageSegment(type = "tool", toolCallId = "t1"),
                MessageSegment(type = "text", text = "PR is open."),
            ),
        )
        assertEquals(
            listOf(
                AssistantDisplayBlock.TextBlock("I'll check GitHub access."),
                AssistantDisplayBlock.ToolRunBlock(listOf(tool)),
                AssistantDisplayBlock.TextBlock("PR is open."),
            ),
            buildAssistantDisplayBlocks(segmented),
        )

        val legacy = ChatMessage(id = "a2", tabId = "tab_1", role = "assistant", text = leaked)
        assertEquals(
            listOf(AssistantDisplayBlock.TextBlock("I'll check GitHub access.")),
            buildAssistantDisplayBlocks(legacy),
        )

        // A tag quoted later in the text is part of the reply and stays.
        val quoted = "The `<tab_task_summary>x</tab_task_summary>` tag leaked."
        assertEquals(
            listOf(AssistantDisplayBlock.TextBlock(quoted)),
            buildAssistantDisplayBlocks(legacy.copy(text = quoted)),
        )
    }

    @Test
    fun reduceWorkspaceEventBuildsSegmentsLiveAndPreservesAcrossLegacyUpdate() {
        val tab = TabRecord("tab_1", "Claude · app", "claude", "/home/ned/app", status = "working")
        var state = reduceWorkspaceEvent(
            WorkspaceState(),
            WsServerEnvelope(type = "snapshot", tabs = listOf(tab), activeTabId = "tab_1"),
        )
        val msg = ChatMessage(id = "msg_1", tabId = "tab_1", role = "assistant", text = "", status = "streaming")
        state = reduceWorkspaceEvent(state, WsServerEnvelope(type = "message_created", tabId = "tab_1", message = msg))
        state = reduceWorkspaceEvent(
            state,
            WsServerEnvelope(type = "message_delta", tabId = "tab_1", messageId = "msg_1", textDelta = "Checking now."),
        )
        state = reduceWorkspaceEvent(
            state,
            WsServerEnvelope(
                type = "tool_update",
                tabId = "tab_1",
                messageId = "msg_1",
                toolCall = ToolCallItem("t1", "Bash", "git status", status = "running"),
            ),
        )
        state = reduceWorkspaceEvent(
            state,
            WsServerEnvelope(
                type = "tool_update",
                tabId = "tab_1",
                messageId = "msg_1",
                toolCall = ToolCallItem("t1", "Bash", "git status", output = "clean", status = "done"),
            ),
        )
        state = reduceWorkspaceEvent(
            state,
            WsServerEnvelope(type = "message_delta", tabId = "tab_1", messageId = "msg_1", textDelta = "\n\nAll done."),
        )

        val streamed = state.activeMessages.single()
        assertEquals("Checking now.\n\nAll done.", streamed.text)
        assertEquals(
            listOf(
                MessageSegment(type = "text", text = "Checking now."),
                MessageSegment(type = "tool", toolCallId = "t1"),
                MessageSegment(type = "text", text = "All done."),
            ),
            streamed.segments,
        )

        // Legacy daemon sends message_updated without segments: streamed segments should be preserved
        val legacyFinal = streamed.copy(status = "complete", segments = emptyList())
        val afterLegacyUpdate = reduceWorkspaceEvent(
            state,
            WsServerEnvelope(type = "message_updated", tabId = "tab_1", message = legacyFinal),
        )
        assertEquals(streamed.segments, afterLegacyUpdate.activeMessages.single().segments)

        // JSON backward compatibility: decodes with and without segments
        val withoutSegments = decodeHerdrJson<ChatMessage>(
            """{"id":"m1","tabId":"t1","role":"assistant","text":"Hi","toolCalls":[]}""",
        )
        assertTrue(withoutSegments.segments.isEmpty())
        val withSegments = decodeHerdrJson<ChatMessage>(
            """{"id":"m2","tabId":"t1","role":"assistant","text":"Hi","toolCalls":[],"segments":[{"type":"text","text":"Hi"}]}""",
        )
        assertEquals(listOf(MessageSegment(type = "text", text = "Hi")), withSegments.segments)
    }

    @Test
    fun systemPromptDefaultsResolutionAndSerializationWork() {
        assertTrue(DEFAULT_AGENT_SYSTEM_PROMPT.contains("mobile", ignoreCase = true))
        assertTrue(DEFAULT_AGENT_SYSTEM_PROMPT.contains("agents_term_app_cli"))
        assertTrue(DEFAULT_AGENT_SYSTEM_PROMPT.contains(".agents-term/live-debugging.md"))
        assertTrue(DEFAULT_AGENT_SYSTEM_PROMPT.contains("read-only"))
        assertFalse(DEFAULT_AGENT_SYSTEM_PROMPT.contains("close this tab", ignoreCase = true))
        assertTrue(DEFAULT_FULL_ACCESS_AGENT_SYSTEM_PROMPT.contains("close this tab", ignoreCase = true))

        assertEquals(DEFAULT_AGENT_SYSTEM_PROMPT, resolveDefaultSystemPrompt(null, "default"))
        assertEquals(DEFAULT_FULL_ACCESS_AGENT_SYSTEM_PROMPT, resolveDefaultSystemPrompt(null, "full-access"))
        assertEquals(DEFAULT_AGENT_SYSTEM_PROMPT, resolveDefaultSystemPrompt(ServerInfo(defaultSystemPrompt = "   "), "plan"))
        assertEquals(
            "Daemon custom default",
            resolveDefaultSystemPrompt(ServerInfo(defaultSystemPrompt = "  Daemon custom default  "), "accept-edits"),
        )
        assertEquals(
            "Daemon full access default",
            resolveDefaultSystemPrompt(
                ServerInfo(
                    defaultSystemPrompt = "Daemon custom default",
                    defaultSystemPromptFullAccess = "  Daemon full access default  ",
                ),
                "full-access",
            ),
        )
        val fallbackFullAccess = resolveDefaultSystemPrompt(
            ServerInfo(defaultSystemPrompt = "Daemon custom default"),
            "full-access",
        )
        assertTrue(fallbackFullAccess.startsWith("Daemon custom default"))
        assertTrue(fallbackFullAccess.contains("close this tab", ignoreCase = true))

        val snapshotWithPrompt = decodeHerdrJson<WsServerEnvelope>(
            """
            {
              "type": "snapshot",
              "server": {
                "version": "0.2.0",
                "hostname": "vm",
                "defaultSystemPrompt": "From daemon",
                "defaultSystemPromptFullAccess": "From daemon full access"
              },
              "tabs": [
                {
                  "id": "tab_1",
                  "title": "Agy",
                  "agent": "agy",
                  "cwd": "/home/ned",
                  "systemPrompt": "Per-tab prompt"
                }
              ]
            }
            """.trimIndent(),
        )
        assertEquals("From daemon", snapshotWithPrompt.server?.defaultSystemPrompt)
        assertEquals("From daemon full access", snapshotWithPrompt.server?.defaultSystemPromptFullAccess)
        assertEquals("Per-tab prompt", snapshotWithPrompt.tabs?.single()?.systemPrompt)

        val createEmptyJson = encodeHerdrJson(CreateTabRequest(agent = "claude", systemPrompt = ""))
        assertTrue(createEmptyJson.contains("\"systemPrompt\":\"\""))

        val clearJson = encodeHerdrJson(UpdateTabRequest(systemPrompt = ""))
        assertTrue(clearJson.contains("\"systemPrompt\":\"\""))
        val titleOnlyJson = encodeHerdrJson(UpdateTabRequest(title = "Renamed"))
        assertFalse(titleOnlyJson.contains("\"systemPrompt\""))
    }

    @Test
    fun workDoneTokenDetectionAndStrippingIgnoreCodeBlocks() {
        assertTrue(isTaskFinishedText("All tests pass.\n\n$WORK_DONE_TOKEN"))
        assertTrue(isTaskFinishedText("Done!\n${WORK_DONE_TOKEN.uppercase()}\n"))
        assertFalse(isTaskFinishedText("This task is finished — you can close this tab whenever you're ready."))
        assertFalse(isTaskFinishedText("Do not close this tab yet."))
        assertFalse(isTaskFinishedText("I will emit `$WORK_DONE_TOKEN` once finished."))
        assertFalse(isTaskFinishedText("```text\n$WORK_DONE_TOKEN\n```"))
        assertFalse(isTaskFinishedText("~~~\n$WORK_DONE_TOKEN\n~~~\nStill working."))
        assertFalse(isTaskFinishedText("```$WORK_DONE_TOKEN```"))
        assertTrue(
            isTaskFinishedText(
                "```kotlin\nconst val T = \"$WORK_DONE_TOKEN\"\n```\n\nAll fixed!\n\n$WORK_DONE_TOKEN",
            ),
        )
        assertEquals(
            "```kotlin\nconst val T = \"$WORK_DONE_TOKEN\"\n```\n\nAll fixed!",
            stripWorkDoneToken(
                "```kotlin\nconst val T = \"$WORK_DONE_TOKEN\"\n```\n\nAll fixed!\n\n$WORK_DONE_TOKEN\n",
            ),
        )
        assertEquals(
            "Look at foo`bar above.\n\nDone!",
            stripWorkDoneToken("Look at foo`bar above.\n\nDone!\n\n$WORK_DONE_TOKEN"),
        )

        val msgWithRawToken = ChatMessage(
            id = "m_done",
            tabId = "t_tok",
            role = "assistant",
            text = "Shipped!\n\n$WORK_DONE_TOKEN",
            segments = listOf(MessageSegment(type = "text", text = "Shipped!\n\n$WORK_DONE_TOKEN")),
            status = "complete",
        )
        assertEquals(
            listOf(AssistantDisplayBlock.TextBlock("Shipped!")),
            buildAssistantDisplayBlocks(msgWithRawToken),
        )
    }

    @Test
    fun finishedTaskDetectionAndWorkspaceStateWork() {
        val explicitDone = TabRecord("t_done", "Done Tab", "agy", "/home/ned", status = "done")
        val tokenDone = TabRecord(
            "t_token",
            "Token Tab",
            "claude",
            "/home/ned",
            status = "idle",
            lastMessagePreview = "All set!\n$WORK_DONE_TOKEN",
        )
        val proseOnlyTab = TabRecord(
            "t_prose",
            "Prose Tab",
            "claude",
            "/home/ned",
            status = "idle",
            lastMessagePreview = "All set — you can close this tab.",
        )
        val workingTab = TabRecord("t_work", "Working Tab", "codex", "/home/ned", status = "working")
        val shellTab = TabRecord(
            "t_shell",
            "Shell",
            "shell",
            "/home/ned",
            status = "done",
            lastMessagePreview = WORK_DONE_TOKEN,
        )

        assertTrue(isTabTaskFinished(explicitDone))
        assertTrue(isTabTaskFinished(tokenDone))
        assertFalse(isTabTaskFinished(tokenDone, manuallyReopened = true))
        assertFalse(isTabTaskFinished(proseOnlyTab))
        assertFalse(isTabTaskFinished(workingTab))
        assertFalse(isTabTaskFinished(shellTab))
        assertEquals("All set!", resolveTabLatestDetail(tokenDone, null))

        val ws = WorkspaceState(tabs = listOf(explicitDone, tokenDone, proseOnlyTab, workingTab, shellTab))
        assertEquals(setOf("t_done", "t_token"), ws.finishedTabIds)

        val statusJson = encodeHerdrJson(UpdateTabRequest(status = "done"))
        assertTrue(statusJson.contains("\"status\":\"done\""))
    }

    @Test
    fun queuedMessagesSerializationAndWorkspaceReductionRemainBackwardCompatible() {
        // Legacy tab JSON without queuedMessages decodes with an empty queue
        val legacyTab = decodeHerdrJson<TabRecord>(
            """{"id":"tab_legacy","title":"Agy","agent":"agy","cwd":"/home/ned","status":"working"}""",
        )
        assertTrue(legacyTab.queuedMessages.isEmpty())

        // Queued SendMessageResponse decodes cleanly without userMessage/assistantMessage
        val queuedResp = decodeHerdrJson<SendMessageResponse>(
            """
            {
              "queued": true,
              "queuedMessage": {"id": "q_1", "text": "Follow up", "createdAt": 1700000001000},
              "tab": {
                "id": "tab_1",
                "title": "Agy",
                "agent": "agy",
                "cwd": "/home/ned",
                "status": "working",
                "queuedMessages": [{"id": "q_1", "text": "Follow up", "createdAt": 1700000001000}]
              }
            }
            """.trimIndent(),
        )
        assertTrue(queuedResp.queued)
        assertNull(queuedResp.userMessage)
        assertEquals("q_1", queuedResp.queuedMessage?.id)
        assertEquals(listOf("Follow up"), queuedResp.tab?.queuedMessages?.map { it.text })

        // InterruptTabRequest and QueueActionResponse round-trip cleanly (including legacy {"ok":true})
        val intrJson = encodeHerdrJson(InterruptTabRequest(processQueue = true, text = "Run now"))
        assertTrue(intrJson.contains("\"processQueue\":true"))
        assertTrue(intrJson.contains("\"text\":\"Run now\""))
        val legacyOk = decodeHerdrJson<QueueActionResponse>("""{"ok":true}""")
        assertTrue(legacyOk.ok)
        assertNull(legacyOk.tab)

        // reduceWorkspaceEvent propagates queuedMessages updates via tab_updated
        val initial = reduceWorkspaceEvent(
            WorkspaceState(),
            WsServerEnvelope(type = "snapshot", tabs = listOf(legacyTab), activeTabId = "tab_legacy"),
        )
        val withQueue = legacyTab.copy(
            queuedMessages = listOf(QueuedMessage(id = "q_1", text = "Second prompt")),
        )
        val afterUpdate = reduceWorkspaceEvent(
            initial,
            WsServerEnvelope(type = "tab_updated", tab = withQueue),
        )
        assertEquals(listOf("Second prompt"), afterUpdate.activeTab?.queuedMessages?.map { it.text })
    }

    @Test
    fun taskSummarySerializationAndFallbackResolutionWork() {
        // Legacy JSON without taskSummary decodes with null
        val legacyTab = decodeHerdrJson<TabRecord>(
            """{"id":"tab_old","title":"Agy","agent":"agy","cwd":"/home/ned","lastMessagePreview":"Done!"}""",
        )
        assertNull(legacyTab.taskSummary)

        // Newer daemon JSON with taskSummary decodes cleanly
        val modernTab = decodeHerdrJson<TabRecord>(
            """
            {
              "id": "tab_new",
              "title": "Agy",
              "agent": "agy",
              "cwd": "/home/ned",
              "status": "idle",
              "lastMessagePreview": "All tests pass.",
              "taskSummary": "Fix daemon reconnect race condition"
            }
            """.trimIndent(),
        )
        assertEquals("Fix daemon reconnect race condition", modernTab.taskSummary)
        assertEquals("Fix daemon reconnect race condition", resolveTabTaskSummary(modernTab, emptyList()))
        assertEquals("All tests pass.", resolveTabLatestDetail(modernTab, modernTab.taskSummary))
        // Avoid repeating identical text in latest detail
        assertNull(
            resolveTabLatestDetail(
                modernTab.copy(lastMessagePreview = "Fix daemon reconnect race condition"),
                modernTab.taskSummary,
            ),
        )

        // Older daemon fallback derives summary from first non-/model user message
        val messages = listOf(
            ChatMessage(id = "m1", tabId = "tab_old", role = "user", text = "/model opus"),
            ChatMessage(id = "m2", tabId = "tab_old", role = "assistant", text = "Switched"),
            ChatMessage(
                id = "m3",
                tabId = "tab_old",
                role = "user",
                text = "## Debug `ws` drop\nCheck [logs](https://example.com)",
            ),
        )
        assertEquals("Debug ws drop Check logs", resolveTabTaskSummary(legacyTab, messages))
        assertEquals("Done!", resolveTabTaskSummary(legacyTab, emptyList()))
        assertNull(resolveTabTaskSummary(legacyTab.copy(lastMessagePreview = null), emptyList()))

        // Working / blocked / error latest detail uses statusDetail or clear fallback
        assertEquals(
            "Running pytest",
            resolveTabLatestDetail(modernTab.copy(status = "working", statusDetail = "Running pytest"), modernTab.taskSummary),
        )
        assertEquals(
            "Waiting for approval",
            resolveTabLatestDetail(modernTab.copy(status = "blocked", statusDetail = null), modernTab.taskSummary),
        )
        assertEquals(
            "Last turn failed",
            resolveTabLatestDetail(modernTab.copy(status = "error", statusDetail = null), modernTab.taskSummary),
        )

        // Code-block stripping and clean truncation
        assertEquals(
            "Investigate crash in MainActivity",
            summarizeTaskPromptForDisplay("Investigate crash in `MainActivity`\n```kotlin\nthrow Error()\n```"),
        )
    }

    @Test
    fun agentUsageFormattingAndModelLookup() {
        val raw = """
            {
              "agent": "agy",
              "fetchedAt": 1700000000000,
              "plan": "pro",
              "items": [
                {
                  "id": "gemini-3-flash",
                  "label": "Gemini Models",
                  "remainingPercent": 75.6,
                  "usedPercent": 24.4,
                  "resetsAtMs": 1700009300000,
                  "modelIds": ["gemini-3-flash"],
                  "modelPrefixes": ["gemini-"]
                },
                {
                  "id": "claude-opus-5-5-high",
                  "label": "Claude Opus 5.5",
                  "remainingPercent": 0.4,
                  "usedPercent": 99.6,
                  "resetsAtMs": 1700090000000,
                  "modelIds": ["claude-opus-5-5-high"],
                  "modelPrefixes": []
                }
              ]
            }
        """.trimIndent()
        val parsed = com.example.data.decodeHerdrJson<com.example.data.AgentUsageResponse>(raw)
        assertEquals("agy", parsed.agent)
        assertEquals("pro", parsed.plan)
        assertEquals(2, parsed.items.size)

        assertEquals("76%", com.example.data.formatUsagePercent(parsed.items[0].remainingPercent))
        assertEquals("<1%", com.example.data.formatUsagePercent(parsed.items[1].remainingPercent))
        assertEquals("0%", com.example.data.formatUsagePercent(0.0))
        assertEquals("100%", com.example.data.formatUsagePercent(100.0))

        val now = 1_700_000_000_000L
        assertEquals("Resets in 2h 35m", com.example.data.formatTimeUntilReset(now + 9_300_000L, now))
        assertEquals("Resets in 1d 1h", com.example.data.formatTimeUntilReset(now + 90_000_000L, now))
        assertEquals("Resets in 45m", com.example.data.formatTimeUntilReset(now + 2_700_000L, now))
        assertEquals("Resets in <1m", com.example.data.formatTimeUntilReset(now + 20_000L, now))
        assertEquals("Resets soon", com.example.data.formatTimeUntilReset(now - 5_000L, now))
        assertNull(com.example.data.formatTimeUntilReset(null, now))

        assertEquals(
            "Gemini Models",
            com.example.data.findUsageForModel("gemini-4-neon-high", parsed.items)?.label,
        )
        assertEquals(
            "Claude Opus 5.5",
            com.example.data.findUsageForModel("claude-opus-5-5-high", parsed.items)?.label,
        )
        assertNull(com.example.data.findUsageForModel("unknown-model", parsed.items))
    }
}
