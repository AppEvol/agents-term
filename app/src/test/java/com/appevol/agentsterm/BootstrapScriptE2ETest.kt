package com.appevol.agentsterm

import com.appevol.agentsterm.data.buildDaemonBootstrapCommand
import com.appevol.agentsterm.data.extractPairingUriFromOutput
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs the exact remote command the app sends over SSH ([buildDaemonBootstrapCommand]) against a
 * sandbox "VM" (temp HOME + local HTTP release server) to verify binary download, SHA-256
 * verification, idempotent skip-download, atomic upgrade, checksum mismatch rejection, and
 * unhealthy-daemon failure propagation.
 */
class BootstrapScriptE2ETest {
    private lateinit var tmp: File
    private lateinit var home: File
    private lateinit var releaseDir: File
    private lateinit var server: HttpServer
    private lateinit var assetName: String
    private var port = 0
    private var releaseBaseUrl = ""
    private val binaryDownloads = AtomicInteger(0)

    private data class Run(val exit: Int, val output: String)

    private fun exec(dir: File, env: Map<String, String>, vararg cmd: String, timeoutSec: Long = 60): Run {
        val pb = ProcessBuilder(*cmd).directory(dir).redirectErrorStream(true)
        pb.environment().apply {
            keys.filter { it.startsWith("AGENTSTERM_") }.forEach { remove(it) }
            putAll(env)
        }
        val proc = pb.start()
        val out = StringBuilder()
        val reader = Thread { proc.inputStream.bufferedReader().forEachLine { out.appendLine(it) } }.apply { start() }
        check(proc.waitFor(timeoutSec, TimeUnit.SECONDS)) { "timed out: ${cmd.joinToString(" ")}\n$out" }
        reader.join(5_000)
        return Run(proc.exitValue(), out.toString())
    }

    private fun hasTool(name: String): Boolean {
        val code = runCatching { ProcessBuilder("sh", "-c", "command -v $name").start().waitFor() }
        return code.getOrDefault(-1) == 0
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun publishBinary(versionLabel: String, exitCode: Int = 0, badChecksum: Boolean = false) {
        val script = """
            #!/usr/bin/env sh
            echo ">>> Fake daemon $versionLabel install args: $*"
            if [ "$exitCode" -ne 0 ]; then
              echo "ERROR: daemon failed health check" >&2
              exit $exitCode
            fi
            echo "herdr://connect?host=127.0.0.1&port=$port&token=tok_$versionLabel"
            exit 0
        """.trimIndent() + "\n"
        val bytes = script.toByteArray(Charsets.UTF_8)
        File(releaseDir, assetName).writeBytes(bytes)
        val sum = if (badChecksum) "0".repeat(64) else sha256Hex(bytes)
        File(releaseDir, "$assetName.sha256").writeText("$sum  $assetName\n")
    }

    @Before
    fun setUp() {
        assumeTrue("Linux required", System.getProperty("os.name").orEmpty().contains("Linux", ignoreCase = true))
        assumeTrue("needs sh and curl/wget", hasTool("sh") && (hasTool("curl") || hasTool("wget")))
        assumeTrue("needs sha256sum or shasum", hasTool("sha256sum") || hasTool("shasum"))

        val archOut = exec(File("."), emptyMap(), "uname", "-m").output.trim()
        val arch = when (archOut) {
            "x86_64", "amd64" -> "x86_64"

            "aarch64", "arm64" -> "aarch64"

            else -> {
                assumeTrue("Unsupported arch $archOut", false)
                return
            }
        }
        assetName = "agentsterm-daemon-linux-$arch"

        tmp = kotlin.io.path.createTempDirectory("agentsterm-bootstrap-").toFile()
        home = File(tmp, "home").apply { mkdirs() }
        // Seed standard Ubuntu/Debian ~/.profile (which references unset $BASH_VERSION under dash)
        // plus a missing sourced file (like a stale `. "$HOME/.cargo/env"`), verifying POSIX
        // `command .` before `set -eu` never aborts bootstrap with exit 2.
        File(home, ".profile").writeText(
            """
            if [ -n "${'$'}BASH_VERSION" ]; then
                if [ -f "${'$'}HOME/.bashrc" ]; then
                    . "${'$'}HOME/.bashrc"
                fi
            fi
            . "${'$'}HOME/.nonexistent-env"
            """.trimIndent() + "\n",
        )
        releaseDir = File(tmp, "release").apply { mkdirs() }
        port = ServerSocket(0).use { it.localPort }

        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                val name = ex.requestURI.path.trimStart('/')
                val file = File(releaseDir, name)
                if (!file.isFile) {
                    ex.sendResponseHeaders(404, -1)
                    ex.close()
                    return@createContext
                }
                if (name == assetName) {
                    binaryDownloads.incrementAndGet()
                }
                val bytes = file.readBytes()
                ex.sendResponseHeaders(200, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
            start()
        }
        releaseBaseUrl = "http://127.0.0.1:${server.address.port}"
    }

    @After
    fun tearDown() {
        if (::server.isInitialized) server.stop(0)
        if (::tmp.isInitialized) tmp.deleteRecursively()
    }

    private fun bootstrap(): Run {
        val cmd = buildDaemonBootstrapCommand(daemonPort = port, releaseBaseUrl = releaseBaseUrl)
        val env = mapOf(
            "HOME" to home.path,
            "PATH" to "/usr/local/bin:/usr/bin:/bin",
        )
        return exec(home, env, "sh", "-c", cmd)
    }

    @Test
    fun downloadsVerifiesSkipsWhenCurrentAndUpgradesOnNewRelease() {
        publishBinary("v1")
        val first = bootstrap()
        assertEquals("first bootstrap failed:\n${first.output}", 0, first.exit)
        assertEquals(1, binaryDownloads.get())
        assertEquals("tok_v1", extractPairingUriFromOutput(first.output)?.second?.token)

        // Second run with same SHA-256 must skip downloading the binary.
        val second = bootstrap()
        assertEquals("second bootstrap failed:\n${second.output}", 0, second.exit)
        assertEquals("should skip binary download when SHA-256 matches", 1, binaryDownloads.get())
        assertTrue(second.output.contains("already matches release SHA-256"))

        // Publishing v2 triggers a fresh download and atomic replacement.
        publishBinary("v2")
        val third = bootstrap()
        assertEquals("upgrade bootstrap failed:\n${third.output}", 0, third.exit)
        assertEquals(2, binaryDownloads.get())
        assertEquals("tok_v2", extractPairingUriFromOutput(third.output)?.second?.token)
    }

    @Test
    fun rejectsChecksumMismatchAndPropagatesUnhealthyDaemonExitCode() {
        publishBinary("good")
        assertEquals(0, bootstrap().exit)
        val installedBefore = File(home, ".agents-term/bin/agentsterm-daemon").readText()

        publishBinary("corrupt", badChecksum = true)
        val mismatch = bootstrap()
        assertNotEquals("checksum mismatch must fail", 0, mismatch.exit)
        assertTrue(mismatch.output.contains("SHA-256 mismatch"))
        // Existing working binary must not be overwritten on checksum failure.
        assertEquals(installedBefore, File(home, ".agents-term/bin/agentsterm-daemon").readText())

        publishBinary("unhealthy", exitCode = 4)
        val unhealthy = bootstrap()
        assertEquals(4, unhealthy.exit)
        assertNotNull(unhealthy.output)
    }
}
