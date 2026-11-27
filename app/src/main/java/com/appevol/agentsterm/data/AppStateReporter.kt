package com.appevol.agentsterm.data

import kotlinx.coroutines.CancellationException

/**
 * App state reporter: answers inspection commands that agents on the VM send through the daemon.
 *
 * Wire protocol (over the existing daemon WebSocket):
 *  - app -> daemon, after every connect and whenever the setting changes:
 *      {"type":"app_hello","reporter":{"enabled":true,"appVersion":"…","device":"…","commands":[…]}}
 *  - daemon -> app: {"type":"app_state_request","requestId":"…","command":"logs","args":{…}}
 *  - app -> daemon: {"type":"app_state_response","requestId":"…","ok":true,"result":{…}}
 *                or {"type":"app_state_response","requestId":"…","ok":false,"error":"…"}
 *
 * Inspection commands are read-only and gated on the user's "Allow VM agents to inspect the app"
 * setting. Control commands ([AppUiController.CONTROL_COMMANDS]: tap, swipe, type…) also need
 * "Allow VM agents to control the app". Every text returned goes through [sanitizeDebugText].
 */
interface AppStateRequestHandler {
    /** JSON text of the `app_hello` frame announcing the reporter's current status. */
    fun helloJson(): String

    /** Handles one request and returns the JSON text of its `app_state_response` frame. */
    suspend fun handleRequest(requestId: String, command: String, args: Map<String, Any?>): String
}

/** Thrown by [AppStateSources] to send a readable error back to the agent. */
class AppStateCommandException(message: String) : Exception(message)

/** Android-dependent data sources, injected so the reporter stays unit-testable on the JVM. */
interface AppStateSources {
    /** UI/navigation flags and non-secret settings. Values must be JSON-encodable by [toJsonText]. */
    fun uiState(): Map<String, Any?>

    /** Sanitized JSON text of the workspace (connection, tabs, recent messages). */
    fun workspaceJson(): String

    suspend fun logcat(lines: Int): String

    fun lastCrash(): String?

    /** PNG of the current app window, Base64. Throws [AppStateCommandException] if unavailable. */
    suspend fun screenshotPngBase64(): String

    /** Uploads a full debug bundle to the VM and returns details about where it landed. */
    suspend fun uploadDump(note: String?, includeScreenshot: Boolean): Map<String, Any?>
}

class AppStateReporter(
    private val recorder: DebugRecorder,
    private val sources: AppStateSources,
    private val isEnabled: () -> Boolean,
    private val appVersion: String,
    private val device: String,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    /** Reads and drives the on-screen UI; null leaves the UI commands out. */
    private val ui: AppUiController? = null,
    private val isControlEnabled: () -> Boolean = { false },
) : AppStateRequestHandler {

    private val commands: List<String> =
        if (ui == null) COMMANDS else COMMANDS + AppUiController.READ_COMMANDS + AppUiController.CONTROL_COMMANDS

    override fun helloJson(): String = toJsonText(
        mapOf(
            "type" to "app_hello",
            "reporter" to mapOf(
                "enabled" to isEnabled(),
                "appVersion" to appVersion,
                "device" to device,
                "commands" to commands,
                // Older daemons ignore these two fields.
                "controlEnabled" to (ui != null && isEnabled() && isControlEnabled()),
                "controlCommands" to if (ui == null) emptyList() else AppUiController.CONTROL_COMMANDS,
            ),
        ),
    )

    override suspend fun handleRequest(requestId: String, command: String, args: Map<String, Any?>): String {
        val startedAt = clockMs()
        val outcome: Result<Any?> = try {
            if (!isEnabled()) {
                throw AppStateCommandException(
                    "Remote inspection is disabled in the app (Settings > Debug sharing).",
                )
            }
            if (ui != null && command in AppUiController.CONTROL_COMMANDS && !isControlEnabled()) {
                throw AppStateCommandException(
                    "Remote control is disabled in the app (Settings > Debug sharing > Allow VM agents to control the app).",
                )
            }
            Result.success(dispatch(command, args))
        } catch (e: CancellationException) {
            throw e
        } catch (e: AppStateCommandException) {
            Result.failure(e)
        } catch (e: Throwable) {
            Result.failure(AppStateCommandException("${e::class.java.simpleName}: ${e.message ?: "no message"}"))
        }

        // Polling commands are not recorded: `logs --follow` would otherwise log its own polls forever.
        if (command !in UNRECORDED_COMMANDS) {
            recorder.record(
                category = "app_state",
                event = if (outcome.isSuccess) "app_state_command" else "app_state_command_failed",
                detail = buildString {
                    append("VM agent ran '$command' (${clockMs() - startedAt}ms)")
                    outcome.exceptionOrNull()?.let { append(": ${it.message}") }
                },
            )
        }

        return outcome.fold(
            onSuccess = { result ->
                toJsonText(
                    mapOf(
                        "type" to "app_state_response",
                        "requestId" to requestId,
                        "ok" to true,
                        "result" to result,
                    ),
                )
            },
            onFailure = { err ->
                toJsonText(
                    mapOf(
                        "type" to "app_state_response",
                        "requestId" to requestId,
                        "ok" to false,
                        "error" to sanitizeDebugText(err.message ?: "Unknown error"),
                    ),
                )
            },
        )
    }

    private suspend fun dispatch(command: String, args: Map<String, Any?>): Any? = when (command) {
        "ping" -> mapOf("pong" to true, "ts" to clockMs(), "appVersion" to appVersion)

        "state" -> mapOf(
            "capturedAt" to clockMs(),
            "appVersion" to appVersion,
            "device" to device,
            "ui" to sources.uiState(),
            "workspace" to RawJson(sources.workspaceJson()),
        )

        "logs" -> logs(args)

        "logcat" -> {
            val lines = args.intArg("lines", default = 400).coerceIn(50, 2000)
            mapOf("lines" to lines, "text" to sources.logcat(lines))
        }

        "crash" -> {
            val text = sources.lastCrash()?.let { sanitizeDebugText(it) }
            mapOf("hasCrash" to (text != null), "text" to text)
        }

        "screenshot" -> mapOf("capturedAt" to clockMs(), "pngBase64" to sources.screenshotPngBase64())

        "dump" -> sources.uploadDump(
            note = args["note"] as? String,
            includeScreenshot = args["includeScreenshot"] as? Boolean ?: true,
        )

        else -> if (ui != null && command in commands) {
            ui.handle(command, args)
        } else {
            throw AppStateCommandException(
                "Unknown command '$command'. Supported: ${commands.joinToString(", ")}",
            )
        }
    }

    private fun logs(args: Map<String, Any?>): Map<String, Any?> {
        val all = recorder.snapshot()
        val limit = args.intArg("limit", default = 100).coerceIn(1, 600)
        val sinceTs = (args["sinceTs"] as? Number)?.toLong()
        val categories = (args["categories"] as? List<*>)?.mapNotNull { it as? String }?.toSet()
            ?.takeIf { it.isNotEmpty() }
        val contains = (args["contains"] as? String)?.takeIf { it.isNotBlank() }
        val matching = all.filter { ev ->
            (sinceTs == null || ev.ts >= sinceTs) &&
                (categories == null || ev.category in categories) &&
                (
                    contains == null ||
                        ev.event.contains(contains, ignoreCase = true) ||
                        ev.detail.contains(contains, ignoreCase = true) ||
                        (ev.payload?.contains(contains, ignoreCase = true) ?: false)
                    )
        }
        val returned = matching.takeLast(limit)
        return mapOf(
            "bufferedCount" to all.size,
            "matchedCount" to matching.size,
            "returnedCount" to returned.size,
            "events" to returned.map { RawJson(encodeHerdrJson(it)) },
        )
    }

    private fun Map<String, Any?>.intArg(name: String, default: Int): Int = (this[name] as? Number)?.toInt() ?: default

    companion object {
        /** Inspection commands; the UI commands of [AppUiController] are added when it is present. */
        val COMMANDS = listOf("ping", "state", "logs", "logcat", "crash", "screenshot", "dump")

        // `ui` is polled too (agents_term_app_cli wait).
        private val UNRECORDED_COMMANDS = setOf("ping", "logs", "ui")
    }
}

/** Pre-encoded JSON that [toJsonText] embeds verbatim. */
class RawJson(val json: String)

private val jsonStringAdapter = HerdrMoshi.adapter(String::class.java)

/**
 * Encodes maps, lists, strings, numbers, booleans, null and [RawJson] to JSON text.
 *
 * Moshi's untyped (`Any`) adapters read every number as a Double (timestamps come back as
 * 1.7E12), so app-state payloads are put together here and existing typed encoders are embedded
 * via [RawJson].
 */
fun toJsonText(value: Any?): String = buildString { appendJson(value) }

private fun StringBuilder.appendJson(value: Any?) {
    when (value) {
        null -> append("null")

        is RawJson -> append(value.json)

        is String -> append(jsonStringAdapter.toJson(value))

        is Boolean, is Int, is Long, is Short, is Byte -> append(value.toString())

        is Double, is Float -> {
            val d = value.toDouble()
            when {
                !d.isFinite() -> append("null")
                d == Math.rint(d) && kotlin.math.abs(d) < 9.0e15 -> append(d.toLong().toString())
                else -> append(d.toString())
            }
        }

        is Number -> append(value.toString())

        is Map<*, *> -> {
            append('{')
            var first = true
            for ((k, v) in value) {
                if (!first) append(',')
                first = false
                append(jsonStringAdapter.toJson(k.toString()))
                append(':')
                appendJson(v)
            }
            append('}')
        }

        is Iterable<*> -> {
            append('[')
            var first = true
            for (item in value) {
                if (!first) append(',')
                first = false
                appendJson(item)
            }
            append(']')
        }

        is Array<*> -> appendJson(value.asList())

        is Enum<*> -> append(jsonStringAdapter.toJson(value.name))

        else -> append(jsonStringAdapter.toJson(value.toString()))
    }
}
