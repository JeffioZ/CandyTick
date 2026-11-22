package io.candy.tick.ui.detail

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.candy.tick.R
import io.candy.tick.data.RepoDownloadState
import io.candy.tick.data.model.statusAt
import io.candy.tick.ui.common.AppIconResult
import io.candy.tick.ui.common.AppLinkRow
import io.candy.tick.ui.common.downloadActionTransition
import io.candy.tick.ui.common.DownloadProgress
import io.candy.tick.ui.common.InlineErrorRow
import io.candy.tick.ui.common.LinkedRepoAvatar
import io.candy.tick.ui.common.LinkedVersionSummary
import io.candy.tick.ui.common.Motion
import io.candy.tick.ui.common.PrimaryButton
import io.candy.tick.ui.common.RepoIdentityHeader
import io.candy.tick.ui.common.SectionDivider
import io.candy.tick.ui.common.StatusBadge
import io.candy.tick.ui.common.rememberAppIconResult
import io.candy.tick.ui.common.rememberMotionEnabled
import io.candy.tick.ui.common.repoAvatarIcon
import io.candy.tick.ui.common.sourceErrorText
import io.candy.tick.util.VersionCompare
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

// ── 详情页子组件（自 DetailScreen 拆出：头部卡 / 移除确认）──

@Composable
internal fun downloadFailureText(failure: RepoDownloadState.Failed?): String {
    val reason = failure?.error?.let { sourceErrorText(it) } ?: failure?.message
    return if (reason.isNullOrBlank()) stringResource(R.string.settings_download_failed)
    else stringResource(R.string.download_failed_reason, reason)
}

/** 移除订阅确认（破坏性操作：红色主按钮） */
@Composable
internal fun RemoveRepoDialog(
    show: Boolean,
    repoName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    // 底部浮层容器：与选项浮层同款动效
    top.yukonga.miuix.kmp.window.WindowBottomSheet(
        show = show,
        title = stringResource(R.string.remove_confirm_title),
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 4.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.remove_confirm_body, repoName),
                style = MiuixTheme.textStyles.body2,
                color = scheme.onBackgroundVariant,
            )
            // 与导入弹层同构：主上副下、全宽竖排（dialog 式右对齐小按钮不属于 sheet 体系）
            top.yukonga.miuix.kmp.basic.Button(
                onClick = onConfirm,
                colors = top.yukonga.miuix.kmp.basic.ButtonDefaults.buttonColors(
                    color = scheme.error,
                    contentColor = scheme.onError,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(R.string.action_remove),
                    style = MiuixTheme.textStyles.button,
                )
            }
            io.candy.tick.ui.common.SecondaryButton(
                text = stringResource(R.string.action_cancel),
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private data class LinkRow(val packageName: String, val label: String, val suggested: Boolean)
private data class HeaderApp(val icon: AppIconResult?, val version: String?)
private data class HeaderVersion(val tag: String, val local: String?, val comparison: Int?)

/** Only the changed section takes up or releases space, including its gap above. */
@Composable
private fun <T : Any> HeaderInfoSection(
    value: T?,
    sectionKey: (T) -> String,
    content: @Composable (T) -> Unit,
) {
    val duration = if (rememberMotionEnabled()) Motion.PHASE else 0
    AnimatedContent(
        targetState = value,
        contentKey = { it?.let(sectionKey) },
        modifier = Modifier.fillMaxWidth(),
        transitionSpec = {
            // The local-version row handles its own change while the release tag stays the same.
            if (initialState?.let(sectionKey) == targetState?.let(sectionKey)) {
                (EnterTransition.None togetherWith ExitTransition.None).using(null)
            } else {
                (fadeIn(tween(duration)) togetherWith fadeOut(tween(duration / 2)))
                    .using(SizeTransform { _, _ -> tween(duration, easing = Motion.Decelerate) })
            }
        },
        label = "headerInfoSection",
    ) { section ->
        if (section != null) {
            Column(Modifier.fillMaxWidth().padding(top = 8.dp)) { content(section) }
        }
    }
}

@Composable
internal fun HeaderCard(
    state: DetailViewModel.UiState,
    localVersion: String?,
    onChangeLinked: () -> Unit,
    onUnlink: () -> Unit,
    onLinkSuggestion: () -> Unit,
    onDownloadUpdate: () -> Unit,
    onInstallUpdate: () -> Unit,
    onCancelDownload: () -> Unit = {},
) {
    val repo = state.repo ?: return
    val snapshot = state.snapshot
    val scheme = MiuixTheme.colorScheme
    val context = LocalContext.current
    val linked = remember(context, repo.linkedPackage, repo.autoNote, repo.noteName, state.installEpoch) {
        repo.linkedPackage?.let {
            val label = repo.noteName.takeIf { repo.autoNote } ?: io.candy.tick.util.labelOf(context, it) ?: it
            LinkRow(it, label, false)
        }
    }
    val link = linked ?: state.linkSuggestion?.let { LinkRow(it.first, it.second, true) }
    val iconResult = rememberAppIconResult(link?.packageName, refreshKey = state.installEpoch)
    val avatar = repoAvatarIcon(linked?.packageName, iconResult.takeIf { linked != null })
    // Keep the previous avatar and local version together while another app's icon is being read.
    val headerApp by key(repo.id) {
        produceState(HeaderApp(avatar, localVersion), linked?.packageName, localVersion, avatar) {
            if (avatar != null) value = HeaderApp(avatar, localVersion)
        }
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        // 双层间距体系：跨组 8dp（Spacer）、组内 6dp——头卡多组累加，组距收紧一档防松散
        Column(modifier = Modifier.padding(start = 16.dp, top = 8.dp, end = 16.dp)) {
            RepoIdentityHeader(
                owner = repo.owner,
                repo = repo.repo,
                title = repo.noteName ?: repo.repo,
                stars = snapshot?.stars,
                badge = { StatusBadge(snapshot?.statusAt() ?: io.candy.tick.data.model.RepoStatus.UNKNOWN) },
            ) {
                LinkedRepoAvatar(repo.owner, headerApp.icon)
            }

            // ── 信息组（描述；无描述时跨组 8dp 不叠加）——统计行与推送时间已分别
            // 迁往名称旁（star）与首页折叠卡，此处只承载描述 ──
            HeaderInfoSection(snapshot?.description?.takeIf { it.isNotBlank() }, { it }) { description ->
                Text(
                    text = description,
                    style = MiuixTheme.textStyles.body2,
                    color = scheme.onBackgroundVariant,
                    maxLines = 3,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
            // 统计行已删（Fork/Issue 是档案页惯性搬运，健康度由活跃徽章承担；
            // star 挪名称旁，push 时间由首页折叠卡表达）

            // ── 版本分区（有 tag 时显示分隔线 + 版本行 + 操作按钮）──
            val tag = snapshot?.currentReleaseTag
            val displayedVersion = headerApp.version
            val version = remember(tag, displayedVersion) {
                tag?.let { remote ->
                    val order = displayedVersion?.let { VersionCompare.compare(remote, it) }
                    HeaderVersion(remote, displayedVersion, order)
                }
            }
            HeaderInfoSection(version, HeaderVersion::tag) { shown ->
                SectionDivider()
                Spacer(Modifier.height(6.dp))
                LinkedVersionSummary(shown.tag, shown.local, shown.comparison)
            }
            val hasUpdate = version?.comparison?.let { it > 0 } == true
            val downloadedMatches = repo.downloadedApk?.version == tag
            val updatePhase = when {
                // 进度必须绑定正在浏览的 tag：下载中改选资产/换版本后，旧传输不得显示到新版本行下
                state.download is RepoDownloadState.Active &&
                    state.download.fromHeader && state.download.releaseKey == tag -> "progress"
                // 失败相：此前头部无 failed 分支——失败回落"下载"按钮复原 + 错误条只在
                // 版本 tab 的卡里（停留提交 tab 时不可见）→ 感知"点了没反应"
                hasUpdate && state.download is RepoDownloadState.Failed &&
                    state.download.fromHeader && state.download.releaseKey == tag -> "failed"
                hasUpdate && downloadedMatches -> "install"
                hasUpdate -> "download"
                else -> null // idle 不渲染（零占位）
            }
            val actionDuration = if (rememberMotionEnabled()) Motion.PHASE else 0
            AnimatedContent(
                targetState = updatePhase to state.download,
                modifier = Modifier.fillMaxWidth(),
                contentKey = { it.first },
                transitionSpec = { downloadActionTransition(duration = actionDuration, clip = true) },
                label = "updatePhase",
            ) { (phase, transfer) ->
                if (phase == null) return@AnimatedContent
                val actionCurrent = phase == updatePhase && displayedVersion == localVersion
                Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    when (phase) {
                        "failed" -> InlineErrorRow(
                            message = downloadFailureText(transfer as? RepoDownloadState.Failed),
                            onRetry = onDownloadUpdate,
                            retryEnabled = actionCurrent,
                        )
                        "progress" -> DownloadProgress(
                            progress = (transfer as RepoDownloadState.Active).progress,
                            onCancel = onCancelDownload,
                            cancelEnabled = actionCurrent,
                        )
                        "install" -> PrimaryButton(
                            text = stringResource(R.string.install_apk),
                            onClick = onInstallUpdate,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = actionCurrent,
                        )
                        "download" -> PrimaryButton(
                            text = stringResource(R.string.download_update_action),
                            onClick = onDownloadUpdate,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = actionCurrent && state.download !is RepoDownloadState.Active,
                        )
                    }
                }
            }

            // ── 关联分区（始终存在，固定卡底）；前置 8dp 与上方版本区呼吸对称 ──
            Spacer(Modifier.height(8.dp))
            SectionDivider()
            LinkedAppRow(link, iconResult, onChangeLinked, onLinkSuggestion, onUnlink)
        }
    }
}

@Composable
private fun LinkedAppRow(
    link: LinkRow?,
    icon: AppIconResult?,
    onChangeLinked: () -> Unit,
    onLinkSuggestion: () -> Unit,
    onUnlink: () -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    val suggested = link?.suggested == true
    val linked = link != null && !suggested
    AppLinkRow(
        appIcon = icon?.bitmap,
        iconPending = link != null && icon == null,
        onClick = when {
            linked -> null
            suggested -> onLinkSuggestion
            else -> onChangeLinked
        },
        modifier = Modifier.padding(vertical = 8.dp),
        suggested = suggested,
        onChangeApp = if (link == null) null else onChangeLinked,
        onUnlink = if (linked) onUnlink else null,
    ) {
        Text(
            text = when {
                link == null -> stringResource(R.string.detail_link_app)
                suggested -> stringResource(R.string.detail_auto_link_hint, link.label)
                else -> stringResource(R.string.detail_linked_app, link.label)
            },
            style = MiuixTheme.textStyles.footnote1,
            color = if (suggested) scheme.onPrimaryContainer else scheme.onBackgroundVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
