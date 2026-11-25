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
 * Connects to a Tailscale VM over SSH to download, verify, and install the static Rust AgentsTerm
 * daemon binary, streaming live progress back to the mobile UI.
 */
class SshBootstrapClient(
    private val settings: SettingsStore,
    private val debugRecorder: DebugRecorder? = null,
) {
    private class HostKeyTracker {
        var observedFingerprint: String? = null
        var mismatch: HostKeyMismatchException? = null
    }

    suspend fun bootstrapVm(
        config: SshBootstrapConfig,
        onPhase: (BootstrapPhase) -> Unit,
        onHostFingerprint: (String) -> Unit,
        onLog: (String) -> Unit,
    ): SshBootstrapResult = withContext(Dispatchers.IO) {
        val host = cleanHost(config.host)
        val username = config.username.trim()
        validateConfig(host, username, config)

        onPhase(BootstrapPhase.CONNECTING_SSH)
        debugRecorder?.record(
            "ssh",
            "ssh_connect_start",
            "Connecting to $username@$host:${config.sshPort} (auth=${config.authMethod.storageKey})",
        )
        onLog("Connecting to $username@$host:${config.sshPort} via SSH…")

        val tracker = HostKeyTracker()
        val jsch = createConfiguredJsch(host, config, tracker, onHostFingerprint, onLog)
        var session: Session? = null
        var channel: ChannelExec? = null

        try {
            session = openSession(jsch, username, host, config, tracker)
            val fingerprint = tracker.observedFingerprint
                ?: session.hostKey?.getFingerPrint(jsch)
                ?: "unknown"

            onPhase(BootstrapPhase.INSTALLING_DAEMON)
            val remoteCommand = buildDaemonBootstrapCommand(daemonPort = config.daemonPort)
            val execResult = runRemoteCommand(session, remoteCommand, onLog) { channel = it }
            interpretResult(host, config.daemonPort, fingerprint, execResult, onLog)
        } finally {
            runCatching { channel?.disconnect() }
            runCatching { session?.disconnect() }
        }
    }

    private fun validateConfig(host: String, username: String, config: SshBootstrapConfig) {
        require(isValidHost(host)) { "Invalid Tailscale host or IP address" }
        require(isValidUsername(username)) { "Invalid SSH username" }
        require(config.sshPort in 1..65535) { "SSH port must be between 1 and 65535" }
        require(config.daemonPort in 1..65535) { "Daemon port must be between 1 and 65535" }
    }

    private fun createConfiguredJsch(
        host: String,
        config: SshBootstrapConfig,
        tracker: HostKeyTracker,
        onHostFingerprint: (String) -> Unit,
        onLog: (String) -> Unit,
    ): JSch = JSch().apply {
        // Pre-authentication Trust-On-First-Use (TOFU) host key verifier:
        // rejects changed host keys before credentials are transmitted.
        hostKeyRepository = object : HostKeyRepository {
            override fun check(checkHost: String?, key: ByteArray?): Int {
                if (key == null) return HostKeyRepository.NOT_INCLUDED
                val fp = computeSha256Fingerprint(key)
                tracker.observedFingerprint = fp
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
                        tracker.mismatch = HostKeyMismatchException(
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

        if (config.authMethod == SshAuthMethod.PRIVATE_KEY) {
            val keyText = config.privateKey.trim()
            require(keyText.isNotBlank()) { "Paste an OpenSSH/PEM private key or switch to Tailscale SSH" }
            val passphraseBytes = config.passphrase.takeIf { it.isNotEmpty() }?.toByteArray(Charsets.UTF_8)
            addIdentity("herdr-vm-key", keyText.toByteArray(Charsets.UTF_8), null, passphraseBytes)
        }
    }

    private fun openSession(
        jsch: JSch,
        username: String,
        host: String,
        config: SshBootstrapConfig,
        tracker: HostKeyTracker,
    ): Session {
        val session = jsch.getSession(username, host, config.sshPort).apply {
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
            serverAliveInterval = 10_000
            serverAliveCountMax = 18
        }
        try {
            session.connect(20_000)
        } catch (e: JSchException) {
            tracker.mismatch?.let { throw it }
            throw e
        }
        return session
    }

    private suspend fun runRemoteCommand(
        session: Session,
        remoteCommand: String,
        onLog: (String) -> Unit,
        registerChannel: (ChannelExec) -> Unit,
    ): Pair<Int, String> {
        val channel = (session.openChannel("exec") as ChannelExec).apply {
            setCommand(remoteCommand)
            inputStream = null
            setPty(false)
        }
        registerChannel(channel)

        val stdoutReader = BufferedReader(InputStreamReader(channel.inputStream, Charsets.UTF_8))
        val stderrReader = BufferedReader(InputStreamReader(channel.errStream, Charsets.UTF_8))
        channel.connect(15_000)

        val fullOutput = StringBuilder()
        val stderrThread = Thread {
            runCatching {
                var errLine = stderrReader.readLine()
                while (errLine != null) {
                    val cleaned = stripAnsiCodes(errLine)
                    synchronized(fullOutput) { fullOutput.appendLine(cleaned) }
                    if (cleaned.isNotBlank()) onLog("[stderr] $cleaned")
                    errLine = stderrReader.readLine()
                }
            }
        }.apply { start() }

        var line = stdoutReader.readLine()
        while (line != null && currentCoroutineContext().isActive) {
            val cleaned = stripAnsiCodes(line)
            synchronized(fullOutput) { fullOutput.appendLine(cleaned) }
            if (cleaned.isNotBlank()) onLog(cleaned)
            line = stdoutReader.readLine()
        }

        while (!channel.isClosed && currentCoroutineContext().isActive) {
            delay(150)
        }
        stderrThread.join(2_000)

        val exitCode = channel.exitStatus
        debugRecorder?.record("ssh", "ssh_bootstrap_exit", "Remote bootstrap exited with status $exitCode")
        onLog("Remote bootstrap command exited with status $exitCode")
        return exitCode to synchronized(fullOutput) { fullOutput.toString() }
    }

    private fun interpretResult(
        host: String,
        daemonPort: Int,
        fingerprint: String,
        execResult: Pair<Int, String>,
        onLog: (String) -> Unit,
    ): SshBootstrapResult {
        val (exitCode, combinedOutput) = execResult
        if (bootstrapExitIsFailure(exitCode)) {
            throw IOException(
                "Bootstrap exited with code $exitCode: the latest daemon could not be verified. " +
                    "Check the live output above for details.",
            )
        }

        extractPairingUriFromOutput(combinedOutput)?.let { (rawUri, parsedConfig) ->
            onLog("Detected pairing URI for ${parsedConfig.host}:${parsedConfig.port}")
            return SshBootstrapResult(
                connectionConfig = parsedConfig,
                rawPairingUri = rawUri,
                hostFingerprint = fingerprint,
            )
        }

        if (exitCode == -1) {
            throw IOException(
                "SSH connection dropped before bootstrap finished (remote setup may still be running on $host). " +
                    "Keep the screen awake and tap Retry to connect once the daemon is up.",
            )
        }

        val fallbackConfig = ConnectionConfig(host = host, port = daemonPort, token = "")
        val fallbackUri = "herdr://connect?host=$host&port=$daemonPort"
        onLog("No pairing URI printed; trying $host:$daemonPort directly…")
        return SshBootstrapResult(
            connectionConfig = fallbackConfig,
            rawPairingUri = fallbackUri,
            hostFingerprint = fingerprint,
        )
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
