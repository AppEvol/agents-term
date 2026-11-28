package com.appevol.agentsterm

import android.content.Intent
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.appevol.agentsterm.data.ForegroundActivityTracker
import com.appevol.agentsterm.data.ScreenCaptureConsent
import com.appevol.agentsterm.data.ScreenCaptureConsentHost
import com.appevol.agentsterm.ui.AgentsTermApp
import com.appevol.agentsterm.ui.LocalAttachmentImages
import com.appevol.agentsterm.ui.MainViewModel
import com.appevol.agentsterm.ui.theme.AgentsTermTheme
import com.appevol.agentsterm.ui.theme.AppTheme

class MainActivity :
    ComponentActivity(),
    ScreenCaptureConsentHost {
    private val viewModel: MainViewModel by viewModels()

    private val screenCaptureConsent =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { ScreenCaptureConsent.deliver(it) }

    override fun launchScreenCaptureConsent() {
        screenCaptureConsent.launch(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        setContent {
            val theme by viewModel.appTheme.collectAsStateWithLifecycle()
            AgentsTermTheme(theme = theme) {
                SystemBarIcons()
                CompositionLocalProvider(LocalAttachmentImages provides viewModel.attachmentImages) {
                    AgentsTermApp(viewModel = viewModel)
                }
            }
        }
    }

    /** Keeps the status and navigation bar icons readable on the current theme's bars. */
    @Composable
    private fun SystemBarIcons() {
        val lightStatusIcons = AppTheme.chrome.lightStatusBarIcons
        val lightNavigationIcons = AppTheme.spec.isDark
        LaunchedEffect(lightStatusIcons, lightNavigationIcons) {
            enableEdgeToEdge(
                statusBarStyle = systemBarStyle(lightIcons = lightStatusIcons),
                navigationBarStyle = systemBarStyle(lightIcons = lightNavigationIcons),
            )
        }
    }

    private fun systemBarStyle(lightIcons: Boolean): SystemBarStyle = if (lightIcons) {
        SystemBarStyle.dark(Color.TRANSPARENT)
    } else {
        SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        ForegroundActivityTracker.onResumed(this)
        viewModel.onForegroundChanged(inForeground = true)
    }

    override fun onPause() {
        ForegroundActivityTracker.onPaused(this)
        super.onPause()
    }

    override fun onStop() {
        if (!isChangingConfigurations) {
            viewModel.onForegroundChanged(inForeground = false)
        }
        super.onStop()
    }

    private fun handleIntent(intent: Intent?) {
        val data = intent?.dataString ?: return
        if (
            data.startsWith("agentsterm://", ignoreCase = true) ||
            data.startsWith("herdr://", ignoreCase = true)
        ) {
            viewModel.handleIncomingPairingUri(data)
        }
    }
}
