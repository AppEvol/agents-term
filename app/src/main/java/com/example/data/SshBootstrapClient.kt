package com.example.data

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import com.jcraft.jsch.UserInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.security.MessageDigest
import java.util.Properties

/**
 * Connects to a Tailscale VM over SSH to install or update the AgentsTerm daemon via
 * `daemon/install.sh` (falling back to `agy --dangerously-skip-permissions` to repair missing
 * prerequisites if needed), streaming live progress back to the mobile UI.
 */
class SshBootstrapClient(
    private val settings: SettingsStore,
    private val debugRecorder: DebugRecorder? = null,
) {
    suspend fun bootstrapWithAgy(
        config: SshBootstrapConfig,
        onPhase: (BootstrapPhase) -> Unit,
        onHostFingerprint: (String) -> Unit,
        onLog: (String) -> Unit,
    ): SshBootstrapResult = withContext(Dispatchers.IO) {
        val host = cleanHost(config.host)
        val username = config.username.trim()

        require(isValidHost(host)) { "Invalid Tailscale host or IP address" }
        require(isValidUsername(username)) { "Invalid SSH username" }
        require(config.sshPort in 1..65535) { "SSH port must be between 1 and 65535" }
        require(config.daemonPort in 1..65535) { "Daemon port must be between 1 and 65535" }

        onPhase(BootstrapPhase.CONNECTING_SSH)
        debugRecorder?.record("ssh", "ssh_connect_start", "Connecting to $username@$host:${config.sshPort} (auth=${config.authMethod.storageKey})")
        onLog("Connecting to $username@$host:${config.sshPort} via SSH…")

        var observedFingerprint: String? = null
        var hostKeyMismatch: HostKeyMismatchException? = null

        val jsch = JSch().apply {
            // Pre-authentication Trust-On-First-Use (TOFU) host key verifier:
            // rejects changed host keys before credentials are transmitted.
            hostKeyRepository = object : HostKeyRepository {
                override fun check(checkHost: String?, key: ByteArray?): Int {
                    if (key == null) return HostKeyRepository.NOT_INCLUDED
                    val fp = computeSha256Fingerprint(key)
                    observedFingerprint = fp
                    val stored = settings.getTrustedHostFingerprint(host, config.sshPort)
                    return when {
                        stored == null -> {
                            settings.saveTrustedHostFingerprint(host, config.sshPort, fp)
                            onLog("Trusted new VM host key: $fp")
                            onHostFingerprint(fp)
                            HostKeyRepository.OK
                        }

                        stored == fp -> {
                            onLog("Verified VM host key: $fp")
                            onHostFingerprint(fp)
                            HostKeyRepository.OK
                        }

                        else -> {
                            hostKeyMismatch = HostKeyMismatchException(
                                host = host,
                                port = config.sshPort,
                                expectedFingerprint = stored,
                                receivedFingerprint = fp,
                            )
                            HostKeyRepository.CHANGED
                        }
                    }
                }

                override fun add(hostkey: HostKey?, ui: UserInfo?) = Unit
                override fun remove(host: String?, type: String?) = Unit
                override fun remove(host: String?, type: String?, key: ByteArray?) = Unit
                override fun getKnownHostsRepositoryID(): String = "herdr-tofu-store"
                override fun getHostKey(): Array<HostKey> = emptyArray()
                override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
            }
        }

        if (config.authMethod == SshAuthMethod.PRIVATE_KEY) {
            val keyText = config.privateKey.trim()
            require(keyText.isNotBlank()) { "Paste an OpenSSH/PEM private key or switch to Tailscale SSH" }
            val passphraseBytes = config.passphrase.takeIf { it.isNotEmpty() }?.toByteArray(Charsets.UTF_8)
            jsch.addIdentity("herdr-vm-key", keyText.toByteArray(Charsets.UTF_8), null, passphraseBytes)
        }

        var session: Session? = null
        var channel: ChannelExec? = null

        try {
            session = jsch.getSession(username, host, config.sshPort).apply {
                if (config.authMethod == SshAuthMethod.PASSWORD) {
                    setPassword(config.password)
                }
                val props = Properties()
                props["StrictHostKeyChecking"] = "yes"
                props["PreferredAuthentications"] = when (config.authMethod) {
                    SshAuthMethod.TAILSCALE_SSH -> "none,publickey,keyboard-interactive,password"
                    SshAuthMethod.PRIVATE_KEY -> "publickey,none,keyboard-interactive,password"
                    SshAuthMethod.PASSWORD -> "password,keyboard-interactive,publickey,none"
                }
                setConfig(props)
                // Keep SSH alive across multi-minute agent runs and brief mobile network blips
                serverAliveInterval = 10_000
                serverAliveCountMax = 18
            }

            try {
                session.connect(20_000)
            } catch (e: JSchException) {
                hostKeyMismatch?.let { throw it }
                throw e
            }

            val fingerprint = observedFingerprint
                ?: session.hostKey?.getFingerPrint(jsch)
                ?: "unknown"

            onPhase(BootstrapPhase.RUNNING_AGY)
            val remoteCommand = buildAgyBootstrapCommand(
                prompt = config.prompt.ifBlank { defaultAgyBootstrapPrompt(config.daemonPort) },
                daemonPort = config.daemonPort,
            )

            channel = (session.openChannel("exec") as ChannelExec).apply {
                setCommand(remoteCommand)
                inputStream = null
                setPty(false)
            }

            val stdout = channel.inputStream
            val stderr = channel.errStream
            channel.connect(15_000)

            val fullOutput = StringBuilder()
            val pendingResponseBuffer = StringBuilder()
            var agyConversationId: String? = null

            fun flushResponseLines(flushAll: Boolean) {
                if (flushAll) {
                    val remaining = pendingResponseBuffer.toString().trim()
                    pendingResponseBuffer.clear()
                    if (remaining.isNotBlank()) {
                        onLog(remaining)
                        synchronized(fullOutput) { fullOutput.appendLine(remaining) }
                    }
                    return
                }
                var nlIdx = pendingResponseBuffer.indexOf('\n')
                while (nlIdx >= 0) {
                    val completeLine = pendingResponseBuffer.substring(0, nlIdx).trimEnd('\r')
                    pendingResponseBuffer.delete(0, nlIdx + 1)
                    if (completeLine.isNotBlank()) {
                        onLog(completeLine)
                        synchronized(fullOutput) { fullOutput.appendLine(completeLine) }
                    }
                    nlIdx = pendingResponseBuffer.indexOf('\n')
                }
            }

            val stdoutReader = BufferedReader(InputStreamReader(stdout, Charsets.UTF_8))
            val stderrReader = BufferedReader(InputStreamReader(stderr, Charsets.UTF_8))

            val stderrThread = Thread {
                runCatching {
                    var errLine = stderrReader.readLine()
                    while (errLine != null) {
                        val cleaned = stripAnsiCodes(errLine)
                        synchronized(fullOutput) {
                            fullOutput.appendLine(cleaned)
                        }
                        if (cleaned.isNotBlank()) {
                            onLog("[stderr] $cleaned")
                        }
                        errLine = stderrReader.readLine()
                    }
                }
            }.apply { start() }

            var line = stdoutReader.readLine()
            while (line != null && currentCoroutineContext().isActive) {
                val cleaned = stripAnsiCodes(line)
                val parsed = parseAgyStreamJsonLine(cleaned)
                if (parsed != null) {
                    if (!parsed.conversationId.isNullOrBlank()) {
                        agyConversationId = parsed.conversationId
                    }
                    if (!parsed.textDelta.isNullOrEmpty()) {
                        pendingResponseBuffer.append(parsed.textDelta)
                        flushResponseLines(flushAll = false)
                    }
                    if (!parsed.pairingSourceText.isNullOrBlank()) {
                        synchronized(fullOutput) {
                            fullOutput.appendLine(parsed.pairingSourceText)
                        }
                    }
                    if (parsed.isResult) {
                        flushResponseLines(flushAll = true)
                    }
                    for (displayLine in parsed.displayLines) {
                        if (displayLine.isNotBlank()) {
                            onLog(displayLine)
                        }
                    }
                } else {
                    synchronized(fullOutput) {
                        fullOutput.appendLine(cleaned)
                    }
                    if (cleaned.isNotBlank()) {
                        onLog(cleaned)
                    }
                }
                line = stdoutReader.readLine()
            }
            flushResponseLines(flushAll = true)

            while (!channel.isClosed && currentCoroutineContext().isActive) {
                delay(150)
            }
            stderrThread.join(2_000)

            val exitCode = channel.exitStatus
            debugRecorder?.record("ssh", "ssh_bootstrap_exit", "Remote bootstrap exited with status $exitCode (conv=${agyConversationId ?: "none"})")
            onLog("Remote bootstrap command exited with status $exitCode")

            val combinedOutput = synchronized(fullOutput) { fullOutput.toString() }
            val extracted = extractPairingUriFromOutput(combinedOutput)

            // The script's last step verifies that the latest daemon is running, so a clean non-zero
            // exit means "not verified" even if a pairing URI was printed earlier in the output.
            if (bootstrapExitIsFailure(exitCode)) {
                val debugHint = agyConversationId?.let {
                    " (VM session: ~/.gemini/antigravity-cli/brain/$it/)"
                }.orEmpty()
                throw IOException(
                    "Bootstrap exited with code $exitCode: the latest daemon could not be verified. " +
                        "Check the live output above for details.$debugHint",
                )
            }

            if (extracted != null) {
                val (rawUri, parsedConfig) = extracted
                onLog("Detected pairing URI for ${parsedConfig.host}:${parsedConfig.port}")
                return@withContext SshBootstrapResult(
                    connectionConfig = parsedConfig,
                    rawPairingUri = rawUri,
                    hostFingerprint = fingerprint,
                )
            }

            if (exitCode == -1) {
                val debugHint = agyConversationId?.let {
                    " VM session log: ~/.gemini/antigravity-cli/brain/$it/"
                }.orEmpty()
                val remoteProcess = if (agyConversationId != null) "remote repair agent" else "remote setup"
                throw IOException(
                    "SSH connection dropped before bootstrap finished ($remoteProcess may still be running on $host). " +
                        "Keep the screen awake and tap Retry to connect once the daemon is up.$debugHint",
                )
            }

            // Fallback if agy started daemon with --no-auth and did not print a herdr:// URI
            val fallbackConfig = ConnectionConfig(host = host, port = config.daemonPort, token = "")
            val fallbackUri = "herdr://connect?host=$host&port=${config.daemonPort}"
            onLog("No pairing URI printed; trying $host:${config.daemonPort} directly…")
            SshBootstrapResult(
                connectionConfig = fallbackConfig,
                rawPairingUri = fallbackUri,
                hostFingerprint = fingerprint,
            )
        } finally {
            runCatching { channel?.disconnect() }
            runCatching { session?.disconnect() }
        }
    }

    private fun computeSha256Fingerprint(keyBytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(keyBytes)
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val sb = StringBuilder(((digest.size + 2) / 3) * 4)
        var i = 0
        while (i < digest.size) {
            val b0 = digest[i].toInt() and 0xFF
            val b1 = if (i + 1 < digest.size) digest[i + 1].toInt() and 0xFF else 0
            val b2 = if (i + 2 < digest.size) digest[i + 2].toInt() and 0xFF else 0
            val triple = (b0 shl 16) or (b1 shl 8) or b2
            sb.append(alphabet[(triple ushr 18) and 0x3F])
            sb.append(alphabet[(triple ushr 12) and 0x3F])
            sb.append(if (i + 1 < digest.size) alphabet[(triple ushr 6) and 0x3F] else '=')
            sb.append(if (i + 2 < digest.size) alphabet[triple and 0x3F] else '=')
            i += 3
        }
        return "SHA256:${sb.toString().trimEnd('=')}"
    }
}
