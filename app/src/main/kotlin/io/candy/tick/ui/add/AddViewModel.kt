package io.candy.tick.ui.add

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.candy.tick.data.RepoInfo
import io.candy.tick.data.RepoInfoResult
import io.candy.tick.data.RepoRepository
import io.candy.tick.data.SourceError
import io.candy.tick.data.SourceException
import io.candy.tick.util.UrlParser
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AddViewModel(private val repository: RepoRepository) : ViewModel() {

    sealed interface PreviewState {
        data object Idle : PreviewState
        data object Loading : PreviewState
        data class Loaded(val info: RepoInfo) : PreviewState
        data class Failed(val error: SourceError) : PreviewState
    }

    data class UiState(
        val input: String = "",
        val parseError: Boolean = false, // 无法识别的 GitHub 链接
        val preview: PreviewState = PreviewState.Idle,
        val duplicate: Boolean = false,
        val searching: Boolean = false,
        val searched: Boolean = false, // 已完成过一次搜索（区分「还没搜」与「无结果」）
        val results: List<RepoInfo> = emptyList(),
        val searchFailed: Boolean = false,
        val linkedPackage: String? = null,
        val linkedLabel: String? = null,
        val adding: Boolean = false,
    )

    sealed interface Event {
        data class Added(val repoId: Long) : Event
    }

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events: Flow<Event> = _events.receiveAsFlow()

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var fetchingKey: String? = null

    fun onInput(text: String) {
        _state.update { it.copy(input = text, searchFailed = false, searched = false) }
        val parsed = UrlParser.parse(text)
        if (parsed == null) {
            val looksTried = UrlParser.looksLikeUrl(text)
            _state.update {
                it.copy(
                    parseError = looksTried,
                    preview = if (looksTried) PreviewState.Idle else it.preview,
                    duplicate = false,
                )
            }
            return
        }
        _state.update { it.copy(parseError = false) }
        val key = "${parsed.first}/${parsed.second}"
        if (key == fetchingKey) return
        fetchingKey = key
        fetchPreview(parsed.first, parsed.second)
    }

    /** 提交：URL → 已在解析；否则按关键词搜索 */
    fun submit() {
        val text = _state.value.input
        if (UrlParser.parse(text) != null) return
        if (text.isBlank()) return
        viewModelScope.launch {
            _state.update { it.copy(searching = true, searchFailed = false, results = emptyList()) }
            val results = runCatching { repository.search(text) }.getOrNull()
            _state.update {
                if (results == null) {
                    it.copy(searching = false, searchFailed = true)
                } else {
                    it.copy(searching = false, searched = true, results = results)
                }
            }
        }
    }

    fun pickResult(info: RepoInfo) {
        fetchingKey = "${info.owner}/${info.repo}"
        fetchPreview(info.owner, info.repo)
    }

    private fun fetchPreview(owner: String, repo: String) {
        viewModelScope.launch {
            _state.update { it.copy(preview = PreviewState.Loading, duplicate = false, results = emptyList()) }
            try {
                when (val result = repository.addPreview(owner, repo)) {
                    is RepoInfoResult.NotModified -> Unit // 预览无 etag，不会走到
                    is RepoInfoResult.Fetched -> {
                        val dup = repository.state.value.repos.any { it.id == result.info.githubId }
                        _state.update { it.copy(preview = PreviewState.Loaded(result.info), duplicate = dup) }
                    }
                }
            } catch (e: SourceException) {
                _state.update { it.copy(preview = PreviewState.Failed(e.error)) }
            }
        }
    }

    fun setLinkedApp(packageName: String?, label: String?) {
        _state.update { it.copy(linkedPackage = packageName, linkedLabel = label) }
    }

    fun confirmAdd() {
        val preview = _state.value.preview as? PreviewState.Loaded ?: return
        if (_state.value.adding) return
        viewModelScope.launch {
            _state.update { it.copy(adding = true) }
            when (val result = repository.addRepo(preview.info.owner, preview.info.repo)) {
                is RepoRepository.AddResult.Added -> {
                    _state.value.linkedPackage?.let { pkg ->
                        repository.setLinkedPackage(result.repo.id, pkg)
                    }
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
