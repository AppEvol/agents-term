package com.appevol.agentsterm

import com.appevol.agentsterm.data.AppReleaseFetcher
import com.appevol.agentsterm.data.AppReleaseInfo
import com.appevol.agentsterm.data.AppUpdatePreferences
import com.appevol.agentsterm.data.CachedAppUpdateState
import com.appevol.agentsterm.data.GitHubAppReleaseFetcher
import com.appevol.agentsterm.data.appReleaseInfoFromTag
import com.appevol.agentsterm.data.isLocalDevAppVersion
import com.appevol.agentsterm.data.isNewerReleaseAvailable
import com.appevol.agentsterm.data.parseAppVersion
import com.appevol.agentsterm.data.parseReleaseTagFromLocation
import com.appevol.agentsterm.ui.APP_UPDATE_FAILURE_BACKOFF_MS
import com.appevol.agentsterm.ui.APP_UPDATE_SUCCESS_COOLDOWN_MS
import com.appevol.agentsterm.ui.AppUpdateController
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class AppUpdateCheckerTest {

    private class FakeAppUpdatePreferences(
        initial: CachedAppUpdateState = CachedAppUpdateState(),
    ) : AppUpdatePreferences {
        var state: CachedAppUpdateState = initial
            private set

        override fun loadAppUpdateState(): CachedAppUpdateState = state

        override fun saveLastAppUpdateCheck(latestTag: String, checkedAtMs: Long) {
            state = state.copy(latestTag = latestTag, lastSuccessCheckMs = checkedAtMs)
        }

        override fun saveDismissedAppUpdateTag(tag: String?) {
            state = state.copy(dismissedTag = tag)
        }
    }

    private class FakeReleaseFetcher(
        private val responses: MutableList<Result<AppReleaseInfo>>,
    ) : AppReleaseFetcher {
        var callCount: Int = 0
            private set

        override suspend fun fetchLatestRelease(): Result<AppReleaseInfo> {
            callCount += 1
            return if (responses.size > 1) responses.removeAt(0) else responses.first()
        }
    }

    @Test
    fun parseAppVersionHandlesOfficialPreviewAndLocalFormats() {
        val official = parseAppVersion("v0.3.457-cc4da24")
        assertNotNull(official)
        assertEquals(0, official!!.major)
        assertEquals(3, official.minor)
        assertEquals(457, official.patch)
        assertEquals("cc4da24", official.commitHash)
        assertFalse(official.isPreview)
        assertTrue(official.hasReleaseBuildNumber)
        assertEquals("v0.3.457", official.shortLabel)

        val preview = parseAppVersion("0.3.460-abcdef1-preview")
        assertNotNull(preview)
        assertEquals(460, preview!!.patch)
        assertTrue(preview.isPreview)

        val local = parseAppVersion("0.3")
        assertNotNull(local)
        assertNull(local!!.patch)
        assertFalse(local.hasReleaseBuildNumber)

        assertNull(parseAppVersion("latest"))
        assertNull(parseAppVersion(""))
        assertNull(parseAppVersion("v0.3.invalid"))
    }

    @Test
    fun versionComparisonDetectsNewerReleasesWithoutFalsePositives() {
        val latest = appReleaseInfoFromTag("v0.3.457-cc4da24")!!

        // Older official build -> update available
        assertTrue(isNewerReleaseAvailable(latest, "0.3.456-99e7ef3", 456))
        assertTrue(isNewerReleaseAvailable(latest, "0.2.999-1111111", 999))

        // Same version -> up to date
        assertFalse(isNewerReleaseAvailable(latest, "0.3.457-cc4da24", 457))

        // Newer official or preview build -> no update
        assertFalse(isNewerReleaseAvailable(latest, "0.3.458-1234567", 458))
        assertFalse(isNewerReleaseAvailable(latest, "0.3.460-abcdef0-preview", 460))

        // Same numeric tuple preview -> stable supersedes preview
        assertTrue(isNewerReleaseAvailable(latest, "0.3.457-cc4da24-preview", 457))

        // Unversioned local dev build -> never auto-flagged
        assertTrue(isLocalDevAppVersion("0.3", 1))
        assertFalse(isNewerReleaseAvailable(latest, "0.3", 1))
    }

    @Test
    fun parseReleaseTagFromLocationHandlesValidAndInvalidRedirects() {
        assertEquals(
            "v0.3.457-cc4da24",
            parseReleaseTagFromLocation("https://github.com/AppEvol/agents-term/releases/tag/v0.3.457-cc4da24"),
        )
        assertEquals(
            "v0.3.457-cc4da24",
            parseReleaseTagFromLocation("/AppEvol/agents-term/releases/tag/v0.3.457-cc4da24/?foo=1#top"),
        )
        assertNull(parseReleaseTagFromLocation("https://github.com/AppEvol/agents-term/releases/tag/latest"))
        assertNull(parseReleaseTagFromLocation("https://github.com/AppEvol/agents-term/releases"))
        assertNull(parseReleaseTagFromLocation(""))
    }

    @Test
    fun gitHubFetcherReadsHeadRedirectLocation() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(302)
                    .addHeader(
                        "Location",
                        "https://github.com/AppEvol/agents-term/releases/tag/v0.3.457-cc4da24",
                    ),
            )
            server.start()
            val fetcher = GitHubAppReleaseFetcher(
                latestReleaseUrl = server.url("/releases/latest").toString(),
                releasesBaseUrl = "https://github.com/AppEvol/agents-term/releases",
            )
            val result = fetcher.fetchLatestRelease()
            assertTrue(result.isSuccess)
            val info = result.getOrThrow()
            assertEquals("v0.3.457-cc4da24", info.tagName)
            assertEquals(457, info.version.patch)
            assertEquals(
                "https://github.com/AppEvol/agents-term/releases/tag/v0.3.457-cc4da24",
                info.releasePageUrl,
            )
            assertEquals(
                "https://github.com/AppEvol/agents-term/releases/download/v0.3.457-cc4da24/AgentsTerm-debug.apk",
                info.apkDownloadUrl,
            )

            val recorded = server.takeRequest()
            assertEquals("HEAD", recorded.method)
        }
    }

    @Test
    fun gitHubFetcherReturnsFailureOnNonRedirectOrBadTag() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200))
            server.enqueue(
                MockResponse()
                    .setResponseCode(302)
                    .addHeader("Location", "https://github.com/AppEvol/agents-term/releases/tag/latest"),
            )
            server.start()
            val fetcher = GitHubAppReleaseFetcher(
                latestReleaseUrl = server.url("/releases/latest").toString(),
            )
            assertTrue(fetcher.fetchLatestRelease().isFailure)
            assertTrue(fetcher.fetchLatestRelease().isFailure)
        }
    }

    @Test
    fun controllerEnforcesTwoHourSuccessCooldownAndFifteenMinuteErrorBackoff() = runTest {
        var now = 1_000_000L
        val release457 = appReleaseInfoFromTag("v0.3.457-cc4da24")!!
        val release458 = appReleaseInfoFromTag("v0.3.458-deadbee")!!
        val fetcher = FakeReleaseFetcher(
            mutableListOf(
                Result.failure(IOException("offline")),
                Result.success(release457),
                Result.success(release458),
            ),
        )
        val prefs = FakeAppUpdatePreferences()
        val controller = AppUpdateController(
            fetcher = fetcher,
            prefs = prefs,
            scope = this,
            currentVersionName = "0.3.450-1111111",
            currentVersionCode = 450,
            clockMs = { now },
        )

        // First attempt fails silently and arms the 15-minute error backoff.
        controller.checkIfDue()
        advanceUntilIdle()
        assertEquals(1, fetcher.callCount)
        assertNull(controller.uiState.value.lastCheckError)
        assertFalse(controller.uiState.value.updateAvailable)

        // Within 15 minutes -> skipped.
        now += APP_UPDATE_FAILURE_BACKOFF_MS - 1_000L
        controller.checkIfDue()
        advanceUntilIdle()
        assertEquals(1, fetcher.callCount)

        // After 15 minutes -> retries and succeeds with v0.3.457.
        now += 2_000L
        controller.checkIfDue()
        advanceUntilIdle()
        assertEquals(2, fetcher.callCount)
        assertTrue(controller.uiState.value.updateAvailable)
        assertTrue(controller.uiState.value.showOverflowBadge)
        assertEquals("v0.3.457-cc4da24", controller.uiState.value.latestRelease?.tagName)
        assertEquals("v0.3.457-cc4da24", prefs.state.latestTag)

        // Within 2 hours -> throttled.
        now += APP_UPDATE_SUCCESS_COOLDOWN_MS - 1_000L
        controller.checkIfDue()
        advanceUntilIdle()
        assertEquals(2, fetcher.callCount)

        // Dismiss badge for v0.3.457 -> dot hides, updateAvailable remains true.
        controller.dismissBadgeForCurrentUpdate()
        assertFalse(controller.uiState.value.showOverflowBadge)
        assertTrue(controller.uiState.value.updateAvailable)

        // After 2 hours -> checks again, sees newer v0.3.458 -> dot badge reappears!
        now += 2_000L
        controller.checkIfDue()
        advanceUntilIdle()
        assertEquals(3, fetcher.callCount)
        assertEquals("v0.3.458-deadbee", controller.uiState.value.latestRelease?.tagName)
        assertTrue(controller.uiState.value.showOverflowBadge)
    }

    @Test
    fun controllerRestoresCacheAndAllowsManualCheckIgnoringCooldown() = runTest {
        val now = 10_000_000L
        val release457 = appReleaseInfoFromTag("v0.3.457-cc4da24")!!
        val prefs = FakeAppUpdatePreferences(
            CachedAppUpdateState(
                latestTag = "v0.3.457-cc4da24",
                lastSuccessCheckMs = now - 60_000L, // 1 minute ago (< 2h)
                dismissedTag = "v0.3.457-cc4da24",
            ),
        )
        val fetcher = FakeReleaseFetcher(
            mutableListOf(
                Result.failure(IOException("timeout")),
                Result.success(release457),
            ),
        )
        val controller = AppUpdateController(
            fetcher = fetcher,
            prefs = prefs,
            scope = this,
            currentVersionName = "0.3.450-1111111",
            currentVersionCode = 450,
            clockMs = { now },
        )

        // Restored from cache with badge already dismissed.
        assertTrue(controller.uiState.value.updateAvailable)
        assertFalse(controller.uiState.value.showOverflowBadge)

        // Automatic check skipped due to 2-hour cooldown.
        controller.checkIfDue()
        advanceUntilIdle()
        assertEquals(0, fetcher.callCount)

        // Manual check ignores cooldown and surfaces error.
        controller.checkNow()
        advanceUntilIdle()
        assertEquals(1, fetcher.callCount)
        assertEquals("timeout", controller.uiState.value.lastCheckError)

        // Second manual check succeeds and clears error.
        controller.checkNow()
        advanceUntilIdle()
        assertEquals(2, fetcher.callCount)
        assertNull(controller.uiState.value.lastCheckError)
    }

    @Test
    fun controllerSkipsAutoCheckOnLocalDevBuildButAllowsManualCheck() = runTest {
        val release457 = appReleaseInfoFromTag("v0.3.457-cc4da24")!!
        val fetcher = FakeReleaseFetcher(mutableListOf(Result.success(release457)))
        val controller = AppUpdateController(
            fetcher = fetcher,
            prefs = FakeAppUpdatePreferences(),
            scope = this,
            currentVersionName = "0.3",
            currentVersionCode = 1,
        )

        assertTrue(controller.uiState.value.isLocalDevBuild)
        controller.checkIfDue()
        advanceUntilIdle()
        assertEquals(0, fetcher.callCount)

        controller.checkNow()
        advanceUntilIdle()
        assertEquals(1, fetcher.callCount)
        assertEquals("v0.3.457-cc4da24", controller.uiState.value.latestRelease?.tagName)
        assertFalse(controller.uiState.value.updateAvailable)
        assertFalse(controller.uiState.value.showOverflowBadge)
    }
}
