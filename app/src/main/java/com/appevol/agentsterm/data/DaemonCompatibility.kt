package com.appevol.agentsterm.data

import com.appevol.agentsterm.BuildConfig

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
    BUILD_MISMATCH,
}

data class DaemonCompatibilityInfo(
    val status: DaemonCompatibilityStatus,
    val appVersionLabel: String,
    val daemonVersionLabel: String,
    val daemonProtocolVersion: Int?,
    val expectedProtocolVersion: Int,
    val daemonBuildHash: String? = null,
    val expectedBuildHash: String = "",
    val expectedReleaseTag: String = "",
    val supportsSelfUpdate: Boolean = false,
    val rollbackAvailable: Boolean = false,
    val previousBuildHash: String? = null,
    val lastAutoRollbackError: String? = null,
) {
    val isProtocolOutdated: Boolean
        get() = status == DaemonCompatibilityStatus.OUTDATED

    val isBuildMismatch: Boolean
        get() = status == DaemonCompatibilityStatus.BUILD_MISMATCH

    val isOutdated: Boolean
        get() = isProtocolOutdated || isBuildMismatch

    val isPinnedBuild: Boolean
        get() = expectedReleaseTag.isNotBlank()

    val targetUpdateVersion: String
        get() = expectedReleaseTag.trim().ifEmpty { "latest" }

    val targetExpectedHash: String?
        get() = if (isPinnedBuild) expectedBuildHash.trim().takeIf { it.isNotEmpty() } else null

    val shortExpectedBuildHash: String?
        get() = expectedBuildHash.trim().takeIf { it.isNotEmpty() }?.take(SHORT_COMMIT_LENGTH)

    val shortPreviousBuildHash: String?
        get() = previousBuildHash?.trim()?.takeIf { it.isNotEmpty() }?.take(SHORT_COMMIT_LENGTH)
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
    buildHash?.trim().orEmpty(),
).joinToString("|")

/**
 * Compares the connected VM daemon's advertised protocol version and build hash against what this
 * APK expects.
 *
 * - Daemons predating `protocolVersion` report `null` (treated as `0`) and are always flagged as
 *   [DaemonCompatibilityStatus.OUTDATED].
 * - Daemons advertising a newer `protocolVersion` than this APK remain compatible without warning
 *   (avoiding unwanted downgrades).
 * - On pinned release/preview APKs (`expectedReleaseTag` non-empty) with matching protocol version,
 *   a different `buildHash` is flagged as [DaemonCompatibilityStatus.BUILD_MISMATCH]. Unpinned
 *   local APKs skip build-hash mismatch prompts while still allowing manual updates in Settings.
 */
fun evaluateDaemonCompatibility(
    serverInfo: ServerInfo?,
    appVersionName: String = BuildConfig.VERSION_NAME,
    expectedProtocolVersion: Int = EXPECTED_DAEMON_PROTOCOL_VERSION,
    expectedBuildHash: String = BuildConfig.EXPECTED_DAEMON_BUILD_HASH,
    expectedReleaseTag: String = BuildConfig.EXPECTED_DAEMON_RELEASE_TAG,
): DaemonCompatibilityInfo {
    val cleanAppVersion = appVersionName.trim().removePrefix("v").ifEmpty { "dev" }
    val appLabel = "v$cleanAppVersion"
    val cleanExpectedHash = expectedBuildHash.trim()
    val cleanExpectedTag = expectedReleaseTag.trim()
    if (serverInfo == null) {
        return DaemonCompatibilityInfo(
            status = DaemonCompatibilityStatus.UNKNOWN,
            appVersionLabel = appLabel,
            daemonVersionLabel = "unknown",
            daemonProtocolVersion = null,
            expectedProtocolVersion = expectedProtocolVersion,
            expectedBuildHash = cleanExpectedHash,
            expectedReleaseTag = cleanExpectedTag,
        )
    }

    val reportedProtocol = serverInfo.protocolVersion ?: 0
    val cleanDaemonHash = serverInfo.buildHash?.trim()?.takeIf { it.isNotEmpty() }
    val status = when {
        reportedProtocol < expectedProtocolVersion -> DaemonCompatibilityStatus.OUTDATED

        reportedProtocol > expectedProtocolVersion -> DaemonCompatibilityStatus.COMPATIBLE

        cleanExpectedTag.isNotEmpty() &&
            cleanExpectedHash.isNotEmpty() &&
            cleanDaemonHash != null &&
            !cleanDaemonHash.equals(cleanExpectedHash, ignoreCase = true) ->
            DaemonCompatibilityStatus.BUILD_MISMATCH

        else -> DaemonCompatibilityStatus.COMPATIBLE
    }
    val selfUpdate = serverInfo.selfUpdate
    return DaemonCompatibilityInfo(
        status = status,
        appVersionLabel = appLabel,
        daemonVersionLabel = serverInfo.displayVersionLabel(),
        daemonProtocolVersion = serverInfo.protocolVersion,
        expectedProtocolVersion = expectedProtocolVersion,
        daemonBuildHash = cleanDaemonHash,
        expectedBuildHash = cleanExpectedHash,
        expectedReleaseTag = cleanExpectedTag,
        supportsSelfUpdate = selfUpdate?.supported == true,
        rollbackAvailable = selfUpdate?.rollbackAvailable == true,
        previousBuildHash = selfUpdate?.previousBuildHash?.trim()?.takeIf { it.isNotEmpty() },
        lastAutoRollbackError = selfUpdate?.lastError?.trim()?.takeIf { it.isNotEmpty() },
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
