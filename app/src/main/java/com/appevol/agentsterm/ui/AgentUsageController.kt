package com.appevol.agentsterm.ui

import com.appevol.agentsterm.data.AgentUsageUiState
import com.appevol.agentsterm.data.AgentsTermRepository
import com.appevol.agentsterm.data.formatDaemonRequestError
import com.appevol.agentsterm.data.isUnknownEndpointError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AgentUsageController(
    private val repo: AgentsTermRepository,
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
                            error = formatDaemonRequestError(err, "Failed to check usage"),
                            needsDaemonUpdate = err.isUnknownEndpointError,
                        )
                    },
                )
                cur + (agent to next)
            }
        }
    }
}
