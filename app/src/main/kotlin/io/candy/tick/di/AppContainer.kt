package io.candy.tick.di

import android.content.Context
import io.candy.tick.data.AppStateStore
import io.candy.tick.data.GitHubApi
import io.candy.tick.data.GitHubProvider
import io.candy.tick.data.InstallManager
import io.candy.tick.data.Notifications
import io.candy.tick.data.PROVIDER_GITHUB
import io.candy.tick.data.RepoDownloadHost
import io.candy.tick.data.RepoRepository
import io.candy.tick.data.CacheManager
import io.candy.tick.data.SelfUpdater
import io.candy.tick.data.SyncStatus
import io.candy.tick.data.Translator
import io.candy.tick.util.forAppLanguage
import io.candy.tick.worker.SyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow

/** 手动 DI（design.md：页面少，框架是负资产） */
class AppContainer(context: Context, isAppVisible: () -> Boolean) {

    /** 安装结果与更新任务跨页面存活。 */
    internal val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val appStateStore: AppStateStore by lazy { AppStateStore(context) }

    /** 自动同步信号（SyncWorker 写，首页读）：进度细线 + 结果胶囊；同进程直写无边界问题 */
    val syncStatus = MutableStateFlow<SyncStatus>(SyncStatus.Idle)

    val githubApi: GitHubApi by lazy {
        GitHubApi(tokenProvider = { appStateStore.state.value.settings.githubToken })
    }

    val installManager: InstallManager by lazy { InstallManager(context, appScope) }

    val selfUpdater: SelfUpdater by lazy {
        SelfUpdater(
            context = context,
            githubApi = githubApi,
            installManager = installManager,
            store = appStateStore,
            scope = appScope,
            isAppVisible = isAppVisible,
        ).also { it.observeInstallResults() } // 自更新安装失败此前无人消费（详情页专属订阅）
    }

    val syncScheduler: SyncScheduler by lazy { SyncScheduler(context) }
    val cacheManager: CacheManager by lazy { CacheManager(context) }
    val translator: Translator by lazy { Translator() }

    init {
        Notifications.ensureChannels(context.forAppLanguage(appStateStore.state.value.settings.language))
    }

    val repoRepository: RepoRepository by lazy {
        RepoRepository(
            store = appStateStore,
            providers = mapOf(PROVIDER_GITHUB to GitHubProvider(githubApi)),
            filesDir = context.filesDir,
        )
    }

    /** 仓库 APK 下载宿主（App 级）：离开详情页不中断下载，重进页面按 repoId 重绑进度 */
    val repoDownloadHost: RepoDownloadHost by lazy {
        RepoDownloadHost(
            scope = appScope,
            repository = repoRepository,
            githubApi = githubApi,
            installManager = installManager,
            appContext = context,
            isAppVisible = isAppVisible,
        )
    }
}
