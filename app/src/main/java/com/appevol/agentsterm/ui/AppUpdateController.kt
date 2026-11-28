package com.appevol.agentsterm.ui

import com.appevol.agentsterm.BuildConfig
import com.appevol.agentsterm.data.APP_RELEASES_BASE_URL
import com.appevol.agentsterm.data.AppReleaseFetcher
import com.appevol.agentsterm.data.AppReleaseInfo
import com.appevol.agentsterm.data.AppUpdatePreferences
import com.appevol.agentsterm.data.appReleaseInfoFromTag
import com.appevol.agentsterm.data.formatInstalledAppVersionLabel
import com.appevol.agentsterm.data.isLocalDevAppVersion
import com.appevol.agentsterm.data.isNewerReleaseAvailable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

const val APP_UPDATE_SUCCESS_COOLDOWN_MS = 2L * 60L * 60L * 1000L
const val APP_UPDATE_FAILURE_BACKOFF_MS = 15L * 60L * 1000L

data class AppUpdateUiState(
    val currentVersionLabel: String = formatInstalledAppVersionLabel(),
    val isLocalDevBuild: Boolean = isLocalDevAppVersion(),
    val latestRelease: AppReleaseInfo? = null,
    val updateAvailable: Boolean = false,
    val badgeDismissed: Boolean = false,
    val isChecking: Boolean = false,
    val lastCheckedAtMs: Long? = null,
    val lastCheckError: String? = null,
) {
    /** True when the three-dot overflow icon should display a small update dot. */
    val showOverflowBadge: Boolean
        get() = updateAvailable && !badgeDismissed
}

/**
 * Tracks whether a newer official AgentsTerm APK release is available on GitHub.
 *
 * Automatic checks are throttled to at most once every 2 hours after a successful check (and
 * 15 minutes after a network failure), and are skipped for unversioned local dev builds unless
 * explicitly triggered via [checkNow].
 */
class AppUpdateController(
    private val fetcher: AppReleaseFetcher,
    private val prefs: AppUpdatePreferences,
    private val scope: CoroutineScope,
    private val currentVersionName: String = BuildConfig.VERSION_NAME,
    private val currentVersionCode: Int = BuildConfig.VERSION_CODE,
    private val releasesBaseUrl: String = APP_RELEASES_BASE_URL,
    private val successCooldownMs: Long = APP_UPDATE_SUCCESS_COOLDOWN_MS,
    private val failureBackoffMs: Long = APP_UPDATE_FAILURE_BACKOFF_MS,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) {
    private val isLocalDev = isLocalDevAppVersion(currentVersionName, currentVersionCode)
    private var lastSuccessCheckMs: Long
    private var lastFailureCheckMs: Long = 0L
    private var dismissedTag: String?
    private var checkJob: Job? = null

    private val _uiState: MutableStateFlow<AppUpdateUiState>
    val uiState: StateFlow<AppUpdateUiState>

    init {
        val cached = prefs.loadAppUpdateState()
        lastSuccessCheckMs = cached.lastSuccessCheckMs
        dismissedTag = cached.dismissedTag
        val cachedRelease = cached.latestTag?.let { appReleaseInfoFromTag(it, releasesBaseUrl) }
        val available = cachedRelease != null &&
            isNewerReleaseAvailable(cachedRelease, currentVersionName, currentVersionCode)
        val badgeDismissed = available && dismissedTag == cachedRelease.tagName
        _uiState = MutableStateFlow(
            AppUpdateUiState(
                currentVersionLabel = formatInstalledAppVersionLabel(currentVersionName),
                isLocalDevBuild = isLocalDev,
                latestRelease = cachedRelease,
                updateAvailable = available,
                badgeDismissed = badgeDismissed,
                lastCheckedAtMs = cached.lastSuccessCheckMs.takeIf { it > 0L },
            ),
        )
        uiState = _uiState.asStateFlow()
    }

    /**
     * Runs a silent background check if this is a versioned build and the cooldown has elapsed.
     */
    fun checkIfDue() {
        if (isLocalDev) return
        if (checkJob?.isActive == true) return
        val now = clockMs()
        if (lastSuccessCheckMs > 0L && now >= lastSuccessCheckMs && now - lastSuccessCheckMs < successCooldownMs) {
            return
        }
        if (lastFailureCheckMs > 0L && now >= lastFailureCheckMs && now - lastFailureCheckMs < failureBackoffMs) {
            return
        }
        runCheck(surfaceError = false)
    }

    /**
     * Runs an immediate, unthrottled check (for the manual "Check for updates" action in Settings).
     */
    fun checkNow() {
        if (checkJob?.isActive == true) return
        runCheck(surfaceError = true)
    }

    /**
     * Clears the notification dot on the three-dot icon for the currently advertised release tag
     * while keeping the menu entry accessible until a newer release ships.
     */
    fun dismissBadgeForCurrentUpdate() {
        val tag = _uiState.value.latestRelease?.tagName ?: return
        dismissedTag = tag
        prefs.saveDismissedAppUpdateTag(tag)
        _uiState.update { cur ->
            if (cur.latestRelease?.tagName == tag) cur.copy(badgeDismissed = true) else cur
        }
    }

    private fun runCheck(surfaceError: Boolean) {
        _uiState.update {
            it.copy(
                isChecking = true,
                lastCheckError = if (surfaceError) null else it.lastCheckError,
            )
        }
        checkJob = scope.launch {
            val result = fetcher.fetchLatestRelease()
            val checkedAt = clockMs()
            result.fold(
                onSuccess = { release ->
                    lastSuccessCheckMs = checkedAt
                    lastFailureCheckMs = 0L
                    prefs.saveLastAppUpdateCheck(release.tagName, checkedAt)
                    val available = isNewerReleaseAvailable(release, currentVersionName, currentVersionCode)
                    val dismissed = available && dismissedTag == release.tagName
                    _uiState.update { cur ->
                        cur.copy(
                            latestRelease = release,
                            updateAvailable = available,
                            badgeDismissed = dismissed,
                            isChecking = false,
                            lastCheckedAtMs = checkedAt,
                            lastCheckError = null,
                        )
                    }
                },
                onFailure = { err ->
                    lastFailureCheckMs = checkedAt
                    _uiState.update { cur ->
                        cur.copy(
                            isChecking = false,
                            lastCheckError = if (surfaceError) {
                                err.message?.trim()?.takeIf { it.isNotEmpty() }
                                    ?: "Unable to check for updates"
                            } else {
                                cur.lastCheckError
                            },
                        )
                    }
                },
            )
        }
    }
}
