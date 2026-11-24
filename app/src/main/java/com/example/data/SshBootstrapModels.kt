package com.example.data

import java.net.URLDecoder
import java.net.URLEncoder
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
) {
    /**
     * Builds a non-secret `agentsterm://ssh?...` setup link that can be bookmarked or pasted
     * after a fresh reinstall to autofill the initial SSH setup fields.
     */
    fun toSetupUri(scheme: String = "agentsterm"): String? {
        val cleanedHost = cleanHost(host)
        if (cleanedHost.isBlank()) return null
        val params = buildList {
            add("host=${URLEncoder.encode(cleanedHost, "UTF-8")}")
            val cleanUser = username.trim()
            if (cleanUser.isNotBlank()) {
                add("user=${URLEncoder.encode(cleanUser, "UTF-8")}")
            }
            if (sshPort in 1..65535 && sshPort != 22) {
                add("sshPort=$sshPort")
            }
            if (daemonPort in 1..65535 && daemonPort != 7501) {
                add("daemonPort=$daemonPort")
            }
            add("auth=${URLEncoder.encode(authMethod.storageKey, "UTF-8")}")
        }
        return "$scheme://ssh?${params.joinToString("&")}"
    }
}

/**
 * Parsed initial SSH setup values for autofilling ConnectScreen from a deep link,
 * clipboard `ssh` command, or `user@host[:port]` target string.
 */
data class SshSetupAutofill(
    val host: String,
    val username: String? = null,
    val sshPort: Int? = null,
    val daemonPort: Int? = null,
    val authMethod: SshAuthMethod? = null,
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

/** Git repo + ref the VM daemon (Python, `daemon/` in this repo) is installed from. */
const val AGENTSTERM_REPO_URL = "https://github.com/nedn/agents-term.git"
const val AGENTSTERM_DAEMON_REF = "main"

/** Checkout used by the VM daemon service, relative to `$HOME`. */
const val AGENTSTERM_INSTALL_SUBDIR = ".local/share/agentsterm/agents-term"

fun defaultAgyBootstrapPrompt(
    daemonPort: Int = 7501,
    repoUrl: String = AGENTSTERM_REPO_URL,
    ref: String = AGENTSTERM_DAEMON_REF,
): String {
    val dir = "\$HOME/$AGENTSTERM_INSTALL_SUBDIR"
    val install = "bash \"$dir/daemon/install.sh\" --port $daemonPort --ref $ref"
    return buildString {
        appendLine("Bootstrap this VM so the AgentsTerm Android app can connect over Tailscale.")
        appendLine("The VM daemon is the Python 3 program in `daemon/` of $repoUrl, installed by its `daemon/install.sh`. Do not write your own daemon.")
        appendLine("1. Install or update it by running exactly:")
        appendLine("   if [ -d \"$dir/.git\" ]; then $install; else git clone --depth 1 --branch $ref $repoUrl \"$dir\" && $install; fi")
        appendLine("   install.sh is idempotent: it updates the checkout, installs the herdr-mobile.service systemd --user unit (or a detached process), replaces any legacy Node daemon, health-checks http://<tailscale-ip>:$daemonPort/api/info, and prints a herdr://connect pairing URI.")
        appendLine("2. If it exits non-zero, read its ERROR line, fix the root cause, and run step 1 again:")
        appendLine("   - exit 3 (missing prerequisite): install python3 (>= 3.9) or git with the system package manager, or bring Tailscale up (`sudo tailscale up`). If GitHub is unreachable, say so clearly.")
        appendLine("   - exit 4 (daemon unhealthy): inspect `systemctl --user status herdr-mobile.service` or ~/.config/herdr-mobile/daemon.log (for example, another process holding port $daemonPort).")
        appendLine("3. End your final response with the exact `herdr://connect?host=...&port=$daemonPort&token=...` line that install.sh printed, on its own line.")
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
 * 1. Loads login PATH and checks if the AgentsTerm Python daemon (installed by `daemon/install.sh`) is
 *    already running and healthy on [daemonPort] (returning its pairing URI immediately if so).
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
        AGENTSTERM_DAEMON="${'$'}HOME/$AGENTSTERM_INSTALL_SUBDIR/daemon/agentsterm-daemon"
        if [ -f "${'$'}AGENTSTERM_DAEMON" ] && command -v python3 >/dev/null 2>&1; then
          TS_IP=${'$'}(tailscale ip -4 2>/dev/null | head -n 1 || true)
          if [ -n "${'$'}TS_IP" ]; then
            DAEMON_OK=${'$'}(python3 -c 'import json,os,sys,urllib.request
p=os.path.expanduser("~/.config/herdr-mobile/config.json")
t=json.load(open(p)).get("token","") if os.path.exists(p) else ""
r=urllib.request.Request("http://%s:%s/api/info" % (sys.argv[1], sys.argv[2]), headers={"Authorization": "Bearer " + t})
urllib.request.urlopen(r, timeout=3).read()' "${'$'}TS_IP" $daemonPort >/dev/null 2>&1 && echo yes || echo no)
            if [ "${'$'}DAEMON_OK" = "yes" ]; then
              echo ">>> AgentsTerm daemon is already running on ${'$'}{TS_IP}:$daemonPort."
              python3 "${'$'}AGENTSTERM_DAEMON" pairing --host "${'$'}TS_IP" --port $daemonPort
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
        if [ -f "${'$'}AGENTSTERM_DAEMON" ] && command -v python3 >/dev/null 2>&1; then
          echo ">>> Checking AgentsTerm pairing URI..."
          python3 "${'$'}AGENTSTERM_DAEMON" pairing --port $daemonPort || true
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

/**
 * Parses an `agentsterm://ssh?...` or `herdr://ssh?...` deep link for autofilling SSH setup.
 */
fun parseSshSetupUri(raw: String): SshSetupAutofill? {
    val trimmed = raw.trim()
    val validPrefix = trimmed.startsWith("agentsterm://ssh?", ignoreCase = true) ||
        trimmed.startsWith("herdr://ssh?", ignoreCase = true)
    if (!validPrefix) return null
    val queryIdx = trimmed.indexOf('?')
    if (queryIdx < 0) return null

    val query = trimmed.substring(queryIdx + 1)
    val params = mutableMapOf<String, String>()
    for (part in query.split('&')) {
        val eq = part.indexOf('=')
        if (eq > 0) {
            val k = runCatching { URLDecoder.decode(part.substring(0, eq), "UTF-8") }.getOrNull() ?: continue
            val v = runCatching { URLDecoder.decode(part.substring(eq + 1), "UTF-8") }.getOrNull() ?: continue
            params[k] = v
        }
    }

    val host = cleanHost(params["host"].orEmpty())
    if (!isValidHost(host)) return null

    val rawUser = (params["user"] ?: params["username"])?.trim()?.takeIf { it.isNotBlank() }
    if (rawUser != null && !isValidUsername(rawUser)) return null

    val sshPort = (params["sshPort"] ?: params["port"])?.toIntOrNull()?.takeIf { it in 1..65535 }
    val daemonPort = params["daemonPort"]?.toIntOrNull()?.takeIf { it in 1..65535 }
    val rawAuth = (params["auth"] ?: params["authMethod"])?.trim()?.takeIf { it.isNotBlank() }
    val authMethod = rawAuth?.let { key ->
        SshAuthMethod.entries.firstOrNull { it.storageKey.equals(key, ignoreCase = true) }
    }

    return SshSetupAutofill(
        host = host,
        username = rawUser,
        sshPort = sshPort,
        daemonPort = daemonPort,
        authMethod = authMethod,
    )
}

/**
 * Parses common SSH setup inputs for one-tap clipboard autofill or smart host-field paste:
 * - `agentsterm://ssh?host=...&user=...&sshPort=22&daemonPort=7501&auth=tailscale_ssh`
 * - `herdr://ssh?...`
 * - `ssh://user@host:2222`
 * - `ssh [-p 2222] [-l user] [user@]host`
 * - `user@host` or `user@host:2222`
 */
fun parseSshSetupInput(raw: String): SshSetupAutofill? {
    val trimmed = raw.trim().trim('`', '"', '\'')
    if (trimmed.isBlank()) return null

    parseSshSetupUri(trimmed)?.let { return it }

    // Standard ssh://[user@]host[:port] URI
    if (trimmed.startsWith("ssh://", ignoreCase = true)) {
        val remainder = trimmed.substring(6).substringBefore('/').substringBefore('?').trim()
        return parseUserHostTarget(remainder, requireUserOrPort = false)
    }

    // CLI form: ssh [options] [user@]host
    val tokens = trimmed.split(Regex("\\s+")).filter { it.isNotBlank() }
    if (tokens.isNotEmpty() && tokens.first().equals("ssh", ignoreCase = true)) {
        var sshPort: Int? = null
        var flagUser: String? = null
        var targetToken: String? = null

        val flagsWithValue = setOf(
            "-b", "-c", "-D", "-E", "-e", "-F", "-I", "-i", "-J",
            "-L", "-l", "-m", "-O", "-o", "-p", "-Q", "-R", "-S", "-W", "-w",
        )

        var idx = 1
        while (idx < tokens.size) {
            val tok = tokens[idx]
            when {
                tok == "-p" && idx + 1 < tokens.size -> {
                    sshPort = tokens[idx + 1].toIntOrNull()?.takeIf { it in 1..65535 }
                    idx += 2
                }
                tok.startsWith("-p") && tok.length > 2 -> {
                    sshPort = tok.substring(2).toIntOrNull()?.takeIf { it in 1..65535 }
                    idx++
                }
                tok == "-l" && idx + 1 < tokens.size -> {
                    flagUser = tokens[idx + 1].trim()
                    idx += 2
                }
                tok.startsWith("-l") && tok.length > 2 -> {
                    flagUser = tok.substring(2).trim()
                    idx++
                }
                tok in flagsWithValue && idx + 1 < tokens.size -> {
                    idx += 2
                }
                tok.startsWith("-") -> {
                    idx++
                }
                targetToken == null -> {
                    targetToken = tok
                    idx++
                }
                else -> idx++
            }
        }

        val target = targetToken ?: return null
        val parsed = parseUserHostTarget(target, requireUserOrPort = false) ?: return null
        val resolvedUser = parsed.username ?: flagUser?.takeIf { isValidUsername(it) }
        return parsed.copy(
            username = resolvedUser,
            sshPort = sshPort ?: parsed.sshPort,
        )
    }

    // Shorthand user@host or user@host:port
    return parseUserHostTarget(trimmed, requireUserOrPort = true)
}

private fun parseUserHostTarget(raw: String, requireUserOrPort: Boolean): SshSetupAutofill? {
    val cleaned = raw.trim()
    if (cleaned.isBlank() || cleaned.any { it.isWhitespace() }) return null

    val atIdx = cleaned.lastIndexOf('@')
    val username = if (atIdx >= 0) cleaned.substring(0, atIdx).trim() else null
    val hostAndPort = if (atIdx >= 0) cleaned.substring(atIdx + 1).trim() else cleaned

    if (username != null && !isValidUsername(username)) return null

    var host = hostAndPort
    var sshPort: Int? = null
    val colonCount = hostAndPort.count { it == ':' }
    if (colonCount == 1) {
        val candidateHost = hostAndPort.substringBefore(':')
        val candidatePort = hostAndPort.substringAfter(':').toIntOrNull()
        if (candidatePort != null && candidatePort in 1..65535) {
            host = candidateHost
            sshPort = candidatePort
        }
    }

    host = cleanHost(host)
    if (!isValidHost(host)) return null
    if (requireUserOrPort && username == null) return null

    return SshSetupAutofill(
        host = host,
        username = username,
        sshPort = sshPort,
    )
}
