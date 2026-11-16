package io.candy.tick.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.candy.tick.data.GitHubApi
import io.candy.tick.data.RepoRepository
import io.candy.tick.data.SelfUpdater
import io.candy.tick.data.SourceException
import io.candy.tick.data.model.Settings
import io.candy.tick.data.model.SyncFrequency
import io.candy.tick.data.model.ThemeMode
import io.candy.tick.worker.SyncScheduler
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val repository: RepoRepository,
    private val githubApi: GitHubApi,
    private val selfUpdater: SelfUpdater,
    private val syncScheduler: SyncScheduler,
) : ViewModel() {

    enum class TokenTest { TESTING, VALID, INVALID, NETWORK }

    data class UiState(
        val settings: Settings = Settings(),
        val selfUpdate: SelfUpdater.State = SelfUpdater.State.Idle,
        val tokenInput: String = "",
        val tokenTest: TokenTest? = null,
        val localVersion: String = "0",
    )

    private val tokenInput = MutableStateFlow("")
    private val tokenTest = MutableStateFlow<TokenTest?>(null)

    val state: StateFlow<UiState> = combine(
        repository.state,
        selfUpdater.state,
        tokenInput,
        tokenTest,
    ) { s, update, input, test ->
        UiState(
            settings = s.settings,
            selfUpdate = update,
            tokenInput = input,
            tokenTest = test,
            localVersion = selfUpdater.localVersionName(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    private fun mutateSettings(transform: (Settings) -> Settings, reschedule: Boolean = false) {
        viewModelScope.launch {
            repository.updateSettings(transform)
            if (reschedule) syncScheduler.apply(repository.state.value.settings)
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        mutateSettings(transform = { s: Settings -> s.copy(themeMode = mode) })
    }

    fun setSyncFrequency(frequency: SyncFrequency) {
        mutateSettings(transform = { s: Settings -> s.copy(syncFrequency = frequency) }, reschedule = true)
    }

    fun setWifiOnly(enabled: Boolean) {
        mutateSettings(transform = { s: Settings -> s.copy(wifiOnly = enabled) }, reschedule = true)
    }

    fun setChargingOnly(enabled: Boolean) {
        mutateSettings(transform = { s: Settings -> s.copy(chargingOnly = enabled) }, reschedule = true)
    }

    fun setNotifications(enabled: Boolean) {
        mutateSettings(transform = { s: Settings -> s.copy(notificationsEnabled = enabled) })
    }

    fun setSelfUpdateCheck(enabled: Boolean) {
        mutateSettings(transform = { s: Settings -> s.copy(selfUpdateCheck = enabled) })
    }

    fun setSelfUpdateDownload(enabled: Boolean) {
        mutateSettings(transform = { s: Settings -> s.copy(selfUpdateAutoDownload = enabled) })
    }

    // ---- Token ----

    fun onTokenInput(text: String) {
        tokenInput.value = text
        tokenTest.value = null
    }

    fun saveAndTestToken() {
        val token = tokenInput.value.trim().takeIf { it.isNotBlank() }
        mutateSettings(transform = { s: Settings -> s.copy(githubToken = token) })
        if (token == null) {
            tokenTest.value = null
            return
        }
        viewModelScope.launch {
            tokenTest.value = TokenTest.TESTING
            tokenTest.value = try {
                if (githubApi.validateToken(token)) TokenTest.VALID else TokenTest.INVALID
            } catch (e: SourceException) {
                TokenTest.NETWORK
            }
        }
    }

    // ---- 自身更新 ----

    fun checkUpdate() {
        viewModelScope.launch { selfUpdater.check() }
    }

    fun downloadUpdate() {
        viewModelScope.launch {
            val current = selfUpdater.state.value as? SelfUpdater.State.NewVersion ?: return@launch
            current.asset?.let { selfUpdater.download(it) }
        }
    }

    fun installUpdate(file: File) = selfUpdater.install(file)
}
