package com.example.data

import com.example.BuildConfig

/**
 * Minimum VM daemon `protocolVersion` expected by this Android build.
 * Keep in lockstep with `PROTOCOL_VERSION` in `daemon-rs/src/config.rs`.
 */
const val EXPECTED_DAEMON_PROTOCOL_VERSION = 1

const val OUTDATED_DAEMON_ENDPOINT_ERROR =
    "Your VM daemon is out of date and does not support this feature. " +
        "Update it via Settings > Edit VM Connection > SSH Auto-Setup, " +
        "or run `sh daemon-rs/install.sh` on your VM."

private const val SHORT_COMMIT_LENGTH = 7

enum class DaemonCompatibilityStatus {
    UNKNOWN,
    COMPATIBLE,
    OUTDATED,
}

data class DaemonCompatibilityInfo(
    val status: DaemonCompatibilityStatus,
    val appVersionLabel: String,
    val daemonVersionLabel: String,
    val daemonProtocolVersion: Int?,
    val expectedProtocolVersion: Int,
) {
    val isOutdated: Boolean
        get() = status == DaemonCompatibilityStatus.OUTDATED
}

fun ServerInfo.shortCommitOrHash(): String? {
    val raw = commit?.trim()?.takeIf { it.isNotEmpty() }
        ?: buildHash?.trim()?.takeIf { it.isNotEmpty() }
        ?: return null
    return raw.take(SHORT_COMMIT_LENGTH)
}

fun ServerInfo.displayVersionLabel(): String {
    val cleanVersion = version.trim().removePrefix("v")
    val shortCommit = shortCommitOrHash()
    val base = when {
        cleanVersion.isNotEmpty() && shortCommit != null -> "v$cleanVersion ($shortCommit)"
        cleanVersion.isNotEmpty() -> "v$cleanVersion"
        shortCommit != null -> shortCommit
        else -> "unknown"
    }
    return if (protocolVersion != null) "$base · proto $protocolVersion" else base
}

/**
 * Stable identifier for a connected daemon instance, used to key dismissal of the outdated-daemon
 * banner so upgrading or switching VMs re-evaluates the warning cleanly.
 */
fun ServerInfo.daemonIdentityKey(host: String, port: Int): String = listOf(
    "$host:$port",
    version.trim(),
    (protocolVersion ?: 0).toString(),
    shortCommitOrHash().orEmpty(),
).joinToString("|")

/**
 * Compares the connected VM daemon's advertised protocol version against what this APK expects.
 *
 * Daemons predating `protocolVersion` report `null` (treated as `0`) and are flagged as outdated.
 * Daemons advertising a newer protocol version than this APK remain compatible without warning.
 */
fun evaluateDaemonCompatibility(
    serverInfo: ServerInfo?,
    appVersionName: String = BuildConfig.VERSION_NAME,
    expectedProtocolVersion: Int = EXPECTED_DAEMON_PROTOCOL_VERSION,
): DaemonCompatibilityInfo {
    val cleanAppVersion = appVersionName.trim().removePrefix("v").ifEmpty { "dev" }
    val appLabel = "v$cleanAppVersion"
    if (serverInfo == null) {
        return DaemonCompatibilityInfo(
            status = DaemonCompatibilityStatus.UNKNOWN,
            appVersionLabel = appLabel,
            daemonVersionLabel = "unknown",
            daemonProtocolVersion = null,
            expectedProtocolVersion = expectedProtocolVersion,
        )
    }

    val reportedProtocol = serverInfo.protocolVersion ?: 0
    val status = if (reportedProtocol < expectedProtocolVersion) {
        DaemonCompatibilityStatus.OUTDATED
    } else {
        DaemonCompatibilityStatus.COMPATIBLE
    }
    return DaemonCompatibilityInfo(
        status = status,
        appVersionLabel = appLabel,
        daemonVersionLabel = serverInfo.displayVersionLabel(),
        daemonProtocolVersion = serverInfo.protocolVersion,
        expectedProtocolVersion = expectedProtocolVersion,
    )
}

/**
 * True when an HTTP 404 response indicates the VM daemon does not recognize the requested route
 * (as opposed to a valid resource-not-found error like `"Tab not found"`).
 */
fun isUnknownEndpointResponse(statusCode: Int, rawError: String?): Boolean {
    if (statusCode != HTTP_NOT_FOUND) return false
    val normalized = rawError?.trim().orEmpty()
    return normalized.contains("unknown endpoint", ignoreCase = true) ||
        normalized.equals("HTTP 404: Not Found", ignoreCase = true)
}

fun formatDaemonRequestError(err: Throwable, fallback: String): String {
    if (err.isUnknownEndpointError) {
        return OUTDATED_DAEMON_ENDPOINT_ERROR
    }
    return err.message?.trim()?.takeIf { it.isNotEmpty() } ?: fallback
}
