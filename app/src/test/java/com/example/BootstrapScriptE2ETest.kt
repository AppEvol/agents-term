package com.example

import com.example.data.buildAgyBootstrapCommand
import com.example.data.decodeHerdrJson
import com.example.data.defaultAgyBootstrapPrompt
import com.example.data.extractPairingUriFromOutput
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Runs the exact remote command the app sends over SSH ([buildAgyBootstrapCommand]) against a
 * sandbox "VM": a temp HOME, a throwaway git remote holding this repo's `daemon/`, and a fake `agy`.
 *
 * Guarantee under test: every SSH bootstrap ends with the latest daemon commit running and verified,
 * or it exits non-zero.
 */
class BootstrapScriptE2ETest {
    private lateinit var tmp: File
    private lateinit var home: File
    private lateinit var origin: File
    private lateinit var localBin: File
    private var port = 0

    private data class Run(val exit: Int, val output: String)

    private fun exec(dir: File, env: Map<String, String>, vararg cmd: String, timeoutSec: Long = 120): Run {
        val pb = ProcessBuilder(*cmd).directory(dir).redirectErrorStream(true)
        pb.environment().apply {
            keys.filter { it.startsWith("AGENTSTERM_") || it.startsWith("GIT_") }.forEach { remove(it) }
            putAll(env)
        }
        val proc = pb.start()
        val out = StringBuilder()
        val reader = Thread { proc.inputStream.bufferedReader().forEachLine { out.appendLine(it) } }.apply { start() }
        check(proc.waitFor(timeoutSec, TimeUnit.SECONDS)) { "timed out: ${cmd.joinToString(" ")}\n$out" }
        reader.join(5_000)
        return Run(proc.exitValue(), out.toString())
    }

    private fun git(vararg args: String): String {
        val run = exec(
            origin, emptyMap(), "git", "-c", "user.name=test", "-c", "user.email=test@example.invalid",
            "-c", "commit.gpgsign=false", *args,
        )
        check(run.exit == 0) { "git ${args.joinToString(" ")} failed:\n${run.output}" }
        return run.output.trim()
    }

    private fun publishCommit(message: String): String {
        File(origin, "CHANGELOG").writeText("$message\n")
        git("add", "-A")
        git("commit", "-q", "-m", message)
        return git("rev-parse", "HEAD")
    }

    private fun hasTool(name: String) = runCatching { ProcessBuilder("sh", "-c", "command -v $name").start().waitFor() == 0 }.getOrDefault(false)

    @Before
    fun setUp() {
        assumeTrue("needs bash, git and python3", listOf("bash", "git", "python3").all { hasTool(it) })
        val daemonSrc = File(System.getProperty("user.dir")).resolve("../daemon").canonicalFile
        assumeTrue("daemon/ sources not found at $daemonSrc", File(daemonSrc, "install.sh").isFile)

        tmp = kotlin.io.path.createTempDirectory("agentsterm-bootstrap-").toFile()
        home = File(tmp, "home").apply { mkdirs() }
        localBin = File(home, ".local/bin").apply { mkdirs() }
        origin = File(tmp, "origin").apply { mkdirs() }
        daemonSrc.copyRecursively(File(origin, "daemon")) { _, e -> throw e }
        File(origin, "daemon/tests").deleteRecursively()
        File(origin, "daemon").walkBottomUp().filter { it.name == "__pycache__" }.forEach { it.deleteRecursively() }
        File(origin, "daemon/install.sh").setExecutable(true)
        File(origin, "daemon/agentsterm-daemon").setExecutable(true)
        git("init", "-q", "-b", "main")
        publishCommit("initial daemon")
        port = ServerSocket(0).use { it.localPort }
    }

    @After
    fun tearDown() {
        if (!::tmp.isInitialized) return
        val pidFile = File(home, ".config/herdr-mobile/daemon.pid")
        if (pidFile.isFile) {
            runCatching { ProcessBuilder("kill", pidFile.readText().trim()).start().waitFor() }
        }
        tmp.deleteRecursively()
    }

    private fun sandboxEnv(withHost: Boolean = true): Map<String, String> = buildMap {
        put("HOME", home.path)
        put("PATH", "${localBin.path}:/usr/local/bin:/usr/bin:/bin")
        put("AGENTSTERM_KEEP_LEGACY", "1") // never touch a real daemon on the machine running the tests
        put("AGENTSTERM_NO_SYSTEMD", "1")
        if (withHost) put("AGENTSTERM_HOST", "127.0.0.1")
    }

    /** Runs the same string the app passes to the SSH exec channel. */
    private fun bootstrap(env: Map<String, String> = sandboxEnv()): Run {
        val command = buildAgyBootstrapCommand(
            prompt = defaultAgyBootstrapPrompt(port, repoUrl = "file://${origin.path}"),
            daemonPort = port,
            repoUrl = "file://${origin.path}",
            ref = "main",
        )
        return exec(home, env, "bash", "-c", command)
    }

    private fun fakeTool(name: String, body: String) {
        File(localBin, name).apply {
            writeText("#!/bin/sh\n$body\n")
            setExecutable(true)
        }
    }

    /**
     * Fake Antigravity CLI. Only a `--print=<prompt>` invocation counts as a bootstrap repair call:
     * the daemon itself also runs `agy models` at startup for the model picker.
     */
    private fun agyScript(onPrompt: String) = "case \"\$*\" in\n  *--print=*) echo invoked >> \"\$HOME/agy-calls\"; $onPrompt ;;\nesac\nexit 0"

    private fun daemonPid() = File(home, ".config/herdr-mobile/daemon.pid").readText().trim()

    private fun runningCommit(token: String): String {
        val conn = URL("http://127.0.0.1:$port/api/info").openConnection() as HttpURLConnection
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.connectTimeout = 5_000
        conn.readTimeout = 5_000
        return conn.inputStream.bufferedReader().use { it.readText() }
            .let { decodeHerdrJson<Map<String, Any?>>(it)["commit"] as String }
    }

    @Test
    fun everyBootstrapEndsWithTheLatestDaemonCommitRunning() {
        fakeTool("agy", agyScript(onPrompt = ":"))
        val commitA = git("rev-parse", "HEAD")

        // Fresh VM: clone, install, start, verify. Antigravity is not needed.
        val first = bootstrap()
        assertEquals(first.output, 0, first.exit)
        val pairing = extractPairingUriFromOutput(first.output)
        assertNotNull(first.output, pairing)
        val config = pairing!!.second
        assertEquals("127.0.0.1", config.host)
        assertEquals(port, config.port)
        assertEquals(commitA, runningCommit(config.token))
        assertFalse(first.output, File(home, "agy-calls").exists())
        val pidA = daemonPid()

        // Bootstrap again with nothing new upstream: still verified, daemon (and its sessions) kept.
        val second = bootstrap()
        assertEquals(second.output, 0, second.exit)
        assertEquals(config, extractPairingUriFromOutput(second.output)!!.second)
        assertEquals(pidA, daemonPid())

        // A new daemon commit is published. The old daemon is healthy, yet the next bootstrap
        // must still move the VM onto the new commit.
        val commitB = publishCommit("daemon change B")
        val third = bootstrap()
        assertEquals(third.output, 0, third.exit)
        assertEquals(commitB, runningCommit(config.token))
        assertNotEquals(pidA, daemonPid())
        assertEquals("token must survive updates", config, extractPairingUriFromOutput(third.output)!!.second)
        assertFalse(File(home, "agy-calls").exists())
    }

    @Test
    fun antigravityRepairsAVmWhereTheInstallerFailsAndTheResultIsVerified() {
        // No AGENTSTERM_HOST and Tailscale reports no address until "agy" brings it up.
        fakeTool("tailscale", "[ -f \"\$HOME/tailscale-up\" ] && [ \"\$1\" = ip ] && echo 127.0.0.1\nexit 0")
        fakeTool("agy", agyScript(onPrompt = "touch \"\$HOME/tailscale-up\"; echo 'Brought Tailscale up.'"))
        val run = bootstrap(sandboxEnv(withHost = false))
        assertEquals(run.output, 0, run.exit)
        assertTrue(run.output, run.output.contains("Tailscale has no IPv4"))
        assertTrue(run.output, run.output.contains("Asking Antigravity to repair"))
        assertEquals(1, File(home, "agy-calls").readLines().size)
        val config = extractPairingUriFromOutput(run.output)!!.second
        assertEquals(git("rev-parse", "HEAD"), runningCommit(config.token))
    }

    @Test
    fun bootstrapFailsLoudlyWhenTheLatestDaemonCannotBeVerified() {
        // Antigravity "finishes" and even prints a pairing URI, but fixes nothing.
        fakeTool("tailscale", "exit 0")
        fakeTool("agy", agyScript(onPrompt = "echo 'All set! herdr://connect?host=127.0.0.1&port=$port&token=made-up'"))
        val run = bootstrap(sandboxEnv(withHost = false))
        assertEquals(run.output, 3, run.exit)
        assertTrue(com.example.data.bootstrapExitIsFailure(run.exit))
        assertFalse(File(home, ".config/herdr-mobile/daemon.pid").exists())

        // Without agy there is nothing to repair with: a distinct, explained failure.
        File(localBin, "agy").delete()
        val noAgy = bootstrap(sandboxEnv(withHost = false))
        assertEquals(noAgy.output, 127, noAgy.exit)
        assertTrue(noAgy.output.contains("could not be installed"))
    }
}
