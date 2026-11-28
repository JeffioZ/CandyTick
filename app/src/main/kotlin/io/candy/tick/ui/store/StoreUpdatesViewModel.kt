package io.candy.tick.ui.store

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.os.SystemClock
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.candy.tick.data.AppStateStore
import io.candy.tick.data.model.StoreAppUpdate
import io.candy.tick.data.model.StorePreferences
import io.candy.tick.data.model.StoreReleaseInfo
import io.candy.tick.data.model.StoreSignatureRecord
import io.candy.tick.data.model.StoreState
import io.candy.tick.data.model.withStorePreferenceFor
import io.candy.tick.data.store.InstalledAppCache
import io.candy.tick.data.store.InstalledAppScan
import io.candy.tick.data.store.LocalInstalledApp
import io.candy.tick.data.store.StoreCatalog
import io.candy.tick.data.store.StoreCheckSummary
import io.candy.tick.data.store.SingleStoreCheckResult
import io.candy.tick.data.store.StoreCheckProgress
import io.candy.tick.data.store.StoreDownloadHost
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
    val skippedThrough: Long?,
    val task: StoreTaskState?,
) {
    fun isVersionSkipped(versionCode: Long): Boolean = skippedThrough?.let { versionCode <= it } == true
    val isSkipped: Boolean get() = best?.let { isVersionSkipped(it.versionCode) } == true

    // A manually selected alternative stays installable in its own source sheet.
    val recommendedTask: StoreTaskState?
        get() = if (task is StoreTaskState.ReadyToInstall && (isSkipped || !task.matches(best))) null else task

    val displayedRelease: StoreReleaseInfo?
        get() {
            val active = recommendedTask ?: return best
            return update?.candidates?.firstOrNull { release ->
                release.storeId == active.storeId && when (active) {
                    is StoreTaskState.Failed -> release.versionCode == active.versionCode
                    is StoreTaskState.ReadyToInstall -> active.matches(release)
                    else -> release.versionName == active.versionName
                }
            }
        }
}

@Immutable
data class StoreUpdatesUiState(
    val enabledStores: List<String> = emptyList(),
    val updatable: List<StoreUpdateRowItem> = emptyList(),
    val allInstalled: List<StoreUpdateRowItem> = emptyList(),
    val skipped: List<StoreUpdateRowItem> = emptyList(),
    val lastCheckedAt: Instant? = null,
    val refreshing: Boolean = false,
    val signatures: Map<String, StoreSignatureRecord> = emptyMap(),
    val lastCheckComplete: Boolean? = null,
    val failedStores: List<String> = emptyList(),
    val failedStoreReason: String? = null,
    val autoInstall: Boolean = true,
    val pinnedSources: Map<String, String> = emptyMap(),
)

internal data class StoreBatchPlan(
    val downloads: List<Pair<LocalInstalledApp, StoreReleaseInfo>>,
    val ready: List<Pair<String, StoreTaskState.ReadyToInstall>>,
) {
    val count: Int get() = downloads.size + ready.size
    val estimatedBytes: Long? get() = downloads.sumOf { it.second.exactSizeBytes?.takeIf { bytes -> bytes > 0L } ?: return null }
}

internal fun StoreUpdatesUiState.batchPlan(canInstall: Boolean): StoreBatchPlan {
    val downloads = mutableListOf<Pair<LocalInstalledApp, StoreReleaseInfo>>()
    val ready = mutableListOf<Pair<String, StoreTaskState.ReadyToInstall>>()
    for (row in updatable) {
        val release = row.best ?: continue
        if (row.isSkipped) continue
        when (val task = row.recommendedTask) {
            null, is StoreTaskState.Failed -> downloads += row.local to release
            is StoreTaskState.ReadyToInstall -> if (canInstall && autoInstall && task.signatureVerified && !task.is32BitWarning) {
                ready += row.local.packageName to task
            }
            else -> Unit
        }
    }
    return StoreBatchPlan(downloads, ready)
}

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
        .map { Triple(it.settings.storePrefs, it.storeState, it.settings.autoInstallDownload) }
        .distinctUntilChanged()

    val tasks = downloadHost.states

    val uiState: StateFlow<StoreUpdatesUiState> = combine(
        storeSlice,
        installedCache.scan,
        tasks.distinctUntilChanged { before, after ->
            before.keys == after.keys && before.all { (pkg, old) ->
                val current = after[pkg]
                old == current || (old is StoreTaskState.Downloading && current is StoreTaskState.Downloading &&
                    old.storeId == current.storeId && old.versionName == current.versionName)
            }
        },
        refreshingFlow,
    ) { slice, installed, tasks, refreshing ->
        buildUiState(slice.first, slice.second, installed, tasks, refreshing, slice.third)
    }.flowOn(Dispatchers.Default).stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = buildUiState(
            prefs = stateStore.state.value.settings.storePrefs,
            storeState = stateStore.state.value.storeState,
            installed = installedCache.scan.value,
            tasks = downloadHost.states.value,
            refreshing = false,
            autoInstall = stateStore.state.value.settings.autoInstallDownload,
        ),
    )

    init {
        viewModelScope.launch {
            val state = stateStore.state.value
            val prefs = state.settings.storePrefs
            if (state.storeState.lastCheckComplete != null) installedCache.ensureLoaded(prefs.includeSystemApps)
        }
    }

    fun onPullRefresh(onComplete: ((StoreCheckSummary) -> Unit)? = null) {
        refresh(onComplete)
        if (_pullRefreshing.compareAndSet(false, true)) {
            viewModelScope.launch {
                delay(80L)
                _pullRefreshing.value = false
            }
        }
    }

    fun refresh(onComplete: ((StoreCheckSummary) -> Unit)? = null) {
        if (!refreshingFlow.compareAndSet(expect = false, update = true)) return
        viewModelScope.launch {
            val startedAt = SystemClock.elapsedRealtime()
            var summary: StoreCheckSummary? = null
            try {
                summary = checker.checkAll(force = true, scanInstalled = installedCache::refresh)
            } finally {
                // 批量商店常在 200~400ms 返回，补足最短停留时间避免「正在检查」状态一闪而过
                val remaining = MIN_REFRESH_DISPLAY_MS - (SystemClock.elapsedRealtime() - startedAt)
                if (remaining > 0L) delay(remaining)
                refreshingFlow.value = false
                summary?.let { onComplete?.invoke(it) }
            }
        }
    }

    private fun buildUiState(
        prefs: StorePreferences,
        storeState: StoreState,
        installed: InstalledAppScan?,
        tasks: Map<String, StoreTaskState>,
        refreshing: Boolean,
        autoInstall: Boolean,
    ): StoreUpdatesUiState {
        val updates = storeState.updates
        val sigs = storeState.signatures
        val rows = installed?.apps.orEmpty().filter { it.packageName !in prefs.excludedPackages && (prefs.includeSystemApps || !it.isSystemApp) }.map { local ->
            val rawUpd = updates[local.packageName]
            val filteredCandidates = rawUpd?.let { StoreCatalog.eligibleCandidates(it, prefs, local) }.orEmpty()
            val upd = if (rawUpd != null && filteredCandidates.isNotEmpty()) {
                rawUpd.copy(candidates = filteredCandidates)
            } else null
            val best = upd?.let {
                StoreCatalog.actionableCandidate(it, prefs, sigs, local, includeSkipped = true)
            }
            StoreUpdateRowItem(
                local = local,
                update = upd,
                best = best,
                skippedThrough = prefs.skippedVersions[local.packageName],
                task = tasks[local.packageName]?.takeUnless { task ->
                    task is StoreTaskState.Failed && filteredCandidates.none {
                        it.storeId == task.storeId && it.versionCode == task.versionCode
                    }
                },
            )
        }
        return StoreUpdatesUiState(
            enabledStores = prefs.enabledStores,
            // 进行中或失败的任务保留在「可更新」页，避免签名不兼容自动过滤后卡片静默消失、用户看不到红字原因
            updatable = rows.filter { (it.best != null && !it.isSkipped) || it.recommendedTask != null },
            allInstalled = rows,
            skipped = rows.filter { it.isSkipped },
            lastCheckedAt = storeState.lastCheckedAt,
            refreshing = refreshing || (installed == null && storeState.lastCheckComplete != null),
            signatures = sigs,
            lastCheckComplete = if (storeState.lastCheckComplete == true && installed?.complete == false) false else storeState.lastCheckComplete,
            failedStores = storeState.lastFailedStoreIds,
            failedStoreReason = storeState.lastFailedStoreIds.map { storeState.health[it]?.lastError }.distinct().singleOrNull(),
            autoInstall = autoInstall,
            pinnedSources = prefs.pinnedSources,
        )
    }

    fun startUpdate(local: LocalInstalledApp, release: StoreReleaseInfo, meteredConfirmed: Boolean) {
        downloadHost.startUpdate(local, release, meteredConfirmed)
    }

    fun checkSingleApp(local: LocalInstalledApp, onDone: (SingleStoreCheckResult) -> Unit) {
        viewModelScope.launch {
            val found = checker.checkSingleApp(local)
            onDone(found)
        }
    }

    internal fun updateAll(plan: StoreBatchPlan, meteredConfirmed: Boolean, canInstall: Boolean): Int {
        // A confirmation applies only to the packages and versions shown when it opened.
        val current = uiState.value.batchPlan(canInstall)
        val downloads = current.downloads.filter { (app, release) ->
            plan.downloads.any { (approvedApp, approvedRelease) ->
                approvedApp.packageName == app.packageName && approvedRelease == release
            }
        }
        val installs = current.ready.count { (pkg, expected) ->
            (pkg to expected) in plan.ready && downloadHost.installReady(pkg, expected, requestPermission = false)
        }
        return installs + downloadHost.updateAll(downloads, meteredConfirmed)
    }

    fun installReady(packageName: String, expected: StoreTaskState.ReadyToInstall) {
        downloadHost.installReady(packageName, expected)
    }

    fun cancelDownload(packageName: String) = downloadHost.cancel(packageName)

    fun clearFailure(packageName: String) = downloadHost.clearFailure(packageName)

    fun setSkippedVersion(packageName: String, versionCode: Long?, expected: Long? = null) {
        updatePreference(packageName) { prefs ->
            val skipped = prefs.skippedVersions
            if (expected != null && skipped[packageName] != expected) prefs
            else prefs.copy(skippedVersions = if (versionCode == null) skipped - packageName else skipped + (packageName to versionCode))
        }
    }

    fun excludePackage(packageName: String) {
        updatePreference(packageName) { it.copy(excludedPackages = it.excludedPackages + packageName) }
    }

    fun unexcludePackage(packageName: String) {
        updatePreference(packageName) { it.copy(excludedPackages = it.excludedPackages - packageName) }
    }

    fun pinSource(packageName: String, storeId: String?) {
        updatePreference(packageName) { prefs ->
            val pins = if (storeId == null) prefs.pinnedSources - packageName else prefs.pinnedSources + (packageName to storeId)
            prefs.copy(pinnedSources = pins)
        }
    }

    private fun updatePreference(packageName: String, change: (StorePreferences) -> StorePreferences) {
        viewModelScope.launch {
            stateStore.update { st ->
                st.withStorePreferenceFor(packageName, observed = true, transform = change)
            }
        }
    }

    fun isMeteredNetwork(): Boolean {
        val cm = getApplication<Application>().getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        return runCatching { cm?.isActiveNetworkMetered ?: true }.getOrDefault(true)
    }

    fun shouldPromptMetered(sizeBytes: Long): Boolean {
        if (!isMeteredNetwork()) return false
        return stateStore.state.value.settings.storePrefs.meteredDownloadPolicy.requiresConfirmation(sizeBytes)
    }

    companion object {
        private const val MIN_REFRESH_DISPLAY_MS = 700L
    }
}
