package com.appevol.agentsterm.data

import com.appevol.agentsterm.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

const val APP_RELEASES_BASE_URL = "https://github.com/AppEvol/agents-term/releases"
const val APP_LATEST_RELEASE_URL = "$APP_RELEASES_BASE_URL/latest"
const val APP_APK_FILENAME = "AgentsTerm-debug.apk"

private const val RELEASE_CHECK_TIMEOUT_SECONDS = 10L
private const val HTTP_REDIRECT_MIN = 300
private const val HTTP_REDIRECT_MAX = 399
private const val RELEASES_TAG_SEGMENT = "/releases/tag/"

private val APP_VERSION_REGEX =
    Regex("^v?(\\d+)\\.(\\d+)(?:\\.(\\d+))?(?:-([0-9a-fA-F]{7,40}))?(-preview)?$")

/**
 * Parsed representation of an AgentsTerm app version or GitHub release tag, such as
 * `0.3.457-cc4da24`, `v0.3.457-cc4da24`, `v0.3.457-cc4da24-preview`, or `0.3`.
 */
data class AppVersion(
    val major: Int,
    val minor: Int,
    val patch: Int?,
    val commitHash: String? = null,
    val isPreview: Boolean = false,
) : Comparable<AppVersion> {
    val hasReleaseBuildNumber: Boolean
        get() = patch != null

    val shortLabel: String
        get() = if (patch != null) "v$major.$minor.$patch" else "v$major.$minor"

    override fun compareTo(other: AppVersion): Int {
        if (major != other.major) return major.compareTo(other.major)
        if (minor != other.minor) return minor.compareTo(other.minor)
        val selfPatch = patch ?: 0
        val otherPatch = other.patch ?: 0
        if (selfPatch != otherPatch) return selfPatch.compareTo(otherPatch)
        // A stable release with the same numeric tuple supersedes a preview build.
        if (isPreview != other.isPreview) return if (isPreview) -1 else 1
        return 0
    }
}

data class AppReleaseInfo(
    val tagName: String,
    val version: AppVersion,
    val releasePageUrl: String,
    val apkDownloadUrl: String,
)

fun parseAppVersion(raw: String): AppVersion? {
    val trimmed = raw.trim()
    val match = APP_VERSION_REGEX.matchEntire(trimmed) ?: return null
    val major = match.groupValues[1].toIntOrNull() ?: return null
    val minor = match.groupValues[2].toIntOrNull() ?: return null
    val patch = match.groupValues[3].takeIf { it.isNotEmpty() }?.toIntOrNull()
    val commit = match.groupValues[4].takeIf { it.isNotEmpty() }?.lowercase()
    val isPreview = match.groupValues[5].isNotEmpty()
    return AppVersion(
        major = major,
        minor = minor,
        patch = patch,
        commitHash = commit,
        isPreview = isPreview,
    )
}

/**
 * True for unversioned local builds (`versionName="0.3"`, `versionCode=1`), which should not
 * nag developers with automatic update reminders.
 */
fun isLocalDevAppVersion(
    versionName: String = BuildConfig.VERSION_NAME,
    versionCode: Int = BuildConfig.VERSION_CODE,
): Boolean {
    val parsed = parseAppVersion(versionName) ?: return true
    return !parsed.hasReleaseBuildNumber && versionCode <= 1
}

fun formatInstalledAppVersionLabel(versionName: String = BuildConfig.VERSION_NAME): String {
    val clean = versionName.trim().removePrefix("v").ifEmpty { "dev" }
    return "v$clean"
}

/**
 * Returns true when [latestRelease] is strictly newer than the installed app version.
 * Unversioned local dev builds return false.
 */
fun isNewerReleaseAvailable(
    latestRelease: AppReleaseInfo,
    currentVersionName: String = BuildConfig.VERSION_NAME,
    currentVersionCode: Int = BuildConfig.VERSION_CODE,
): Boolean {
    if (isLocalDevAppVersion(currentVersionName, currentVersionCode)) return false
    val parsed = parseAppVersion(currentVersionName) ?: return false
    val effectiveCurrent = if (parsed.hasReleaseBuildNumber) {
        parsed
    } else {
        parsed.copy(patch = currentVersionCode)
    }
    return latestRelease.version > effectiveCurrent
}

fun parseReleaseTagFromLocation(location: String): String? {
    val trimmed = location.trim()
    val idx = trimmed.indexOf(RELEASES_TAG_SEGMENT)
    if (idx < 0) return null
    val afterSegment = trimmed.substring(idx + RELEASES_TAG_SEGMENT.length)
        .substringBefore('?')
        .substringBefore('#')
        .trimEnd('/')
        .trim()
    if (afterSegment.isEmpty() || afterSegment.contains('/') || afterSegment.equals("latest", ignoreCase = true)) {
        return null
    }
    return afterSegment
}

fun appReleaseInfoFromTag(
    tagName: String,
    releasesBaseUrl: String = APP_RELEASES_BASE_URL,
    releasePageUrl: String? = null,
): AppReleaseInfo? {
    val cleanTag = tagName.trim()
    val parsed = parseAppVersion(cleanTag) ?: return null
    if (!parsed.hasReleaseBuildNumber) return null
    val base = releasesBaseUrl.trimEnd('/')
    return AppReleaseInfo(
        tagName = cleanTag,
        version = parsed,
        releasePageUrl = releasePageUrl?.trim()?.takeIf { it.isNotEmpty() } ?: "$base/tag/$cleanTag",
        apkDownloadUrl = "$base/download/$cleanTag/$APP_APK_FILENAME",
    )
}

interface AppReleaseFetcher {
    suspend fun fetchLatestRelease(): Result<AppReleaseInfo>
}

/**
 * Discovers the latest official release via an unauthenticated `HEAD` request to GitHub's
 * `/releases/latest` redirect endpoint (avoids the 60 req/hr GitHub REST API IP rate limit).
 */
class GitHubAppReleaseFetcher(
    private val latestReleaseUrl: String = APP_LATEST_RELEASE_URL,
    private val releasesBaseUrl: String = APP_RELEASES_BASE_URL,
    private val client: OkHttpClient = defaultClient(),
    private val debugRecorder: DebugRecorder? = null,
    private val userAgent: String = "AgentsTerm/${BuildConfig.VERSION_NAME}",
) : AppReleaseFetcher {
    override suspend fun fetchLatestRelease(): Result<AppReleaseInfo> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(latestReleaseUrl)
            .head()
            .header("User-Agent", userAgent)
            .build()
        try {
            client.newCall(request).execute().use { response ->
                val location = response.header("Location")?.trim().orEmpty()
                if (response.code !in HTTP_REDIRECT_MIN..HTTP_REDIRECT_MAX || location.isEmpty()) {
                    return@withContext Result.failure(
                        IOException("Unexpected release lookup status HTTP ${response.code}"),
                    )
                }
                val tag = parseReleaseTagFromLocation(location)
                    ?: return@withContext Result.failure(
                        IOException("Release redirect did not contain a valid tag: $location"),
                    )
                val resolvedPageUrl = response.request.url.resolve(location)?.toString()
                val info = appReleaseInfoFromTag(
                    tagName = tag,
                    releasesBaseUrl = releasesBaseUrl,
                    releasePageUrl = resolvedPageUrl,
                ) ?: return@withContext Result.failure(
                    IOException("Unrecognized release tag format: $tag"),
                )
                debugRecorder?.record(
                    category = "update",
                    event = "app_release_checked",
                    detail = "Latest GitHub release: ${info.tagName}",
                )
                Result.success(info)
            }
        } catch (err: CancellationException) {
            throw err
        } catch (err: IOException) {
            debugRecorder?.record(
                category = "update",
                event = "app_release_check_failed",
                detail = err.message ?: "I/O error checking latest app release",
            )
            Result.failure(err)
        }
    }

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(RELEASE_CHECK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(RELEASE_CHECK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }
}
