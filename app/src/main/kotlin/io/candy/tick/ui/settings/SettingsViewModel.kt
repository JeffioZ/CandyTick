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
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val repository: RepoRepository,
    private val githubApi: GitHubApi,
    private val selfUpdater: SelfUpdater,
    private val syncScheduler: SyncScheduler,
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

    // ---- 缓存清理 ----

    data class CacheState(
        val scanning: Boolean = false,
        val repoApkBytes: Long = 0,
        val updateApkBytes: Long = 0,
        val crashLogBytes: Long = 0,
        val cleaning: Boolean = false,
    ) {
        val totalBytes: Long get() = repoApkBytes + updateApkBytes + crashLogBytes
    }

    private val _cacheState = MutableStateFlow(CacheState())
    val cacheState: StateFlow<CacheState> = _cacheState.asStateFlow()

    /** 进入设置页时粗算（行 value 用） */
    fun quickCacheSize(): Long = cacheManager.quickSize()

    /** 打开清理浮层：实时扫描各类大小 */
    fun scanCache() {
        viewModelScope.launch {
            _cacheState.value = CacheState(scanning = true)
            val info = io.candy.tick.data.CacheManager.CacheInfo(0, 0, 0) // placeholder
            val scan = cacheManager.scan()
            _cacheState.value = CacheState(
                repoApkBytes = scan.repoApkBytes,
                updateApkBytes = scan.updateApkBytes,
                crashLogBytes = scan.crashLogBytes,
            )
        }
    }

    /**
     * 执行清理。返回释放的字节数（挂起直到完成——调用方用 loading 阻断动画包裹）。
     * 同步清空 downloadedApk 记录（UI 状态自动变更）+ SelfUpdater 状态复位。
     */
    suspend fun cleanCache(repoApks: Boolean, updateApks: Boolean, crashLog: Boolean): Long {
        _cacheState.value = _cacheState.value.copy(cleaning = true)
        val freed = cacheManager.clean(repoApks, updateApks, crashLog)
        // 清空已下载记录 → 详情页/卡片「已下载」UI 自动变回「下载」按钮
        if (repoApks) {
            repository.state.value.repos.forEach { repo ->
                if (repo.downloadedApk != null) {
                    repository.setDownloadedApk(repo.id, null)
                }
            }
        }
        // 自身更新状态复位 → 「安装已下载的更新」变回可下载
        if (updateApks) {
            selfUpdater.resetState()
        }
        _cacheState.value = _cacheState.value.copy(cleaning = false)
        return freed
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
            current.asset?.let {
                val file = selfUpdater.download(it)
                // 统一开关：下载完成自动进入安装（与仓库 APK 同一行为）；关则停在安装按钮
                if (file != null && repository.state.value.settings.autoInstallDownload) {
                    selfUpdater.install(file)
                }
            }
        }
    }

    fun installUpdate(file: File) = selfUpdater.install(file)
}
