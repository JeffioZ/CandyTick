package io.candy.tick.ui.detail

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.candy.tick.data.GitHubApi
import io.candy.tick.data.InstallManager
import io.candy.tick.data.RepoRepository
import io.candy.tick.data.SourceError
import io.candy.tick.data.model.ApkAsset
import io.candy.tick.data.model.CommitRecord
import io.candy.tick.data.model.DownloadedApk
import io.candy.tick.data.model.RepoSnapshot
import io.candy.tick.data.model.ReleaseRecord
import io.candy.tick.data.model.TrackedRepo
import io.candy.tick.data.model.statusAt
import java.io.File
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class DetailViewModel(
    val repoId: Long,
    private val repository: RepoRepository,
    private val githubApi: GitHubApi,
    private val installManager: InstallManager,
    context: Context,
) : ViewModel() {

    sealed interface DownloadState {
        data class Active(val assetName: String, val progress: Float) : DownloadState
        data class Done(val assetName: String, val file: File) : DownloadState
        data class Failed(val assetName: String) : DownloadState
    }

    data class UiState(
        val repo: TrackedRepo? = null,
        val snapshot: RepoSnapshot? = null,
        val refreshing: Boolean = false,
        val banner: SourceError? = null,
        val commits: List<CommitRecord> = emptyList(),
        val commitsLoading: Boolean = false,
        val commitsEnd: Boolean = false,
        val commitsFailed: Boolean = false,
        val releases: List<ReleaseRecord> = emptyList(),
        val releasesLoading: Boolean = false,
        val releasesEnd: Boolean = false,
        val releasesFailed: Boolean = false,
        val download: DownloadState? = null,
        val selectedAssets: Map<String, ApkAsset> = emptyMap(), // releaseKey(tag) → 手选覆盖
    )

    sealed interface Event {
        data class InstallResultEvent(val result: InstallManager.InstallResult) : Event
    }

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events: Flow<Event> = _events.receiveAsFlow()

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val apkDir: File = File(context.filesDir, "apks/$repoId")
    private var commitsPage = 1
    private var releasesPage = 1

    init {
        viewModelScope.launch {
            repository.state.collect { s ->
                _state.update {
                    it.copy(repo = s.repos.find { r -> r.id == repoId }, snapshot = s.snapshots[repoId])
                }
            }
        }
        viewModelScope.launch {
            installManager.results.collect { result ->
                _events.send(Event.InstallResultEvent(result))
            }
        }
        loadCommits()
        loadReleases()
    }

    /** 下拉刷新：刷新快照 + 重置提交 / Release 列表（与首页同一语义） */
    fun refresh() {
        if (_state.value.refreshing) return
        viewModelScope.launch {
            _state.update { it.copy(refreshing = true, banner = null) }
            val error = repository.refreshRepo(repoId)
            if (error == null) {
                commitsPage = 1
                releasesPage = 1
                _state.update {
                    it.copy(
                        commits = emptyList(), commitsEnd = false, commitsFailed = false,
                        releases = emptyList(), releasesEnd = false, releasesFailed = false,
                    )
                }
                loadCommits()
                loadReleases()
            } else {
                _state.update { it.copy(banner = error) } // 旧数据保留
            }
            _state.update { it.copy(refreshing = false) }
        }
    }

    fun loadCommits() {
        val s = _state.value
        if (s.commitsLoading || s.commitsEnd) return
        viewModelScope.launch {
            _state.update { it.copy(commitsLoading = true, commitsFailed = false) }
            val page = commitsPage
            val more = runCatching { repository.commits(repoId, page) }
            _state.update {
                when (val data = more.getOrNull()) {
                    null -> it.copy(commitsLoading = false, commitsFailed = true)
                    else -> {
                        if (data.isEmpty()) {
                            it.copy(commitsLoading = false, commitsEnd = true)
                        } else {
                            commitsPage = page + 1
                            it.copy(
                                commits = it.commits + data,
                                commitsLoading = false,
                                commitsEnd = data.size < 30,
                            )
                        }
                    }
                }
            }
        }
    }

    fun loadReleases() {
        val s = _state.value
        if (s.releasesLoading || s.releasesEnd) return
        viewModelScope.launch {
            _state.update { it.copy(releasesLoading = true, releasesFailed = false) }
            val page = releasesPage
            val more = runCatching { repository.releases(repoId, page) }
            _state.update {
                when (val data = more.getOrNull()) {
                    null -> it.copy(releasesLoading = false, releasesFailed = true)
                    else -> {
                        if (data.isEmpty()) {
                            it.copy(releasesLoading = false, releasesEnd = true)
                        } else {
                            releasesPage = page + 1
                            it.copy(
                                releases = it.releases + data,
                                releasesLoading = false,
                                releasesEnd = data.size < 30,
                            )
                        }
                    }
                }
            }
        }
    }

    fun selectAsset(releaseKey: String, asset: ApkAsset) {
        _state.update { it.copy(selectedAssets = it.selectedAssets + (releaseKey to asset)) }
    }

    /** 下载 APK → 私有目录（下载保留；同仓库只留最新一份），完成后记录到 TrackedRepo */
    fun downloadAsset(release: ReleaseRecord, asset: ApkAsset) {
        if (_state.value.download is DownloadState.Active) return
        viewModelScope.launch {
            _state.update { it.copy(download = DownloadState.Active(asset.name, 0f)) }
            val dest = File(apkDir, asset.name)
            val result = runCatching {
                githubApi.download(asset.url, dest) { p ->
                    _state.update { st ->
                        val d = st.download
                        if (d is DownloadState.Active) st.copy(download = d.copy(progress = p)) else st
                    }
                }
            }
            result.fold(
                onSuccess = { file ->
                    // 清理同仓库旧文件（下载保留只留当前）
                    apkDir.listFiles()?.filter { it != file }?.forEach { it.delete() }
                    _state.update { it.copy(download = DownloadState.Done(asset.name, file)) }
                    repository.setDownloadedApk(repoId, DownloadedApk(asset.name, release.tagName))
                },
                onFailure = {
                    _state.update { it.copy(download = DownloadState.Failed(asset.name)) } // 文件已保留，可重试
                },
            )
        }
    }

    fun installFile(file: File) = installManager.installApk(file)

    fun retryDownload(release: ReleaseRecord, asset: ApkAsset) = downloadAsset(release, asset)

    /** 下载保留复用：已下载过的文件直接安装 */
    fun installDownloaded(repo: TrackedRepo): Boolean {
        val record = repo.downloadedApk ?: return false
        val file = File(apkDir, record.fileName)
        if (!file.exists()) return false
        installManager.installApk(file)
        return true
    }

    fun setLinkedApp(packageName: String?) {
        viewModelScope.launch { repository.setLinkedPackage(repoId, packageName) }
    }

    fun dismissBanner() = _state.update { it.copy(banner = null) }
}
