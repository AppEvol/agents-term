package com.example

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.example.data.ForegroundActivityTracker
import com.example.ui.HerdrApp
import com.example.ui.MainViewModel
import com.example.ui.theme.HerdrTheme

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        setContent {
            HerdrTheme {
                HerdrApp(viewModel = viewModel)
            }
        }
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
