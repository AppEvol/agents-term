package com.appevol.agentsterm.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.appevol.agentsterm.data.ModelThroughput
import kotlinx.coroutines.delay
import java.text.NumberFormat

@Composable
internal fun rememberThroughputLabels(rates: List<ModelThroughput>, agent: String): Map<String, String> {
    var now by remember(rates, agent) { mutableLongStateOf(System.currentTimeMillis()) }
    val active = remember(rates, agent, now) { rates.filter { it.agent == agent && it.expiresAt > now } }
    val nextExpiry = active.minOfOrNull { it.expiresAt }
    LaunchedEffect(nextExpiry) {
        if (nextExpiry != null) {
            delay((nextExpiry - System.currentTimeMillis()).coerceAtLeast(0L))
            now = System.currentTimeMillis()
        }
    }
    return remember(active) {
        val number = NumberFormat.getNumberInstance().apply { maximumFractionDigits = 1 }
        active.associate { it.model to "${number.format(it.tokensPerSecond)} tokens/s" }
    }
}
