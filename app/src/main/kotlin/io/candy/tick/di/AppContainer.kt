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
import io.candy.tick.data.download.ApkDownloader
import io.candy.tick.data.store.InstalledAppCache
import io.candy.tick.data.store.StoreCatalog
import io.candy.tick.data.store.StoreDownloadHost
import io.candy.tick.data.store.StoreSource
import io.candy.tick.data.store.StoreUpdateChecker
import io.candy.tick.data.store.appchina.AppchinaStore
import io.candy.tick.data.store.coolapk.CoolapkStore
import io.candy.tick.data.store.fdroid.FdroidStore
import io.candy.tick.data.store.honor.HonorStore
import io.candy.tick.data.store.huawei.HuaweiStore
import io.candy.tick.data.store.oppo.OppoStore
import io.candy.tick.data.store.samsung.SamsungStore
import io.candy.tick.data.store.taptap.TapTapStore
import io.candy.tick.data.store.tencent.TencentStore
import io.candy.tick.data.store.vivo.VivoStore
import io.candy.tick.data.store.wandoujia.WandoujiaStore
import io.candy.tick.data.store.xiaomi.XiaomiStore
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

    val apkDownloader: ApkDownloader by lazy { ApkDownloader() }

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

    /** 已适配的应用商店数据源集合（单点注册） */
    val storeSources: Map<String, StoreSource> by lazy {
        mapOf(
            StoreCatalog.ID_XIAOMI to XiaomiStore(),
            StoreCatalog.ID_VIVO to VivoStore(),
            StoreCatalog.ID_WANDOUJIA to WandoujiaStore(),
            StoreCatalog.ID_TENCENT to TencentStore(),
            StoreCatalog.ID_COOLAPK to CoolapkStore(),
            StoreCatalog.ID_APPCHINA to AppchinaStore(),
            StoreCatalog.ID_FDROID to FdroidStore(
                id = StoreCatalog.ID_FDROID,
                cacheDir = context.cacheDir,
                mirrorProvider = { appStateStore.state.value.settings.storePrefs.fdroidMirror },
            ),
            StoreCatalog.ID_IZZYONDROID to FdroidStore(
                id = StoreCatalog.ID_IZZYONDROID,
                cacheDir = context.cacheDir,
            ),
            StoreCatalog.ID_OPPO to OppoStore(),
            StoreCatalog.ID_HUAWEI to HuaweiStore(),
            StoreCatalog.ID_HONOR to HonorStore(),
            StoreCatalog.ID_SAMSUNG to SamsungStore(),
            StoreCatalog.ID_TAPTAP to TapTapStore(),
        )
    }

    val installedAppCache: InstalledAppCache by lazy { InstalledAppCache(context) }

    val storeUpdateChecker: StoreUpdateChecker by lazy {
        StoreUpdateChecker(
            context = context,
            stateStore = appStateStore,
            sources = storeSources,
        )
    }

    val storeDownloadHost: StoreDownloadHost by lazy {
        StoreDownloadHost(
            scope = appScope,
            appContext = context,
            stateStore = appStateStore,
            downloader = apkDownloader,
            installManager = installManager,
            checker = storeUpdateChecker,
            sources = storeSources,
        )
    }
}
