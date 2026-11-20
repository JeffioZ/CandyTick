package io.candy.tick.ui.add

import androidx.compose.runtime.Immutable
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

class AddViewModel(
    private val repository: RepoRepository,
    context: android.content.Context,
) : ViewModel() {

    private val appContext = context.applicationContext
    private var userClearedLink = false // 用户主动清除过关联：本会话不再自动关联

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
        val results: List<RepoInfo> = emptyList(),
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

    private var fetchingKey: String? = null
    private var parseJob: kotlinx.coroutines.Job? = null

    fun onInput(text: String) {
        _state.update {
            it.copy(
                input = text, searchFailed = false, searched = false,
                // 输入已变，旧预览与输入不匹配 → 失效（URL 粘贴场景随后立即重新 Loading）
                preview = PreviewState.Idle,
            )
        }
        parseJob?.cancel()
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
        // 250ms debounce：裸 owner/repo 逐字输入的中间态不再各发一次请求（匿名限额 60/h 很贵）
        parseJob = viewModelScope.launch {
            kotlinx.coroutines.delay(250)
            val key = "${parsed.first}/${parsed.second}"
            if (key == fetchingKey) return@launch
            fetchingKey = key
            fetchPreview(parsed.first, parsed.second)
        }
    }

    /** 预览失败重试：清键后按当前输入重新解析拉取 */
    fun retryPreview() {
        parseJob?.cancel()
        fetchingKey = null
        val parsed = UrlParser.parse(_state.value.input) ?: return
        fetchingKey = "${parsed.first}/${parsed.second}"
        fetchPreview(parsed.first, parsed.second)
    }

    /** 提交：URL → 已在解析；否则按关键词搜索 */
    fun submit() {
        val text = _state.value.input
        if (UrlParser.parse(text) != null) return
        if (text.isBlank()) return
        viewModelScope.launch {
            _state.update {
                it.copy(searching = true, searchFailed = false, results = emptyList(), preview = PreviewState.Idle)
            }
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

    /** 预览态返回搜索结果（results 保留时的退路） */
    fun dismissPreview() {
        fetchingKey = null
        _state.update { it.copy(preview = PreviewState.Idle, duplicate = false) }
    }

    fun pickResult(info: RepoInfo) {
        fetchingKey = "${info.owner}/${info.repo}"
        // 搜索结果与 addPreview 返回同构（同一 GhRepo.toInfo 转换）：点击即用已知数据直出预览，
        // 不为已有信息闪 loading（skill: tap-feedback-speed <100ms）；duplicate 本地库同步判定；
        // 已订阅仓库的现有关联一并直出（此前 linked 页面级残留 + 异步才补，先「未关联」再跳变）
        val tracked = repository.state.value.repos.find { it.id == info.githubId }
        val linkedPkg = tracked?.linkedPackage
        _state.update {
            it.copy(
                preview = PreviewState.Loaded(
                    info = info,
                    linkedPackage = linkedPkg,
                    linkedLabel = linkedPkg?.let(::appLabel),
                ),
                duplicate = tracked != null,
            )
        }
        fetchPreview(info.owner, info.repo, silent = true)
    }

    private fun appLabel(pkg: String): String? =
        io.candy.tick.util.labelOf(appContext, pkg) ?: pkg

    private fun fetchPreview(owner: String, repo: String, silent: Boolean = false) {
        val key = "$owner/$repo"
        viewModelScope.launch {
            // 保留 results：预览态可「返回搜索结果」重选（此前清空导致无退路）
            if (!silent) _state.update { it.copy(preview = PreviewState.Loading, duplicate = false) }
            try {
                when (val result = repository.addPreview(owner, repo)) {
                    is RepoInfoResult.NotModified -> Unit // 预览无 etag，不会走到
                    is RepoInfoResult.Fetched -> {
                        val dup = repository.state.value.repos.any { it.id == result.info.githubId }
                        // 页面已被重置（fetchingKey 变更）则丢弃过期结果
                        if (fetchingKey == key) {
                            // 保留既有/用户手选的关联；无关联且未清除过 → 高置信自动关联
                            // （Obtainium 式静默体验：仅本地查询零下载；PM 全量查询有成本，
                            // 不可在直出主线程跑，随网络校准异步完成）
                            val auto = if (!userClearedLink) {
                                io.candy.tick.util.AppMatcher.bestAutoMatch(
                                    appContext, result.info.owner, result.info.repo,
                                )
                            } else null
                            // 合并在 update 内做：bestAutoMatch 的几十 ms 里用户可能已在选择器
                            // 手选应用，快照直接覆盖会吞掉手选——手选（非空）永远优先
                            _state.update {
                                val cur = it.preview as? PreviewState.Loaded
                                it.copy(
                                    preview = PreviewState.Loaded(
                                        info = result.info,
                                        linkedPackage = cur?.linkedPackage ?: auto?.packageName,
                                        linkedLabel = cur?.linkedLabel ?: auto?.label,
                                    ),
                                    duplicate = dup,
                                )
                            }
                        }
                    }
                }
            } catch (e: SourceException) {
                // 静默校准失败：已直出的本地数据不受影响（仅 URL 粘贴路径亮错误态）
                if (!silent && fetchingKey == key) {
                    _state.update { it.copy(preview = PreviewState.Failed(e.error)) }
                }
            }
        }
    }

    fun setLinkedApp(packageName: String?, label: String?) {
        if (packageName == null) userClearedLink = true // 主动清除 = 退出自动关联
        _state.update { s ->
            val p = s.preview as? PreviewState.Loaded ?: return@update s
            s.copy(preview = p.copy(linkedPackage = packageName, linkedLabel = label))
        }
    }

    /** 每次进入添加页调用：清空上一次的输入 / 预览 / 卡死标志 */
    fun reset() {
        parseJob?.cancel()
        fetchingKey = null
        _state.value = UiState()
    }

    fun confirmAdd() {
        val preview = _state.value.preview as? PreviewState.Loaded ?: return
        if (_state.value.adding) return
        viewModelScope.launch {
            _state.update { it.copy(adding = true) }
            when (val result = repository.addRepo(preview.info.owner, preview.info.repo)) {
                is RepoRepository.AddResult.Added -> {
                    preview.linkedPackage?.let { pkg ->
                        repository.setLinkedPackage(result.repo.id, pkg, newLabel = preview.linkedLabel)
                    }
                    _state.update { it.copy(adding = false) } // 成功也要复位（导航后 VM 仍存活）
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
