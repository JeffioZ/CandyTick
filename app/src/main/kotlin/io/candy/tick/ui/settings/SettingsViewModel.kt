package io.candy.tick.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.candy.tick.data.CacheManager
import io.candy.tick.data.GitHubApi
import io.candy.tick.data.RepoRepository
import io.candy.tick.data.SelfUpdater
import io.candy.tick.data.SourceException
import io.candy.tick.data.model.AppIconMode
import io.candy.tick.data.model.Settings
import io.candy.tick.data.model.SyncFrequency
import io.candy.tick.data.model.ThemeMode
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
    private val cacheManager: CacheManager,
) : ViewModel() {

    enum class TokenTest { TESTING, VALID, INVALID, NETWORK, SAVE_FAILED }

    data class UiState(
        val settings: Settings = Settings(),
        val selfUpdate: SelfUpdater.State = SelfUpdater.State.Idle,
        val tokenInput: String = "",
        val tokenTest: TokenTest? = null,
        val tokenRejected: Boolean = false,
        val rateLimit: GitHubApi.RateLimitInfo? = null,
        val rateLimitLoading: Boolean = false,
        val localVersion: String? = null,
    )

    private data class TokenTransient(
        val input: String,
        val test: TokenTest?,
        val rateLimitLoading: Boolean,
    )

    // 回填已保存的 Token：重进设置页能看到现值，而非空白
    private val tokenInput = MutableStateFlow(repository.state.value.settings.githubToken ?: "")
    private val tokenTest = MutableStateFlow<TokenTest?>(null)
    private val rateLimitLoading = MutableStateFlow(false)
    // 设置页展示的额度快照：只在进入设置/打开弹窗/手动刷新/保存清空 Token 时更新，
    // 避免后台 SyncWorker 并发同步期间数字逐条跳动
    private val displayedRateLimit = MutableStateFlow(githubApi.rateLimit.value)
    private var tokenValidation: Job? = null
    private var rateLimitJob: Job? = null
    private val localVersion = selfUpdater.localVersionName()

    private val tokenTransient = combine(tokenInput, tokenTest, rateLimitLoading, ::TokenTransient)

    val state: StateFlow<UiState> = combine(
        repository.state,
        selfUpdater.state,
        tokenTransient,
        githubApi.tokenRejected,
        displayedRateLimit,
    ) { s, update, transient, rejected, rateLimit ->
        UiState(
            settings = s.settings,
            selfUpdate = update,
            tokenInput = transient.input,
            tokenTest = transient.test,
            tokenRejected = rejected,
            rateLimit = rateLimit,
            rateLimitLoading = transient.rateLimitLoading,
            localVersion = localVersion,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState(
        settings = repository.state.value.settings,
        selfUpdate = selfUpdater.state.value,
        tokenInput = tokenInput.value,
        tokenRejected = githubApi.tokenRejected.value,
        rateLimit = displayedRateLimit.value,
        localVersion = localVersion,
    ))

    private fun mutateSettings(transform: (Settings) -> Settings) {
        viewModelScope.launch {
            repository.updateSettings(transform)
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        mutateSettings { it.copy(themeMode = mode) }
    }

    fun setAppIconMode(mode: AppIconMode) {
        mutateSettings { it.copy(appIconMode = mode) }
    }

    fun setSyncFrequency(frequency: SyncFrequency) {
        mutateSettings { it.copy(syncFrequency = frequency) }
    }

    fun setWifiOnly(enabled: Boolean) {
        mutateSettings { it.copy(wifiOnly = enabled) }
    }

    fun setNotifications(enabled: Boolean) {
        mutateSettings { it.copy(notificationsEnabled = enabled) }
    }

    fun setSelfUpdateCheck(enabled: Boolean) {
        mutateSettings { it.copy(selfUpdateCheck = enabled) }
    }

    fun setLanguage(tag: String) {
        mutateSettings { it.copy(language = tag) }
    }

    fun setAutoInstallDownload(enabled: Boolean) {
        mutateSettings { it.copy(autoInstallDownload = enabled) }
    }

    fun setSelfUpdateDownload(enabled: Boolean) {
        mutateSettings { if (it.selfUpdateCheck) it.copy(selfUpdateAutoDownload = enabled) else it }
    }

    // ---- Token ----

    /**
     * 存储未就绪时 updateSettings 会无限等待；限时后转 SAVE_FAILED，
     * 让弹窗给出失败出口而不是停在「验证中」。
     */
    private suspend fun persistSettings(transform: (Settings) -> Settings): Boolean =
        withTimeoutOrNull(3_000L) { repository.updateSettings(transform) } != null

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
                if (!persistSettings { it.copy(githubToken = null, githubUsername = null) }) {
                    tokenTest.value = TokenTest.SAVE_FAILED
                    return@launch
                }
                // 保存成功后才清被拒标记：失败时保留提示，避免「坏 Token 仍在用且不再提示被拒」
                githubApi.clearTokenRejected()
                tokenTest.value = null
                refreshRateLimit()
                return@launch
            }
            tokenTest.value = TokenTest.TESTING
            val result = try {
                when (val validation = githubApi.validateToken(token)) {
                    is GitHubApi.TokenValidation.Valid -> {
                        if (!persistSettings {
                                it.copy(githubToken = token, githubUsername = validation.username)
                            }
                        ) {
                            tokenTest.value = TokenTest.SAVE_FAILED
                            return@launch
                        }
                        githubApi.clearTokenRejected()
                        validation.rateLimit?.let {
                            githubApi.applyRateLimit(it)
                            displayedRateLimit.value = it
                        } ?: refreshRateLimit()
                        TokenTest.VALID
                    }
                    GitHubApi.TokenValidation.Invalid -> TokenTest.INVALID
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

    /** 免费查询当前 GitHub core 额度，并在已有 Token 但未缓存用户名时顺手补拉一次 */
    fun refreshRateLimit() {
        rateLimitJob?.cancel()
        rateLimitJob = viewModelScope.launch {
            rateLimitLoading.value = true
            try {
                githubApi.fetchRateLimit()?.let { displayedRateLimit.value = it }
                val settings = repository.state.value.settings
                if (!settings.githubToken.isNullOrBlank() &&
                    settings.githubUsername.isNullOrBlank() &&
                    !githubApi.tokenRejected.value
                ) {
                    val username = githubApi.fetchUsername()
                    if (!username.isNullOrBlank()) {
                        persistSettings { current ->
                            if (current.githubToken == settings.githubToken) current.copy(githubUsername = username)
                            else current
                        }
                    }
                }
            } finally {
                rateLimitLoading.value = false
            }
        }
    }

    /** sheet 关闭时丢弃未保存的编辑：输入框重置为已保存值，消灭幽灵草稿（所见即所存） */
    fun resetTokenDraft() {
        tokenValidation?.cancel()
        tokenInput.value = repository.state.value.settings.githubToken ?: ""
        tokenTest.value = null
    }

    // ---- 缓存清理 ----

    internal data class CacheState(
        val scan: CacheManager.CacheInfo? = null,
        val cleaning: Boolean = false,
    ) {
        val scanning: Boolean get() = scan == null
        val apkBytes: Long get() = scan?.apkBytes ?: 0L
        val crashLogBytes: Long get() = scan?.crashLogBytes ?: 0L
    }

    private val _cacheState = MutableStateFlow(CacheState())
    internal val cacheState: StateFlow<CacheState> = _cacheState.asStateFlow()
    private var cacheScan: Job? = null

    /** 打开清理浮层：实时扫描各类大小 */
    fun scanCache() {
        cacheScan?.cancel()
        cacheScan = viewModelScope.launch {
            _cacheState.value = CacheState()
            val scan = withContext(Dispatchers.IO) { cacheManager.scan() }
            _cacheState.value = CacheState(scan)
        }
    }

    /**
     * 执行清理。返回释放的字节数（挂起直到完成——调用方用 loading 阻断动画包裹）。
     * 同步清空 downloadedApk 记录（UI 状态自动变更）+ SelfUpdater 状态复位。
     */
    suspend fun cleanCache(apks: Boolean, crashLog: Boolean): Long {
        val shown = _cacheState.value
        val scan = shown.scan ?: return 0L
        if (shown.cleaning || !_cacheState.compareAndSet(shown, shown.copy(cleaning = true))) return 0L
        return try {
            // 文件已删后，即使 UI 离场也要核对相应记录。
            withContext(NonCancellable + Dispatchers.IO) {
                val freed = scan.clean(apks, crashLog)
                if (apks) {
                    repository.clearMissingDownloads()
                    selfUpdater.clearMissingDownload()
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

    /** null = 数据未就绪：调用方应中止分享并提示，而不是把空备份送进分享流 */
    suspend fun exportSubscriptions(): String? = withContext(Dispatchers.Default) { repository.exportState() }

    suspend fun importSubscriptions(text: String): Int = withContext(Dispatchers.Default) { repository.importState(text) }

    suspend fun importSubscriptions(input: java.io.InputStream): Int = repository.importState(input)

    // ---- 自身更新 ----

    suspend fun checkUpdate(): SelfUpdater.State {
        selfUpdater.check()
        return selfUpdater.state.value
    }

}
