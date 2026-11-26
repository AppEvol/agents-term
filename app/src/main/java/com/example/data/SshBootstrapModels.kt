package com.example.data

import com.example.BuildConfig
import java.net.URLDecoder
import java.net.URLEncoder

enum class SshAuthMethod(val storageKey: String) {
    TAILSCALE_SSH("tailscale_ssh"),
    PRIVATE_KEY("private_key"),
    PASSWORD("password"),
    ;

    companion object {
        fun fromStorageKey(key: String?): SshAuthMethod = entries.firstOrNull { it.storageKey == key } ?: TAILSCALE_SSH
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
    INSTALLING_DAEMON,
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
            phase == BootstrapPhase.INSTALLING_DAEMON ||
            phase == BootstrapPhase.VERIFYING_DAEMON
}

class HostKeyMismatchException(
    val host: String,
    val port: Int,
    val expectedFingerprint: String,
    val receivedFingerprint: String,
) : Exception(
    "SSH host key changed for $host:$port.\nExpected: $expectedFingerprint\nReceived: $receivedFingerprint",
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

fun cleanHost(raw: String): String = raw.trim().removePrefix("http://").removePrefix("https://").trimEnd('/')

fun stripAnsiCodes(line: String): String = ansiEscapeRegex.replace(line, "")

/** Base URL hosting the prebuilt static Rust daemon binaries and `.sha256` checksum files. */
val AGENTSTERM_RELEASE_BASE_URL: String = BuildConfig.DAEMON_RELEASE_BASE_URL

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
 * Constructs the minimal POSIX remote shell command for SSH bootstrap.
 *
 * Requires only POSIX `sh` + `curl` or `wget` + `sha256sum` or `shasum` on the VM (zero `git`,
 * `python3`, `bash`, or `agy` dependencies):
 * 1. Detects `Linux` + `x86_64` / `aarch64`.
 * 2. Downloads the release `.sha256` file and compares it against `~/.agents-term/bin/agentsterm-daemon`
 *    (skipping the binary download if already current).
 * 3. Downloads and verifies the static binary, atomically replaces `~/.agents-term/bin/agentsterm-daemon`
 *    (avoiding `ETXTBSY` on a running daemon), and executes `agentsterm-daemon install --port <port>`.
 */
private val daemonBootstrapScriptTemplate = """
        [ -f "${'$'}HOME/.profile" ] && command . "${'$'}HOME/.profile" >/dev/null 2>&1 || true
        set -eu
        APP_HOME="${'$'}{AGENTSTERM_HOME:-${'$'}HOME/.agents-term}"
        export PATH="${'$'}APP_HOME/bin:${'$'}HOME/.local/bin:${'$'}HOME/.npm-global/bin:${'$'}HOME/.cargo/bin:/usr/local/bin:${'$'}PATH"
        export FORCE_COLOR=0
        BASE_URL="${'$'}{AGENTSTERM_RELEASE_BASE_URL:-__RELEASE_BASE_URL__}"
        BIN_DIR="${'$'}{AGENTSTERM_BIN_DIR:-${'$'}APP_HOME/bin}"
        BIN_PATH="${'$'}BIN_DIR/agentsterm-daemon"
        die() { code="${'$'}1"; shift; printf 'ERROR: %s\n' "${'$'}*" >&2; exit "${'$'}code"; }
        OS=${'$'}(uname -s)
        [ "${'$'}OS" = "Linux" ] || die 3 "Unsupported OS: ${'$'}OS (Linux required)."
        RAW_ARCH=${'$'}(uname -m)
        case "${'$'}RAW_ARCH" in
          x86_64|amd64) ARCH="x86_64" ;;
          aarch64|arm64) ARCH="aarch64" ;;
          *) die 3 "Unsupported architecture: ${'$'}RAW_ARCH (supported: x86_64, aarch64)." ;;
        esac
        ASSET="agentsterm-daemon-linux-${'$'}ARCH"
        BIN_URL="${'$'}{BASE_URL%/}/${'$'}ASSET"
        SHA_URL="${'$'}BIN_URL.sha256"
        fetch() {
          if command -v curl >/dev/null 2>&1; then
            curl -fsSL --retry 3 -o "${'$'}2" "${'$'}1"
          elif command -v wget >/dev/null 2>&1; then
            wget -qO "${'$'}2" "${'$'}1"
          else
            die 3 "Neither 'curl' nor 'wget' is installed on this VM."
          fi
        }
        sha256_of() {
          if command -v sha256sum >/dev/null 2>&1; then
            sha256sum "${'$'}1" | awk '{print ${'$'}1}'
          elif command -v shasum >/dev/null 2>&1; then
            shasum -a 256 "${'$'}1" | awk '{print ${'$'}1}'
          else
            die 3 "Neither 'sha256sum' nor 'shasum' is installed on this VM."
          fi
        }
        mkdir -p "${'$'}APP_HOME" "${'$'}APP_HOME/tmp" "${'$'}BIN_DIR"
        chmod 700 "${'$'}APP_HOME" "${'$'}APP_HOME/tmp" 2>/dev/null || true
        TMP_DIR=${'$'}(mktemp -d "${'$'}APP_HOME/tmp/agentsterm-dl.XXXXXX")
        trap 'rm -rf "${'$'}TMP_DIR"' EXIT INT TERM
        printf '>>> Checking latest AgentsTerm daemon (%s)...\n' "${'$'}ASSET"
        fetch "${'$'}SHA_URL" "${'$'}TMP_DIR/checksum.sha256" || die 3 "Failed to download checksum from ${'$'}SHA_URL"
        EXPECTED_SHA=${'$'}(awk 'NR==1 {print ${'$'}1}' "${'$'}TMP_DIR/checksum.sha256")
        [ -n "${'$'}EXPECTED_SHA" ] || die 3 "Checksum file from ${'$'}SHA_URL was empty."
        NEED_DL=1
        if [ -x "${'$'}BIN_PATH" ]; then
          CURRENT_SHA=${'$'}(sha256_of "${'$'}BIN_PATH" || true)
          if [ "${'$'}CURRENT_SHA" = "${'$'}EXPECTED_SHA" ]; then
            printf '>>> Installed binary already matches release SHA-256.\n'
            NEED_DL=0
          fi
        fi
        if [ "${'$'}NEED_DL" = 1 ]; then
          printf '>>> Downloading %s...\n' "${'$'}BIN_URL"
          fetch "${'$'}BIN_URL" "${'$'}TMP_DIR/${'$'}ASSET" || die 3 "Failed to download daemon binary from ${'$'}BIN_URL"
          ACTUAL_SHA=${'$'}(sha256_of "${'$'}TMP_DIR/${'$'}ASSET")
          [ "${'$'}ACTUAL_SHA" = "${'$'}EXPECTED_SHA" ] || die 3 "SHA-256 mismatch for ${'$'}ASSET (expected ${'$'}EXPECTED_SHA, got ${'$'}ACTUAL_SHA)."
          chmod 755 "${'$'}TMP_DIR/${'$'}ASSET"
          mv "${'$'}TMP_DIR/${'$'}ASSET" "${'$'}BIN_DIR/.agentsterm-daemon.tmp.${'$'}${'$'}"
          mv -f "${'$'}BIN_DIR/.agentsterm-daemon.tmp.${'$'}${'$'}" "${'$'}BIN_PATH"
          printf '>>> Installed verified binary at %s\n' "${'$'}BIN_PATH"
        fi
        rm -rf "${'$'}TMP_DIR"
        trap - EXIT INT TERM
        exec "${'$'}BIN_PATH" install --port __DAEMON_PORT__
""".trimIndent()

fun buildDaemonBootstrapCommand(
    daemonPort: Int = 7501,
    releaseBaseUrl: String = AGENTSTERM_RELEASE_BASE_URL,
): String {
    require(daemonPort in 1..65535) { "Invalid daemon port: $daemonPort" }
    val trimmedBase = releaseBaseUrl.trimEnd('/')
    require(shellSafeRegex.matches(trimmedBase)) { "Unsafe release base URL: $releaseBaseUrl" }
    val script = daemonBootstrapScriptTemplate
        .replace("__RELEASE_BASE_URL__", trimmedBase)
        .replace("__DAEMON_PORT__", daemonPort.toString())
    val scriptB64 = encodeBase64Utf8(script)
    return "printf '%s' '$scriptB64' | base64 -d | sh -s"
}

/**
 * True when the remote bootstrap script finished with an error. `-1` means the SSH channel closed
 * without reporting a status (connection dropped), which is handled separately.
 */
fun bootstrapExitIsFailure(exitCode: Int): Boolean = exitCode != 0 && exitCode != -1

/** Characters allowed in values interpolated into the bootstrap shell script. */
private val shellSafeRegex = Regex("^[A-Za-z0-9._:/@+=-]+$")

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
