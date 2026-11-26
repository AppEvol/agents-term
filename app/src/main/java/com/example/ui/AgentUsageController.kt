package com.example.ui

import com.example.data.AgentUsageUiState
import com.example.data.HerdrRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AgentUsageController(
    private val repo: HerdrRepository,
    private val scope: CoroutineScope,
) {
    private val _usageByAgent = MutableStateFlow<Map<String, AgentUsageUiState>>(emptyMap())
    val usageByAgent: StateFlow<Map<String, AgentUsageUiState>> = _usageByAgent.asStateFlow()

    fun checkUsage(agent: String) {
        if (agent == "shell") return
        if (_usageByAgent.value[agent]?.isLoading == true) return
        scope.launch {
            _usageByAgent.update { cur ->
                val prev = cur[agent] ?: AgentUsageUiState()
                cur + (agent to prev.copy(isLoading = true, error = null))
            }
            val result = repo.fetchAgentUsage(agent)
            _usageByAgent.update { cur ->
                val next = result.fold(
                    onSuccess = { resp ->
                        AgentUsageUiState(
                            isLoading = false,
                            response = resp,
                            error = resp.error?.trim()?.takeIf { it.isNotEmpty() },
                        )
                    },
                    onFailure = { err ->
                        val prev = cur[agent]
                        AgentUsageUiState(
                            isLoading = false,
                            response = prev?.response,
                            error = err.message ?: "Failed to check usage",
                        )
                    },
                )
                cur + (agent to next)
            }
        }
    }
}
