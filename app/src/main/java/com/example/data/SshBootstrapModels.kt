package com.example.data

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
        appendLine("1. Check if the AgentsTerm / agent-terminal daemon (from $repoUrl) is already running and reachable on port $daemonPort.")
        appendLine("2. If it is not installed or running yet:")
        appendLine("   - Clone $repoUrl into ~/agent-terminal (or update the existing checkout if present).")
        appendLine("   - Ensure Node.js (>= 20) and npm are available, then run `npm install && npm run build` inside ~/agent-terminal/server.")
        appendLine("   - Start the daemon bound to this VM's Tailscale IPv4 on port $daemonPort persistently (preferring `systemd --user` with herdr-mobile.service and `loginctl enable-linger`, or falling back to a detached background process if systemd user sessions are unavailable).")
        appendLine("3. Verify that the daemon responds on http://<tailscale-ip>:$daemonPort/api/info.")
        appendLine("4. Run `node ~/agent-terminal/server/dist/index.js pairing` and print the exact `herdr://connect?host=...&port=$daemonPort&token=...` URI on its own line in your final response.")
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
 * Constructs a safe remote shell command that loads login PATH, verifies `agy`,
 * decodes the base64-encoded bootstrap prompt without shell injection risk,
 * invokes `agy --dangerously-skip-permissions --print=...`, and ensures the
 * pairing URI is printed when done.
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
        if ! command -v agy >/dev/null 2>&1; then
          echo "ERROR: 'agy' (Antigravity CLI) was not found in PATH on this VM." >&2
          echo "Checked PATH: ${'$'}PATH" >&2
          exit 127
        fi
        HERDR_PROMPT=${'$'}(printf '%s' '$promptB64' | base64 -d)
        echo ">>> Invoking Antigravity (agy --dangerously-skip-permissions) on ${'$'}(hostname)..."
        agy --dangerously-skip-permissions --print="${'$'}HERDR_PROMPT"
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
