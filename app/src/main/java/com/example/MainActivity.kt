package com.example

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.ForegroundActivityTracker
import com.example.ui.HerdrApp
import com.example.ui.MainViewModel
import com.example.ui.theme.AppTheme
import com.example.ui.theme.HerdrTheme

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        setContent {
            val theme by viewModel.appTheme.collectAsStateWithLifecycle()
            HerdrTheme(theme = theme) {
                SystemBarIcons()
                HerdrApp(viewModel = viewModel)
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
        viewModel.onForeground()
    }

    override fun onPause() {
        ForegroundActivityTracker.onPaused(this)
        super.onPause()
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
