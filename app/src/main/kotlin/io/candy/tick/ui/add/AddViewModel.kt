package io.candy.tick.ui.add

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.candy.tick.data.RepoInfo
import io.candy.tick.data.RepoInfoResult
import io.candy.tick.data.RepoRepository
import io.candy.tick.data.RepoSearchPage
import io.candy.tick.data.SourceError
import io.candy.tick.data.SourceException
import io.candy.tick.data.model.TrackedRepo
import io.candy.tick.util.UrlParser
import io.candy.tick.util.AppMatcher
import io.candy.tick.util.appDisplayName
import io.candy.tick.util.labelOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AddViewModel(
    private val repository: RepoRepository,
    context: android.content.Context,
) : ViewModel() {

    private val appContext = context.applicationContext
    private var linkEdited = false
    private val appNames = mutableMapOf<String, String>()

    // @Immutable：AnimatedContent targetState 与 PreviewCard 参数的稳定判定依赖此承诺
    @Immutable
    sealed interface PreviewState {
        data object Idle : PreviewState
        data object Loading : PreviewState

        /** linkedPackage/linkedLabel 归属预览对象本身：切换预览不串台（页面级会残留上个仓库的关联） */
        data class Loaded(
            val info: RepoInfo,
            val linkedPackage: String? = null,
            val linkedLabel: String? = null,
            val matchingApp: Boolean = false,
        ) : PreviewState

        data class Failed(val error: SourceError) : PreviewState
    }

    @Immutable
    data class UiState(
        val input: String = "",
        val parseError: Boolean = false, // 无法识别的 GitHub 链接
        val preview: PreviewState = PreviewState.Idle,
        val duplicate: Boolean = false,
        val searching: Boolean = false,
        val searched: Boolean = false, // 已完成过一次搜索（区分「还没搜」与「无结果」）
        val results: RepoSearchPage = RepoSearchPage(),
        val resultPackages: Map<Long, String> = emptyMap(),
        val searchError: SourceError? = null,
        val adding: Boolean = false,
    )

    sealed interface Event {
        data class Added(val repoId: Long) : Event
    }

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events: Flow<Event> = _events.receiveAsFlow()

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    // 同一输入区只允许一个搜索、去抖或预览请求持有结果。
    private var lookupJob: Job? = null
    private var addJob: Job? = null
    private var linkJob: Job? = null
    private data class SavedLink(val packageName: String?, val label: String?, val dismissed: Boolean)
    private var previewSavedLink: SavedLink? = null

    init {
        viewModelScope.launch {
            repository.state.map { it.repos }.distinctUntilChanged().collect { repos ->
                val current = state.value
                if (current.results.repositories.isNotEmpty()) {
                    val packages = current.results.linkedPackages(repos)
                    if (packages != current.resultPackages) _state.update { it.copy(resultPackages = packages) }
                }
                val preview = current.preview as? PreviewState.Loaded ?: return@collect
                val saved = repos.savedLink(preview.info.githubId)
                if (saved == previewSavedLink) return@collect
                // While saving, the store publishes this preview's own link before the write returns.
                if (linkEdited || current.adding) {
                    previewSavedLink = saved
                    _state.update { it.copy(duplicate = saved != null) }
                } else {
                    showPreview(preview.info, saved)
                }
            }
        }
    }

    private fun RepoSearchPage.linkedPackages(repos: List<TrackedRepo>): Map<Long, String> {
        val resultIds = repositories.mapTo(HashSet()) { it.githubId }
        return buildMap {
            for (repo in repos) {
                if (repo.id in resultIds) repo.linkedPackage?.let { put(repo.id, it) }
            }
        }
    }

    private fun List<TrackedRepo>.savedLink(repoId: Long): SavedLink? =
        firstOrNull { it.id == repoId }?.let { repo ->
            SavedLink(repo.linkedPackage, repo.noteName.takeIf { repo.autoNote }, repo.autoLinkDismissed)
        }

    fun onInput(text: String) {
        lookupJob?.cancel()
        addJob?.cancel()
        linkJob?.cancel()
        linkEdited = false
        val parsed = UrlParser.parse(text)
        _state.update {
            it.copy(
                input = text, searchError = null, searched = false, searching = false,
                results = RepoSearchPage(), resultPackages = emptyMap(), duplicate = false, adding = false,
                parseError = parsed == null && UrlParser.looksLikeUrl(text),
                preview = PreviewState.Idle,
            )
        }
        if (parsed == null) return
        // 250ms debounce：裸 owner/repo 逐字输入的中间态不再各发一次请求（匿名限额 60/h 很贵）
        lookupJob = viewModelScope.launch {
            delay(250)
            fetchPreview(parsed.first, parsed.second)
        }
    }

    /** 显式提交直链时跳过去抖；已在加载或展示的同一预览不重复请求。 */
    fun submit() {
        val current = _state.value
        val text = current.input
        if (current.adding || text.isBlank() || current.parseError) return
        val parsed = UrlParser.parse(text)
        if (parsed != null) {
            if (current.preview == PreviewState.Idle || current.preview is PreviewState.Failed) {
                lookupJob?.cancel()
                lookupJob = viewModelScope.launch { fetchPreview(parsed.first, parsed.second) }
            }
            return
        }
        lookupJob?.cancel()
        linkJob?.cancel()
        lookupJob = viewModelScope.launch {
            _state.update {
                it.copy(searching = true, searchError = null, results = RepoSearchPage(), resultPackages = emptyMap(), preview = PreviewState.Idle)
            }
            try {
                val results = repository.search(text)
                val packages = results.linkedPackages(repository.state.value.repos)
                _state.update { it.copy(searching = false, searched = true, results = results, resultPackages = packages) }
            } catch (e: SourceException) {
                _state.update { it.copy(searching = false, searchError = e.error) }
            }
        }
    }

    /** 预览态返回搜索结果（results 保留时的退路） */
    fun dismissPreview() {
        lookupJob?.cancel()
        addJob?.cancel()
        linkJob?.cancel()
        linkEdited = false
        _state.update { it.copy(preview = PreviewState.Idle, duplicate = false, adding = false) }
    }

    fun pickResult(info: RepoInfo) {
        if (_state.value.preview != PreviewState.Idle) return
        lookupJob?.cancel()
        linkEdited = false
        showPreview(info)
        lookupJob = viewModelScope.launch {
            try {
                val fresh = repository.addPreview(info.owner, info.repo) as? RepoInfoResult.Fetched ?: return@launch
                if (fresh.info.githubId == info.githubId) _state.update { state ->
                    val preview = state.preview as? PreviewState.Loaded
                    if (preview?.info?.githubId == info.githubId) state.copy(preview = preview.copy(info = fresh.info))
                    else state
                }
            } catch (_: SourceException) {
                // A search result already contains the information needed to show and add this repo.
            }
        }
    }

    private fun showPreview(info: RepoInfo, saved: SavedLink? = repository.state.value.repos.savedLink(info.githubId)) {
        linkJob?.cancel()
        // 新预览实例使旧的手动选择失效（那是针对上一个预览的）：必须复位，
        // 否则 resolveLink 的 linkEdited 早退会让 matchingApp 永不复位、确认按钮永久置灰
        linkEdited = false
        previewSavedLink = saved
        val linkedPkg = saved?.packageName
        val name = linkedPkg?.let { rememberAppName(it, saved.label) }
        val shouldMatch = linkedPkg == null && saved?.dismissed != true
        _state.update {
            it.copy(
                preview = PreviewState.Loaded(
                    info = info,
                    linkedPackage = linkedPkg,
                    linkedLabel = name,
                    matchingApp = shouldMatch,
                ),
                duplicate = saved != null,
            )
        }
        if (shouldMatch || (linkedPkg != null && name == null)) {
            linkJob = viewModelScope.launch { resolveLink(info, linkedPkg) }
        }
    }

    private suspend fun fetchPreview(owner: String, repo: String) {
        _state.update { it.copy(preview = PreviewState.Loading, duplicate = false) }
        try {
            when (val result = repository.addPreview(owner, repo)) {
                is RepoInfoResult.Fetched -> showPreview(result.info)
                RepoInfoResult.NotModified -> _state.update { it.copy(preview = PreviewState.Failed(SourceError.Http(304))) }
            }
        } catch (e: SourceException) {
            _state.update { it.copy(preview = PreviewState.Failed(e.error)) }
        }
    }

    private suspend fun resolveLink(info: RepoInfo, pkg: String?) {
        val link = withContext(Dispatchers.IO) {
            if (pkg != null) {
                pkg to labelOf(appContext, pkg)
            } else {
                AppMatcher.bestAutoMatch(appContext, info.owner, info.repo)?.let { candidate ->
                    candidate.packageName to (appDisplayName(candidate.packageName, candidate.label)
                        ?: labelOf(appContext, candidate.packageName))
                }
            }
        }
        val current = _state.value.preview as? PreviewState.Loaded ?: return
        if (current.info.githubId != info.githubId || linkEdited) return
        val name = link?.let { (pkg, label) -> rememberAppName(pkg, label) }
        _state.update { it.copy(preview = current.copy(linkedPackage = link?.first, linkedLabel = name, matchingApp = false)) }
    }

    private fun rememberAppName(packageName: String, label: String?): String? {
        appDisplayName(packageName, label)?.let { appNames[packageName] = it }
        return appNames[packageName]
    }

    fun setLinkedApp(repoId: Long, packageName: String?, label: String?) {
        val current = _state.value.preview as? PreviewState.Loaded ?: return
        if (current.info.githubId != repoId || _state.value.adding) return
        linkJob?.cancel()
        linkEdited = true
        val name = packageName?.let { rememberAppName(it, label) }
        _state.update { it.copy(preview = current.copy(linkedPackage = packageName, linkedLabel = name, matchingApp = false)) }
    }

    fun confirmAdd(repoId: Long) {
        val selected = _state.value.preview as? PreviewState.Loaded ?: return
        if (selected.info.githubId != repoId || _state.value.adding) return
        addJob = viewModelScope.launch {
            _state.update { it.copy(adding = true) }
            // A quick tap is still a confirmation while the optional local match is running.
            // Editing the input or dismissing the preview cancels this wait with the add job.
            val ready = state.first {
                val current = it.preview as? PreviewState.Loaded
                it.duplicate || current == null || current.info.githubId != repoId || !current.matchingApp
            }
            val preview = ready.preview as? PreviewState.Loaded ?: return@launch
            if (preview.info.githubId != repoId) return@launch
            if (ready.duplicate) {
                if (linkEdited) {
                    repository.setLinkedPackage(preview.info.githubId, preview.linkedPackage, preview.linkedLabel)
                }
                // 取消可能落在「已写库、事件未发」之间；NonCancellable 保证写入成功必有完成信号
                withContext(NonCancellable) { _events.send(Event.Added(preview.info.githubId)) }
                return@launch
            }
            when (val result = repository.addRepo(preview.info, preview.linkedPackage, preview.linkedLabel)) {
                is RepoRepository.AddResult.Added -> {
                    withContext(NonCancellable) { _events.send(Event.Added(result.repo.id)) }
                }
                is RepoRepository.AddResult.Duplicate -> {
                    _state.update { it.copy(duplicate = true, adding = false) }
                }
                is RepoRepository.AddResult.Failed -> {
                    _state.update { it.copy(preview = PreviewState.Failed(result.error), adding = false) }
                }
            }
        }
    }
}
