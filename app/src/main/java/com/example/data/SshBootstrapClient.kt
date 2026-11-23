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
 * Connects to a Tailscale VM over SSH and invokes `agy --dangerously-skip-permissions`
 * so the VM's Antigravity agent bootstraps the Herdr daemon environment autonomously.
 */
class SshBootstrapClient(
    private val settings: SettingsStore,
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
            if (keyText.isBlank()) {
                throw IllegalArgumentException("Paste an OpenSSH/PEM private key or switch to Tailscale SSH")
            }
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
                serverAliveInterval = 15_000
                serverAliveCountMax = 6
            }

            try {
                session.connect(15_000)
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
            channel.connect(10_000)

            val fullOutput = StringBuilder()
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
                synchronized(fullOutput) {
                    fullOutput.appendLine(cleaned)
                }
                if (cleaned.isNotBlank()) {
                    onLog(cleaned)
                }
                line = stdoutReader.readLine()
            }

            while (!channel.isClosed && currentCoroutineContext().isActive) {
                delay(150)
            }
            stderrThread.join(2_000)

            val exitCode = channel.exitStatus
            onLog("Remote bootstrap command exited with status $exitCode")

            val combinedOutput = synchronized(fullOutput) { fullOutput.toString() }
            val extracted = extractPairingUriFromOutput(combinedOutput)

            if (extracted != null) {
                val (rawUri, parsedConfig) = extracted
                onLog("Detected pairing URI for ${parsedConfig.host}:${parsedConfig.port}")
                return@withContext SshBootstrapResult(
                    connectionConfig = parsedConfig,
                    rawPairingUri = rawUri,
                    hostFingerprint = fingerprint,
                )
            }

            if (exitCode != 0) {
                throw IOException("Bootstrap exited with code $exitCode. Check the live output above for details.")
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
