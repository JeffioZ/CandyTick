package io.candy.tick.data.download

/**
 * 统一的下载与安装任务键：GitHub 仓库、本机商店应用、自身更新共用一套下载和安装管道。
 */
sealed interface DownloadKey {
    data object SelfUpdate : DownloadKey
    data class Repo(val id: Long) : DownloadKey
    data class Store(val packageName: String) : DownloadKey

    companion object {
        fun fromRepoId(repoId: Long?): DownloadKey =
            if (repoId == null) SelfUpdate else Repo(repoId)
    }
}
