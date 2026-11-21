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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
        val searchFailed: Boolean = false,
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

    fun onInput(text: String) {
        lookupJob?.cancel()
        addJob?.cancel()
        linkEdited = false
        val parsed = UrlParser.parse(text)
        _state.update {
            it.copy(
                input = text, searchFailed = false, searched = false, searching = false,
                results = RepoSearchPage(), duplicate = false, adding = false,
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

    /** 预览失败重试：URL 路径清键重拉；搜索关键词路径改走重搜（此前重试静默空转=死路） */
    fun retryPreview() {
        lookupJob?.cancel()
        val parsed = UrlParser.parse(_state.value.input)
        if (parsed == null) {
            if (_state.value.input.isNotBlank()) submit()
            return
        }
        lookupJob = viewModelScope.launch { fetchPreview(parsed.first, parsed.second) }
    }

    /** 提交：URL → 已在解析；否则按关键词搜索 */
    fun submit() {
        val text = _state.value.input
        if (UrlParser.parse(text) != null) return
        if (text.isBlank()) return
        lookupJob?.cancel()
        lookupJob = viewModelScope.launch {
            _state.update {
                it.copy(searching = true, searchFailed = false, results = RepoSearchPage(), preview = PreviewState.Idle)
            }
            try {
                val results = repository.search(text)
                _state.update { it.copy(searching = false, searched = true, results = results) }
            } catch (_: SourceException) {
                _state.update { it.copy(searching = false, searchFailed = true) }
            }
        }
    }

    /** 预览态返回搜索结果（results 保留时的退路） */
    fun dismissPreview() {
        lookupJob?.cancel()
        addJob?.cancel()
        linkEdited = false
        _state.update { it.copy(preview = PreviewState.Idle, duplicate = false, adding = false) }
    }

    fun pickResult(info: RepoInfo) {
        lookupJob?.cancel()
        linkEdited = false
        val tracked = showPreview(info)
        lookupJob = viewModelScope.launch {
            launch { resolveLink(info, tracked) }
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

    private fun showPreview(info: RepoInfo): TrackedRepo? {
        val tracked = repository.state.value.repos.find { it.id == info.githubId }
        val linkedPkg = tracked?.linkedPackage
        val name = linkedPkg?.let { pkg ->
            appNames[pkg] ?: rememberAppName(pkg, tracked.noteName?.takeIf { tracked.autoNote })
        }
        _state.update {
            it.copy(
                preview = PreviewState.Loaded(
                    info = info,
                    linkedPackage = linkedPkg,
                    linkedLabel = name,
                    matchingApp = tracked == null,
                ),
                duplicate = tracked != null,
            )
        }
        return tracked
    }

    private suspend fun fetchPreview(owner: String, repo: String) {
        _state.update { it.copy(preview = PreviewState.Loading, duplicate = false) }
        try {
            val result = repository.addPreview(owner, repo) as? RepoInfoResult.Fetched ?: return
            val info = result.info
            resolveLink(info, showPreview(info))
        } catch (e: SourceException) {
            _state.update { it.copy(preview = PreviewState.Failed(e.error)) }
        }
    }

    private suspend fun resolveLink(info: RepoInfo, tracked: TrackedRepo?) {
        if (linkEdited) return
        val pkg = tracked?.linkedPackage
        val preview = _state.value.preview as? PreviewState.Loaded ?: return
        if (preview.info.githubId != info.githubId || (pkg != null && preview.linkedLabel != null)) return
        val link = withContext(Dispatchers.IO) {
            when {
                pkg != null -> pkg to labelOf(appContext, pkg)
                tracked?.autoLinkDismissed == true -> null
                else -> AppMatcher.bestAutoMatch(appContext, info.owner, info.repo)?.let { candidate ->
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

    fun setLinkedApp(packageName: String?, label: String?) {
        linkEdited = true
        val name = packageName?.let { rememberAppName(it, label) }
        _state.update { s ->
            val p = s.preview as? PreviewState.Loaded ?: return@update s
            s.copy(preview = p.copy(linkedPackage = packageName, linkedLabel = name, matchingApp = false))
        }
    }

    fun confirmAdd() {
        val preview = _state.value.preview as? PreviewState.Loaded ?: return
        if (_state.value.adding || preview.matchingApp) return
        addJob = viewModelScope.launch {
            _state.update { it.copy(adding = true) }
            if (_state.value.duplicate) {
                if (linkEdited) {
                    repository.setLinkedPackage(preview.info.githubId, preview.linkedPackage, preview.linkedLabel)
                }
                _events.send(Event.Added(preview.info.githubId))
                return@launch
            }
            when (val result = repository.addRepo(preview.info, preview.linkedPackage, preview.linkedLabel)) {
                is RepoRepository.AddResult.Added -> {
                    _state.update { it.copy(adding = false) }
                    _events.send(Event.Added(result.repo.id))
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
