package com.example.data

import java.util.Locale

enum class SshAuthMethod(val storageKey: String) {
    TAILSCALE_SSH("tailscale_ssh"),
    PRIVATE_KEY("private_key"),
    PASSWORD("password");

    companion object {
        fun fromStorageKey(key: String?): SshAuthMethod {
            return entries.firstOrNull { it.storageKey == key } ?: TAILSCALE_SSH
        }
    }
}

/**
 * Non-secret SSH connection profile persisted locally for convenience.
 * Passwords and private keys are NEVER saved to disk.
 */
data class SshProfile(
    val host: String = "",
    val sshPort: Int = 22,
    val username: String = "",
    val authMethod: SshAuthMethod = SshAuthMethod.TAILSCALE_SSH,
    val daemonPort: Int = 7501,
    val isBootstrapped: Boolean = false,
)

data class SshBootstrapConfig(
    val host: String,
    val sshPort: Int = 22,
    val username: String,
    val authMethod: SshAuthMethod = SshAuthMethod.TAILSCALE_SSH,
    val password: String = "",
    val privateKey: String = "",
    val passphrase: String = "",
    val daemonPort: Int = 7501,
    val prompt: String = defaultAgyBootstrapPrompt(daemonPort),
) {
    val isValid: Boolean
        get() = isValidHost(host) &&
            isValidUsername(username) &&
            sshPort in 1..65535 &&
            daemonPort in 1..65535
}

enum class BootstrapPhase {
    IDLE,
    CONNECTING_SSH,
    RUNNING_AGY,
    VERIFYING_DAEMON,
    SUCCEEDED,
    ERROR,
}

data class BootstrapUiState(
    val phase: BootstrapPhase = BootstrapPhase.IDLE,
    val logs: List<String> = emptyList(),
    val hostFingerprint: String? = null,
    val detectedPairingUri: String? = null,
    val errorMessage: String? = null,
    val isHostKeyMismatch: Boolean = false,
) {
    val isRunning: Boolean
        get() = phase == BootstrapPhase.CONNECTING_SSH ||
            phase == BootstrapPhase.RUNNING_AGY ||
            phase == BootstrapPhase.VERIFYING_DAEMON
}

class HostKeyMismatchException(
    val host: String,
    val port: Int,
    val expectedFingerprint: String,
    val receivedFingerprint: String,
) : Exception(
    "SSH host key changed for $host:$port.\nExpected: $expectedFingerprint\nReceived: $receivedFingerprint"
)

data class SshBootstrapResult(
    val connectionConfig: ConnectionConfig,
    val rawPairingUri: String,
    val hostFingerprint: String,
)

data class ParsedAgyStreamLine(
    val displayLines: List<String> = emptyList(),
    val pairingSourceText: String? = null,
    val textDelta: String? = null,
    val conversationId: String? = null,
    val isResult: Boolean = false,
    val resultStatus: String? = null,
)

private val hostAllowedRegex = Regex("^[A-Za-z0-9._:\\-]+$")
private val usernameAllowedRegex = Regex("^[A-Za-z0-9._\\-]+$")
private val ansiEscapeRegex = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]")
private val pairingUriRegex = Regex("""(?:agentsterm|herdr)://connect\?[^\s"'`<>)\]]+""", RegexOption.IGNORE_CASE)

fun isValidHost(raw: String): Boolean {
    val clean = raw.trim().removePrefix("http://").removePrefix("https://").trimEnd('/')
    return clean.isNotBlank() && hostAllowedRegex.matches(clean)
}

fun isValidUsername(raw: String): Boolean {
    val clean = raw.trim()
    return clean.isNotBlank() && usernameAllowedRegex.matches(clean)
}

fun cleanHost(raw: String): String {
    return raw.trim().removePrefix("http://").removePrefix("https://").trimEnd('/')
}

fun stripAnsiCodes(line: String): String {
    return ansiEscapeRegex.replace(line, "")
}

fun defaultAgyBootstrapPrompt(
    daemonPort: Int = 7501,
    repoUrl: String = "https://github.com/nedn/agent-terminal",
): String {
    return buildString {
        appendLine("Bootstrap this VM environment so the AgentsTerm Android client can connect over Tailscale:")
        appendLine("1. First check if the AgentsTerm daemon is already running and responding on http://<tailscale-ip>:$daemonPort/api/info. If it is already healthy, skip installation and go directly to step 4.")
        appendLine("2. If ~/agent-terminal/server/dist/index.js is not installed yet:")
        appendLine("   - Ensure Node.js (>= 20) and npm are available.")
        appendLine("   - If $repoUrl is accessible, clone it into ~/agent-terminal and run `npm install && npm run build` inside ~/agent-terminal/server.")
        appendLine("   - Otherwise, implement and build a lightweight Node.js daemon in ~/agent-terminal/server/dist/index.js matching the AgentsTerm HTTP + WebSocket API contract (refer to ~/projects/agents-term/README.md if present).")
        appendLine("3. Start the daemon persistently bound to this VM's Tailscale IPv4 on port $daemonPort (preferring `systemd --user` with herdr-mobile.service and `loginctl enable-linger`, or falling back to a fully detached process using `nohup node ~/agent-terminal/server/dist/index.js --port $daemonPort > ~/.config/herdr-mobile/daemon.log 2>&1 < /dev/null &` so stdout/stderr are not held open).")
        appendLine("4. Verify http://<tailscale-ip>:$daemonPort/api/info responds cleanly, run `node ~/agent-terminal/server/dist/index.js pairing --port $daemonPort`, and print the exact `herdr://connect?host=...&port=$daemonPort&token=...` URI on its own line in your final response.")
    }.trim()
}

/**
 * Pure Kotlin RFC-4648 Base64 encoder (works on Android API 24+ and JVM unit tests).
 */
fun encodeBase64Utf8(input: String): String {
    val bytes = input.toByteArray(Charsets.UTF_8)
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    val sb = StringBuilder(((bytes.size + 2) / 3) * 4)
    var i = 0
    while (i < bytes.size) {
        val b0 = bytes[i].toInt() and 0xFF
        val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xFF else 0
        val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xFF else 0
        val triple = (b0 shl 16) or (b1 shl 8) or b2
        sb.append(alphabet[(triple ushr 18) and 0x3F])
        sb.append(alphabet[(triple ushr 12) and 0x3F])
        sb.append(if (i + 1 < bytes.size) alphabet[(triple ushr 6) and 0x3F] else '=')
        sb.append(if (i + 2 < bytes.size) alphabet[triple and 0x3F] else '=')
        i += 3
    }
    return sb.toString()
}

/**
 * Parses a single NDJSON line emitted by `agy --output-format stream-json`.
 * Returns `null` when the line is plain text rather than an Antigravity stream-json event.
 */
@Suppress("UNCHECKED_CAST")
fun parseAgyStreamJsonLine(rawLine: String): ParsedAgyStreamLine? {
    val trimmed = rawLine.trim()
    if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return null

    val root = runCatching {
        HerdrMoshi.adapter(Map::class.java).lenient().fromJson(trimmed) as? Map<String, Any?>
    }.getOrNull() ?: return null

    val event = root["event"] as? String ?: return null
    val topConvId = root["conversation_id"] as? String

    return when (event) {
        "init" -> {
            val initObj = root["init"] as? Map<String, Any?>
            val cwd = initObj?.get("cwd") as? String
            val shortId = topConvId?.take(8)
            val label = buildString {
                append(">>> Antigravity session")
                if (!shortId.isNullOrBlank()) append(" $shortId")
                append(" started")
                if (!cwd.isNullOrBlank()) append(" ($cwd)")
            }
            ParsedAgyStreamLine(
                displayLines = listOf(label),
                conversationId = topConvId,
            )
        }

        "step_update" -> {
            val step = root["step_update"] as? Map<String, Any?> ?: return ParsedAgyStreamLine(conversationId = topConvId)
            val convId = (step["conversation_id"] as? String) ?: topConvId
            val stepType = step["step_type"] as? String
            val state = step["state"] as? String

            when (stepType) {
                "tool" -> {
                    val toolName = (step["tool_name"] as? String)
                        ?: ((step["tool_info"] as? Map<String, Any?>)?.get("name") as? String)
                        ?: "tool"
                    val toolInfo = step["tool_info"] as? Map<String, Any?>
                    val params = toolInfo?.get("parameters") as? Map<String, Any?>
                    val output = toolInfo?.get("output") as? String

                    if (state == "ACTIVE") {
                        val detail = extractToolSummary(params)
                        val msg = if (!detail.isNullOrBlank()) {
                            "[tool] $toolName: $detail"
                        } else {
                            "[tool] $toolName"
                        }
                        ParsedAgyStreamLine(displayLines = listOf(msg), conversationId = convId)
                    } else if (state == "DONE") {
                        val dur = (step["duration_seconds"] as? Number)?.toDouble()
                        val durText = dur?.let { String.format(Locale.US, " (%.1fs)", it) }.orEmpty()
                        ParsedAgyStreamLine(
                            displayLines = listOf("[tool done] $toolName$durText"),
                            pairingSourceText = output,
                            conversationId = convId,
                        )
                    } else {
                        ParsedAgyStreamLine(conversationId = convId)
                    }
                }

                "agent_response" -> {
                    val delta = step["text_delta"] as? String
                    ParsedAgyStreamLine(
                        textDelta = delta,
                        conversationId = convId,
                    )
                }

                else -> ParsedAgyStreamLine(conversationId = convId)
            }
        }

        "result" -> {
            val res = root["result"] as? Map<String, Any?> ?: return ParsedAgyStreamLine(
                isResult = true,
                conversationId = topConvId,
            )
            val convId = (res["conversation_id"] as? String) ?: topConvId
            val status = (res["status"] as? String) ?: "DONE"
            val response = res["response"] as? String
            val dur = (res["duration_seconds"] as? Number)?.toDouble()
            val turns = (res["num_turns"] as? Number)?.toInt()
            val summary = buildString {
                append(">>> Antigravity finished ($status")
                if (dur != null) append(String.format(Locale.US, " in %.1fs", dur))
                if (turns != null) append(", $turns turn${if (turns == 1) "" else "s"}")
                append(")")
            }
            ParsedAgyStreamLine(
                displayLines = listOf(summary),
                pairingSourceText = response,
                conversationId = convId,
                isResult = true,
                resultStatus = status,
            )
        }

        else -> ParsedAgyStreamLine(conversationId = topConvId)
    }
}

private fun extractToolSummary(params: Map<String, Any?>?): String? {
    if (params == null) return null
    val candidates = listOf("toolSummary", "CommandLine", "Path", "TargetFile", "Query", "SearchPath")
    for (key in candidates) {
        val raw = params[key] as? String ?: continue
        val singleLine = raw.trim().lineSequence().firstOrNull()?.trim().orEmpty()
        if (singleLine.isNotBlank()) {
            return if (singleLine.length > 96) singleLine.take(93) + "…" else singleLine
        }
    }
    return null
}

/**
 * Constructs a safe remote shell command that:
 * 1. Loads login PATH and checks if the AgentsTerm daemon is already running and healthy on [daemonPort]
 *    (returning its pairing URI immediately in <1s if already ready).
 * 2. Otherwise verifies `agy`, decodes the base64-encoded bootstrap prompt without shell injection risk,
 *    invokes `agy --dangerously-skip-permissions --print=... --output-format stream-json` for live progress,
 *    and prints the pairing URI when done.
 */
fun buildAgyBootstrapCommand(prompt: String, daemonPort: Int = 7501): String {
    require(daemonPort in 1..65535) { "Invalid daemon port: $daemonPort" }
    val promptB64 = encodeBase64Utf8(prompt)
    val script = """
        set -e
        [ -f "${'$'}HOME/.profile" ] && . "${'$'}HOME/.profile" >/dev/null 2>&1 || true
        export PATH="${'$'}HOME/.local/bin:${'$'}HOME/.npm-global/bin:${'$'}HOME/.cargo/bin:/usr/local/bin:${'$'}PATH"
        if [ -d "${'$'}HOME/.nvm/versions/node" ]; then
          NVM_LATEST=${'$'}(ls -1 "${'$'}HOME/.nvm/versions/node" 2>/dev/null | sort -V | tail -n 1)
          [ -n "${'$'}NVM_LATEST" ] && export PATH="${'$'}HOME/.nvm/versions/node/${'$'}NVM_LATEST/bin:${'$'}PATH"
        fi
        export FORCE_COLOR=0
        if [ -f "${'$'}HOME/agent-terminal/server/dist/index.js" ] && command -v node >/dev/null 2>&1; then
          TS_IP=${'$'}(tailscale ip -4 2>/dev/null | head -n 1 || true)
          if [ -n "${'$'}TS_IP" ] && command -v curl >/dev/null 2>&1; then
            HERDR_TOKEN=${'$'}(node -e "const fs=require('fs'),p=require('os').homedir()+'/.config/herdr-mobile/config.json'; if(fs.existsSync(p)) { const c=JSON.parse(fs.readFileSync(p,'utf8')); if(c.token) process.stdout.write(c.token); }" 2>/dev/null || true)
            if [ -n "${'$'}HERDR_TOKEN" ]; then
              DAEMON_OK=${'$'}(curl -fsS --max-time 3 -H "Authorization: Bearer ${'$'}HERDR_TOKEN" "http://${'$'}{TS_IP}:$daemonPort/api/info" >/dev/null 2>&1 && echo yes || echo no)
            else
              DAEMON_OK=${'$'}(curl -fsS --max-time 3 "http://${'$'}{TS_IP}:$daemonPort/api/info" >/dev/null 2>&1 && echo yes || echo no)
            fi
            if [ "${'$'}DAEMON_OK" = "yes" ]; then
              echo ">>> AgentsTerm daemon is already running on ${'$'}{TS_IP}:$daemonPort."
              node "${'$'}HOME/agent-terminal/server/dist/index.js" pairing --port $daemonPort
              exit 0
            fi
          fi
        fi
        if ! command -v agy >/dev/null 2>&1; then
          echo "ERROR: 'agy' (Antigravity CLI) was not found in PATH on this VM." >&2
          echo "Checked PATH: ${'$'}PATH" >&2
          exit 127
        fi
        HERDR_PROMPT=${'$'}(printf '%s' '$promptB64' | base64 -d)
        echo ">>> Invoking Antigravity (agy --dangerously-skip-permissions) on ${'$'}(hostname)..."
        if agy --help 2>&1 | grep -q -- '--output-format'; then
          agy --dangerously-skip-permissions --print="${'$'}HERDR_PROMPT" --output-format stream-json
        else
          agy --dangerously-skip-permissions --print="${'$'}HERDR_PROMPT"
        fi
        if [ -f "${'$'}HOME/agent-terminal/server/dist/index.js" ]; then
          echo ">>> Checking AgentsTerm pairing URI..."
          node "${'$'}HOME/agent-terminal/server/dist/index.js" pairing --port $daemonPort || true
        fi
    """.trimIndent()
    val scriptB64 = encodeBase64Utf8(script)
    return "printf '%s' '$scriptB64' | base64 -d | bash -s"
}

/**
 * Extracts the latest valid `herdr://connect?...` pairing URI from raw terminal output.
 */
fun extractPairingUriFromOutput(output: String): Pair<String, ConnectionConfig>? {
    val clean = stripAnsiCodes(output)
    val matches = pairingUriRegex.findAll(clean).toList()
    for (match in matches.asReversed()) {
        val candidate = match.value.trimEnd('.', ',', ';', ':', '!', ')')
        val parsed = ConnectionConfig.parsePairingUri(candidate)
        if (parsed != null && parsed.isValid) {
            return candidate to parsed
        }
    }
    return null
}
