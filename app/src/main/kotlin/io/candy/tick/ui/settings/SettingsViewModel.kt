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
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val repository: RepoRepository,
    private val githubApi: GitHubApi,
    private val selfUpdater: SelfUpdater,
    private val cacheManager: io.candy.tick.data.CacheManager,
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
    private var tokenValidation: Job? = null
    private val localVersion = selfUpdater.localVersionName()

    val state: StateFlow<UiState> = combine(
        repository.state,
        selfUpdater.state,
        tokenInput,
        tokenTest,
        githubApi.tokenRejected,
    ) { s, update, input, test, rejected ->
        UiState(
            settings = s.settings,
            selfUpdate = update,
            tokenInput = input,
            tokenTest = test,
            tokenRejected = rejected,
            localVersion = localVersion,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    private fun mutateSettings(transform: (Settings) -> Settings) {
        viewModelScope.launch {
            repository.updateSettings(transform)
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        mutateSettings(transform = { s: Settings -> s.copy(themeMode = mode) })
    }

    fun setSyncFrequency(frequency: SyncFrequency) {
        mutateSettings(transform = { s: Settings -> s.copy(syncFrequency = frequency) })
    }

    fun setWifiOnly(enabled: Boolean) {
        mutateSettings(transform = { s: Settings -> s.copy(wifiOnly = enabled) })
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
        tokenValidation?.cancel()
        tokenInput.value = text
        tokenTest.value = null
    }

    /** 仅将已通过验证的草稿写入设置，空草稿用于移除原凭据。 */
    fun saveAndTestToken() {
        val token = tokenInput.value.trim().takeIf { it.isNotBlank() }
        tokenValidation?.cancel()
        tokenValidation = viewModelScope.launch {
            if (token == null) {
                githubApi.clearTokenRejected()
                repository.updateSettings { it.copy(githubToken = null) }
                tokenTest.value = null
                return@launch
            }
            tokenTest.value = TokenTest.TESTING
            val result = try {
                if (githubApi.validateToken(token)) {
                    githubApi.clearTokenRejected()
                    repository.updateSettings { it.copy(githubToken = token) }
                    TokenTest.VALID
                } else {
                    TokenTest.INVALID
                }
            } catch (e: SourceException) {
                TokenTest.NETWORK
            } catch (_: IllegalArgumentException) {
                // 粘贴了换行或非 HTTP header 字符。
                TokenTest.INVALID
            }
            tokenTest.value = result
        }
    }

    /** sheet 关闭时丢弃未保存的编辑：输入框重置为已保存值，消灭幽灵草稿（所见即所存） */
    fun resetTokenDraft() {
        tokenValidation?.cancel()
        tokenInput.value = repository.state.value.settings.githubToken ?: ""
        tokenTest.value = null
    }

    // ---- 缓存清理 ----

    data class CacheState(
        val scanning: Boolean = true,
        val apkBytes: Long = 0, // 仓库 + 自身更新安装包合计（浮层一行展示）
        val crashLogBytes: Long = 0,
        val cleaning: Boolean = false,
    )

    private val _cacheState = MutableStateFlow(CacheState())
    val cacheState: StateFlow<CacheState> = _cacheState.asStateFlow()
    private var cacheScan: Job? = null

    /** 打开清理浮层：实时扫描各类大小 */
    fun scanCache() {
        cacheScan?.cancel()
        cacheScan = viewModelScope.launch {
            _cacheState.value = CacheState()
            val scan = withContext(Dispatchers.IO) { cacheManager.scan() }
            _cacheState.value = CacheState(
                scanning = false,
                apkBytes = scan.repoApkBytes + scan.updateApkBytes,
                crashLogBytes = scan.crashLogBytes,
            )
        }
    }

    /**
     * 执行清理。返回释放的字节数（挂起直到完成——调用方用 loading 阻断动画包裹）。
     * 同步清空 downloadedApk 记录（UI 状态自动变更）+ SelfUpdater 状态复位。
     */
    suspend fun cleanCache(apks: Boolean, crashLog: Boolean): Long {
        _cacheState.value = _cacheState.value.copy(cleaning = true)
        return try {
            // 文件已删后，即使 UI 离场也要核对相应记录。
            withContext(NonCancellable + Dispatchers.IO) {
                val freed = cacheManager.clean(apks, apks, crashLog)
                if (apks) {
                    repository.clearMissingDownloads()
                    selfUpdater.resetState()
                }
                freed
            }
        } finally {
            _cacheState.value = _cacheState.value.copy(cleaning = false)
        }
    }

    /** 浮层关闭后清空扫描数据：下次打开不闪旧值 */
    fun resetCacheState() {
        cacheScan?.cancel()
        _cacheState.value = CacheState()
    }

    // ---- 备份 ----

    suspend fun exportSubscriptions(): String = withContext(Dispatchers.Default) { repository.exportState() }

    suspend fun importSubscriptions(text: String): Int = withContext(Dispatchers.Default) { repository.importState(text) }

    // ---- 自身更新 ----

    suspend fun checkUpdate(): SelfUpdater.State {
        selfUpdater.check()
        return selfUpdater.state.value
    }

}
