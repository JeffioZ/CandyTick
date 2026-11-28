package io.candy.tick.ui.store

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.os.SystemClock
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.candy.tick.data.AppStateStore
import io.candy.tick.data.model.MeteredDownloadPolicy
import io.candy.tick.data.model.StoreAppUpdate
import io.candy.tick.data.model.StorePreferences
import io.candy.tick.data.model.StoreReleaseInfo
import io.candy.tick.data.model.StoreSignatureRecord
import io.candy.tick.data.model.StoreState
import io.candy.tick.data.store.InstalledAppCache
import io.candy.tick.data.store.LocalInstalledApp
import io.candy.tick.data.store.StoreCatalog
import io.candy.tick.data.store.StoreCheckProgress
import io.candy.tick.data.store.StoreDownloadHost
import io.candy.tick.data.store.StoreSystemAppPolicy
import io.candy.tick.data.store.StoreTaskState
import io.candy.tick.data.store.StoreUpdateChecker
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class StoreFilterTab { UPDATABLE, ALL_INSTALLED, SKIPPED }

@Immutable
data class StoreUpdateRowItem(
    val local: LocalInstalledApp,
    val update: StoreAppUpdate?,
    val best: StoreReleaseInfo?,
    val isSkipped: Boolean,
    val task: StoreTaskState?,
)

@Immutable
data class StoreUpdatesUiState(
    val enabledStores: List<String> = emptyList(),
    val updatable: List<StoreUpdateRowItem> = emptyList(),
    val allInstalled: List<StoreUpdateRowItem> = emptyList(),
    val skipped: List<StoreUpdateRowItem> = emptyList(),
    val lastCheckedAt: Instant? = null,
    val refreshing: Boolean = false,
    val signatures: Map<String, StoreSignatureRecord> = emptyMap(),
)

class StoreUpdatesViewModel(
    app: Application,
    private val stateStore: AppStateStore,
    private val installedCache: InstalledAppCache,
    private val checker: StoreUpdateChecker,
    private val downloadHost: StoreDownloadHost,
) : AndroidViewModel(app) {

    private val refreshingFlow = MutableStateFlow(false)
    // 下拉手势瞬态确认信号：让 Miuix 刷新头在收起后立刻释放列表滚动拦截，长耗时检查进度交由 StoreCheckProgressStrip 承载
    private val _pullRefreshing = MutableStateFlow(false)
    val pullRefreshing: StateFlow<Boolean> = _pullRefreshing.asStateFlow()

    /** 独立暴露检查进度流，避免每完成一家商店就重算整个百行应用列表并触发整页重组。 */
    val checkProgress: StateFlow<StoreCheckProgress?> = checker.progress

    private val storeSlice = stateStore.state
        .map { it.settings.storePrefs to it.storeState }
        .distinctUntilChanged()

    val uiState: StateFlow<StoreUpdatesUiState> = combine(
        storeSlice,
        installedCache.apps,
        downloadHost.states,
        refreshingFlow,
    ) { slice, installed, tasks, refreshing ->
        buildUiState(slice.first, slice.second, installed, tasks, refreshing)
    }.flowOn(Dispatchers.Default).stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = buildUiState(
            prefs = stateStore.state.value.settings.storePrefs,
            storeState = stateStore.state.value.storeState,
            installed = installedCache.apps.value,
            tasks = downloadHost.states.value,
            refreshing = false,
        ),
    )

    init {
        viewModelScope.launch {
            val state = stateStore.state.value
            val prefs = state.settings.storePrefs
            installedCache.ensureLoaded(prefs.includeSystemApps)
            // 首次开启渠道后点进本页（从未检查过）时自动检查，兑现首页「点此检查更新」语义
            if (prefs.enabledStores.isNotEmpty() && state.storeState.lastCheckedAt == null) {
                refresh()
            }
        }
    }

    fun onPullRefresh(onComplete: ((updatableCount: Int) -> Unit)? = null) {
        refresh(onComplete)
        if (_pullRefreshing.compareAndSet(false, true)) {
            viewModelScope.launch {
                delay(80L)
                _pullRefreshing.value = false
            }
        }
    }

    fun refresh(onComplete: ((updatableCount: Int) -> Unit)? = null) {
        if (refreshingFlow.value) return
        viewModelScope.launch {
            val startedAt = SystemClock.elapsedRealtime()
            refreshingFlow.value = true
            var updatableCount: Int? = null
            try {
                val includeSystem = stateStore.state.value.settings.storePrefs.includeSystemApps
                installedCache.refresh(includeSystem)
                updatableCount = checker.checkAll(force = true).updatableCount
            } finally {
                // 批量商店常在 200~400ms 返回，补足最短停留时间避免「正在检查」状态一闪而过
                val remaining = MIN_REFRESH_DISPLAY_MS - (SystemClock.elapsedRealtime() - startedAt)
                if (remaining > 0L) delay(remaining)
                refreshingFlow.value = false
                updatableCount?.let { onComplete?.invoke(it) }
            }
        }
    }

    private fun buildUiState(
        prefs: StorePreferences,
        storeState: StoreState,
        installed: List<LocalInstalledApp>,
        tasks: Map<String, StoreTaskState>,
        refreshing: Boolean,
    ): StoreUpdatesUiState {
        val updates = storeState.updates
        val sigs = storeState.signatures
        val enabledSet = prefs.enabledStores.toSet()
        val deviceOemStore = StoreSystemAppPolicy.deviceOemStoreId()
        val rows = installed.filter { it.packageName !in prefs.excludedPackages }.map { local ->
            val rawUpd = updates[local.packageName]
            val filteredCandidates = rawUpd?.candidates?.filter { cand ->
                cand.storeId in enabledSet &&
                    StoreSystemAppPolicy.isStoreEligibleForApp(local, cand.storeId, deviceOemStore)
            }.orEmpty()
            val upd = if (rawUpd != null && filteredCandidates.isNotEmpty()) {
                rawUpd.copy(candidates = filteredCandidates)
            } else null
            val best = upd?.let {
                StoreCatalog.selectBestCandidate(
                    candidates = it.candidates,
                    enabledOrder = prefs.enabledStores,
                    pinnedStoreId = prefs.pinnedSources[local.packageName],
                    verdictOf = { sid ->
                        StoreUpdateChecker.validSignatureVerdict(
                            sigs[storeState.signatureKey(local.packageName, sid)],
                            local.signatureSha256,
                        )
                    },
                )
            }
            val skippedCode = prefs.skippedVersions[local.packageName] ?: 0L
            val isSkipped = best != null && best.versionCode <= skippedCode
            StoreUpdateRowItem(
                local = local,
                update = upd,
                best = best,
                isSkipped = isSkipped,
                task = tasks[local.packageName],
            )
        }
        return StoreUpdatesUiState(
            enabledStores = prefs.enabledStores,
            // 进行中或失败的任务保留在「可更新」页，避免签名不兼容自动过滤后卡片静默消失、用户看不到红字原因
            updatable = rows.filter { (it.best != null && !it.isSkipped) || it.task != null },
            allInstalled = rows,
            skipped = rows.filter { it.isSkipped },
            lastCheckedAt = storeState.lastCheckedAt,
            refreshing = refreshing,
            signatures = sigs,
        )
    }

    fun startUpdate(local: LocalInstalledApp, release: StoreReleaseInfo) {
        downloadHost.startUpdate(local, release)
    }

    fun checkSingleApp(local: LocalInstalledApp, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            val found = checker.checkSingleApp(local)
            onDone(found)
        }
    }

    fun updateAll() {
        val targets = uiState.value.updatable.mapNotNull { row ->
            val rel = row.best ?: return@mapNotNull null
            if (row.task is StoreTaskState.Downloading || row.task is StoreTaskState.Installing) null
            else row.local to rel
        }
        if (targets.isNotEmpty()) downloadHost.updateAll(targets)
    }

    fun installReady(packageName: String) = downloadHost.installReady(packageName)

    fun cancelDownload(packageName: String) = downloadHost.cancel(packageName)

    fun clearFailure(packageName: String) = downloadHost.clearFailure(packageName)

    fun skipVersion(packageName: String, versionCode: Long) {
        viewModelScope.launch {
            stateStore.update { st ->
                val prefs = st.settings.storePrefs
                st.copy(
                    settings = st.settings.copy(
                        storePrefs = prefs.copy(skippedVersions = prefs.skippedVersions + (packageName to versionCode)),
                    ),
                )
            }
        }
    }

    fun unskipVersion(packageName: String) {
        viewModelScope.launch {
            stateStore.update { st ->
                val prefs = st.settings.storePrefs
                st.copy(
                    settings = st.settings.copy(
                        storePrefs = prefs.copy(skippedVersions = prefs.skippedVersions - packageName),
                    ),
                )
            }
        }
    }

    fun excludePackage(packageName: String) {
        viewModelScope.launch {
            stateStore.update { st ->
                val prefs = st.settings.storePrefs
                st.copy(
                    settings = st.settings.copy(
                        storePrefs = prefs.copy(excludedPackages = prefs.excludedPackages + packageName),
                    ),
                )
            }
        }
    }

    fun unexcludePackage(packageName: String) {
        viewModelScope.launch {
            stateStore.update { st ->
                val prefs = st.settings.storePrefs
                st.copy(
                    settings = st.settings.copy(
                        storePrefs = prefs.copy(excludedPackages = prefs.excludedPackages - packageName),
                    ),
                )
            }
        }
    }

    fun pinSource(packageName: String, storeId: String?) {
        viewModelScope.launch {
            stateStore.update { st ->
                val prefs = st.settings.storePrefs
                val next = if (storeId == null) prefs.pinnedSources - packageName
                else prefs.pinnedSources + (packageName to storeId)
                st.copy(settings = st.settings.copy(storePrefs = prefs.copy(pinnedSources = next)))
            }
        }
    }

    fun isMeteredNetwork(): Boolean {
        val cm = getApplication<Application>().getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        return runCatching { cm?.isActiveNetworkMetered == true }.getOrDefault(false)
    }

    fun shouldPromptMetered(sizeBytes: Long): Boolean {
        if (!isMeteredNetwork()) return false
        return when (val policy = stateStore.state.value.settings.storePrefs.meteredDownloadPolicy) {
            MeteredDownloadPolicy.ALWAYS_ALLOW -> false
            MeteredDownloadPolicy.ALWAYS_ASK -> true
            MeteredDownloadPolicy.OVER_50MB,
            MeteredDownloadPolicy.OVER_200MB -> sizeBytes >= policy.thresholdBytes
        }
    }

    companion object {
        private const val MIN_REFRESH_DISPLAY_MS = 700L
    }
}
