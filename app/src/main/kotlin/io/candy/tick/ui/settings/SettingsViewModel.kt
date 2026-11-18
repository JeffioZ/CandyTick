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
        val tokenRejected: Boolean = false,
        val localVersion: String = "0",
    )

    // 回填已保存的 Token：重进设置页能看到现值，而非空白
    private val tokenInput = MutableStateFlow(repository.state.value.settings.githubToken ?: "")
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
            tokenRejected = githubApi.tokenRejected,
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

    fun setLanguage(tag: String) {
        viewModelScope.launch {
            repository.updateSettings { it.copy(language = tag) }
        }
    }

    fun setAutoInstallDownload(enabled: Boolean) {
        viewModelScope.launch { repository.updateSettings { it.copy(autoInstallDownload = enabled) } }
    }

    fun setSelfUpdateDownload(enabled: Boolean) {
        mutateSettings(transform = { s: Settings -> s.copy(selfUpdateAutoDownload = enabled) })
    }

    // ---- Token ----

    fun onTokenInput(text: String) {
        tokenInput.value = text
        tokenTest.value = null
    }

    /**
     * 保存并验证。状态机（「已保存」永远 = 最后一次验证通过的值）：
     * - 验证通过 → 落盘保留；失败/网络错 → 回滚原值（旧的先落盘后验证会让无效 Token 悬在设置里）
     */
    fun saveAndTestToken() {
        val token = tokenInput.value.trim().takeIf { it.isNotBlank() }
        val previous = repository.state.value.settings.githubToken
        mutateSettings(transform = { s: Settings -> s.copy(githubToken = token) })
        if (token == null) {
            tokenTest.value = null
            return
        }
        viewModelScope.launch {
            tokenTest.value = TokenTest.TESTING
            val result = try {
                if (githubApi.validateToken(token)) {
                    githubApi.clearTokenRejected()
                    TokenTest.VALID
                } else {
                    TokenTest.INVALID
                }
            } catch (e: SourceException) {
                TokenTest.NETWORK
            }
            tokenTest.value = result
            // 验证未通过：回滚——无效/未验证的 Token 不留在设置里（401 降级反而更差）
            if (result != TokenTest.VALID) {
                mutateSettings(transform = { s: Settings -> s.copy(githubToken = previous) })
            }
        }
    }

    /** sheet 关闭时丢弃未保存的编辑：输入框重置为已保存值，消灭幽灵草稿（所见即所存） */
    fun resetTokenDraft() {
        tokenInput.value = repository.state.value.settings.githubToken ?: ""
        tokenTest.value = null
    }

    // ---- 备份 ----

    fun exportSubscriptions(): String = repository.exportState()

    suspend fun importSubscriptions(text: String): Int = repository.importState(text)

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
