package io.candy.tick.di

import android.content.Context
import io.candy.tick.data.AppStateStore
import io.candy.tick.data.GitHubApi
import io.candy.tick.data.GitHubProvider
import io.candy.tick.data.InstallManager
import io.candy.tick.data.PROVIDER_GITHUB
import io.candy.tick.data.RepoRepository

/** 手动 DI（design.md：页面少，框架是负资产） */
class AppContainer(context: Context) {

    val appStateStore: AppStateStore by lazy { AppStateStore(context) }

    val githubApi: GitHubApi by lazy {
        GitHubApi(tokenProvider = { appStateStore.state.value.settings.githubToken })
    }

    val installManager: InstallManager by lazy { InstallManager(context) }

    val repoRepository: RepoRepository by lazy {
        RepoRepository(
            store = appStateStore,
            providers = mapOf(PROVIDER_GITHUB to GitHubProvider(githubApi)),
        )
    }
}
