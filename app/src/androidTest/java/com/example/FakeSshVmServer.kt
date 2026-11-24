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
 * executed remote command, and streams realistic `agy --output-format stream-json` NDJSON
 * bootstrap output ending with the target [pairingUri] so the full SSH -> agy -> daemon
 * workflow can be verified end-to-end on the Android emulator.
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
                SimulatedAgyBootstrapCommand(
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

private class SimulatedAgyBootstrapCommand(
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
                    ">>> Invoking Antigravity (agy --dangerously-skip-permissions) on ci-test-vm...",
                    """{"event":"init","conversation_id":"e2e-ssh-conv-1234","init":{"cwd":"/home/ci"}}""",
                    """{"event":"step_update","step_update":{"state":"ACTIVE","step_type":"tool","tool_name":"run_command","tool_info":{"name":"run_command","parameters":{"toolSummary":"Verify AgentsTerm daemon on port $daemonPort"}}}}""",
                    """{"event":"step_update","step_update":{"state":"DONE","step_type":"tool","tool_name":"run_command","duration_seconds":0.4,"tool_info":{"output":"Daemon healthy on 127.0.0.1:$daemonPort\n$pairingUri\n"}}}""",
                    """{"event":"step_update","step_update":{"state":"ACTIVE","step_type":"agent_response","text_delta":"AgentsTerm daemon is running and verified.\n$pairingUri\n"}}""",
                    """{"event":"result","result":{"conversation_id":"e2e-ssh-conv-1234","status":"SUCCESS","response":"AgentsTerm daemon ready:\n$pairingUri\n","duration_seconds":0.9,"num_turns":1}}""",
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
