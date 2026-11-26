package com.example

import android.content.Context
import org.apache.sshd.common.io.nio2.Nio2ServiceFactoryFactory
import org.apache.sshd.common.keyprovider.KeyPairProvider
import org.apache.sshd.server.Environment
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.UserAuthFactory
import org.apache.sshd.server.auth.UserAuthNoneFactory
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.auth.password.UserAuthPasswordFactory
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import org.apache.sshd.server.command.CommandFactory
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyPairGenerator
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * In-process embedded SSH server for Android E2E tests.
 *
 * Accepts real JSch connections from [com.example.data.SshBootstrapClient], records the
 * executed remote command, and streams deterministic installer output ending with the target
 * [pairingUri] so the full SSH -> static daemon install -> workspace workflow can be verified
 * end-to-end on the Android emulator.
 */
class FakeSshVmServer(
    context: Context,
    private val expectedUsername: String = "ned",
    private val expectedPassword: String = "test-ssh-pass",
    private val allowNoneAuth: Boolean = true,
    private val pairingUri: String,
    private val daemonPort: Int,
) : AutoCloseable {

    private val executor = Executors.newCachedThreadPool()
    private val server: SshServer

    val receivedCommands = CopyOnWriteArrayList<String>()
    val authenticatedUsers = CopyOnWriteArrayList<String>()

    val host: String = "127.0.0.1"
    val port: Int
        get() = server.port

    init {
        // Apache MINA SSHD checks user.home during setup; Android leaves it unset or "/"
        System.setProperty("user.home", context.cacheDir.absolutePath)

        val keyGen = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
        val hostKeyPair = keyGen.generateKeyPair()

        server = SshServer.setUpDefaultServer().apply {
            setHost("127.0.0.1")
            setPort(0)
            ioServiceFactoryFactory = Nio2ServiceFactoryFactory()
            keyPairProvider = KeyPairProvider.wrap(hostKeyPair)

            val authFactories = mutableListOf<UserAuthFactory>(UserAuthPasswordFactory.INSTANCE)
            if (allowNoneAuth) {
                authFactories.add(0, UserAuthNoneFactory.INSTANCE)
            }
            userAuthFactories = authFactories

            passwordAuthenticator = PasswordAuthenticator { username, password, _ ->
                val ok = username == expectedUsername && password == expectedPassword
                if (ok) authenticatedUsers.add(username)
                ok
            }

            commandFactory = CommandFactory { channel: ChannelSession, command: String ->
                channel.session?.username?.let { authenticatedUsers.add(it) }
                receivedCommands.add(command)
                SimulatedDaemonBootstrapCommand(
                    pairingUri = pairingUri,
                    daemonPort = daemonPort,
                    executor = executor,
                )
            }
        }
    }

    fun start() {
        server.start()
    }

    override fun close() {
        runCatching { server.stop(true) }
        executor.shutdownNow()
    }
}

private class SimulatedDaemonBootstrapCommand(
    private val pairingUri: String,
    private val daemonPort: Int,
    private val executor: java.util.concurrent.ExecutorService,
) : Command {

    private var out: OutputStream? = null
    private var err: OutputStream? = null
    private var callback: ExitCallback? = null

    override fun setInputStream(inStream: InputStream?) = Unit
    override fun setOutputStream(out: OutputStream?) {
        this.out = out
    }
    override fun setErrorStream(err: OutputStream?) {
        this.err = err
    }
    override fun setExitCallback(callback: ExitCallback?) {
        this.callback = callback
    }

    override fun start(channel: ChannelSession?, env: Environment?) {
        executor.submit {
            try {
                val lines = listOf(
                    ">>> Checking latest AgentsTerm daemon (agentsterm-daemon-linux-x86_64)...",
                    ">>> Installed verified binary at /home/ci/.agents-term/bin/agentsterm-daemon",
                    ">>> Waiting for the daemon on http://127.0.0.1:$daemonPort ...",
                    ">>> Daemon v0.3.0 (commit abcdef123456) healthy on 127.0.0.1:$daemonPort (hostname ci-test-vm)",
                    ">>> Running as systemd --user (agentsterm-daemon.service)",
                    pairingUri,
                )
                val stream = out
                if (stream != null) {
                    for (line in lines) {
                        stream.write((line + "\n").toByteArray(Charsets.UTF_8))
                        stream.flush()
                    }
                }
                callback?.onExit(0)
            } catch (t: Throwable) {
                runCatching {
                    err?.write((t.message ?: "Fake SSH command error").toByteArray(Charsets.UTF_8))
                    err?.flush()
                }
                callback?.onExit(1, t.message ?: "error")
            }
        }
    }

    override fun destroy(channel: ChannelSession?) = Unit
}
