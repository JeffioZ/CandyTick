package io.candy.tick.di

import android.content.Context
import io.candy.tick.data.AppStateStore
import io.candy.tick.data.GitHubApi
import io.candy.tick.data.GitHubProvider
import io.candy.tick.data.InstallManager
import io.candy.tick.data.Notifications
import io.candy.tick.data.PROVIDER_GITHUB
import io.candy.tick.data.RepoRepository
import io.candy.tick.data.SelfUpdater
import io.candy.tick.data.SyncStatus
import io.candy.tick.worker.SyncScheduler
import kotlinx.coroutines.flow.MutableStateFlow

/** 手动 DI（design.md：页面少，框架是负资产） */
class AppContainer(context: Context) {

    val appStateStore: AppStateStore by lazy { AppStateStore(context) }

    /** 自动同步信号（SyncWorker 写，首页读）：进度细线 + 结果胶囊；同进程直写无边界问题 */
    val syncStatus = MutableStateFlow<SyncStatus>(SyncStatus.Idle)

    val githubApi: GitHubApi by lazy {
        GitHubApi(tokenProvider = { appStateStore.state.value.settings.githubToken })
    }

    val installManager: InstallManager by lazy { InstallManager(context) }

    val selfUpdater: SelfUpdater by lazy {
        SelfUpdater(context = context, githubApi = githubApi, installManager = installManager)
    }

    val syncScheduler: SyncScheduler by lazy { SyncScheduler(context) }

    init {
        Notifications.ensureChannels(context)
    }

    val repoRepository: RepoRepository by lazy {
        RepoRepository(
            store = appStateStore,
            providers = mapOf(PROVIDER_GITHUB to GitHubProvider(githubApi)),
        )
    }
}
