# 更新日志

点版本号可打开发布页，查看说明或下载安装包。「未发布」列出上个正式版以来已合入主分支的改动。

<!--
格式借鉴 Keep a Changelog（https://keepachangelog.com/zh-CN/1.1.0/），版本号遵循语义化版本。
按用户可见的变化写，内部重构、CI 调整一般不单列。
「新增」是以前没有的功能，「优化」是原有功能的改进，「修复」是修了不正常的行为。
条目归属以 tag 包含的提交为准（git log v前版..v本版）。
在相应版本的中文条目后附英文摘要。发版工作流会提取整个版本章节作为 GitHub Release 正文。
-->

[未发布]: https://github.com/JeffioZ/CandyTick/compare/v0.4.2...main
[0.4.2]: https://github.com/JeffioZ/CandyTick/releases/tag/v0.4.2
[0.4.1]: https://github.com/JeffioZ/CandyTick/releases/tag/v0.4.1
[0.4.0]: https://github.com/JeffioZ/CandyTick/releases/tag/v0.4.0
[0.3.2]: https://github.com/JeffioZ/CandyTick/releases/tag/v0.3.2
[0.3.1]: https://github.com/JeffioZ/CandyTick/releases/tag/v0.3.1
[0.3.0]: https://github.com/JeffioZ/CandyTick/releases/tag/v0.3.0
[0.2.1]: https://github.com/JeffioZ/CandyTick/releases/tag/v0.2.1
[0.2.0]: https://github.com/JeffioZ/CandyTick/releases/tag/v0.2.0
[0.1.0]: https://github.com/JeffioZ/CandyTick/releases/tag/v0.1.0

## [未发布]

### 新增

- 首页的五种排序都支持反向排列。再次点选当前排序方式即可切换方向，已置顶的仓库仍排在前面。
- 长按详情页右上角的地球图标，可复制仓库网址。

### 优化

- 首页的「最近活动」会比较所选分支的最新提交时间和最新安装包的发布时间，显示两者中较近的一个。刷新、置顶或取消置顶时，列表会尽量保留当前阅读位置。
- 点开单个仓库的新版安装包通知后，详情页会定位到对应版本，只清除这次发布的未读标记。「可更新」仍按本机实际安装的版本判断。
- 离开详情页后，安装包会继续下载，返回即可查看进度。分包文件保留原扩展名，方便用兼容的安装程序打开。
- 进入详情页时，刷新期间仍可查看已有的历史记录。搜索本机应用时，面板高度保持稳定。
- 开启 CandyTick 自身更新的自动下载后，使用移动数据时也能下载新版。仓库自动同步仍遵循单独的网络设置。
- 首页排序浮层的标题和选项上移，与其他浮层的标题间距一致。
- 首页同步时，三点出现后才会显示完成图标。刷新期间，已展开的仓库卡片会保留活动时间和已有提交。详情页顶部的三点只跟随当前页签和仓库概况的加载状态。

### 修复

- 手动取消应用关联后，重启 CandyTick 仍会记住这个选择。卸载已关联的应用后，CandyTick 会在下次检查时解除关联。
- 粘贴较长的 GitHub 令牌时，输入框会保留完整内容。
- 安装完成后，CandyTick 会清理与已装应用和版本匹配的 APK，并检查关联仓库的更新通知是否已过期。
- 在设置中点一次「通知权限」即可进入系统设置。系统无法打开权限设置、分享菜单或文件选择器时，应用会给出提示。
- 下载缺少文件大小的安装包时，会显示「正在下载」，避免进度一直停在 0%。
- 修复了自动同步与下拉刷新重叠时，刷新提示提前收起、展开卡片偶尔仍显示旧提交的问题。

### English summary

- Tap the selected sort option again to reverse the order. Pinned repositories stay at the top. Recent activity includes commits on the selected branch and releases with installable packages. Refreshing or pinning a repository keeps the card you’re reading in view where possible.
- Open a notification for a single repository to go to that release. Only that release is marked as read. Update availability still depends on the installed version.
- Downloads continue after you leave the details page. Split packages keep their original extensions. After installation, CandyTick cleans up the matching APK and checks for stale update notifications.
- Long-press the globe icon to copy the repository URL. You can keep reading cached history during a refresh, and the installed-app picker stays the same height as you search.
- CandyTick can download its own updates over mobile data when automatic downloads are enabled. Repository sync still follows your network settings.
- Unlinking an app now persists across restarts. Uninstalled apps are unlinked on the next check, and long GitHub tokens can be pasted in full.
- The sort sheet now uses the same title spacing as other sheets. Tapping notification permission in Settings opens the system settings on the first tap. CandyTick shows a message if a settings page, share menu, or file picker is unavailable.
- The home screen shows a sync result icon only after the loading dots have appeared. Expanded cards keep their activity times and commits visible during refresh. When a pull-to-refresh overlaps with automatic sync, the indicator waits for expanded cards to catch up. The detail header tracks the repository overview and the selected tab.
- Downloads show “Downloading…” when the package size is unknown. The percentage appears when the size is available.

## [0.4.2] - 2026-11-20

### 优化

- 提交历史、版本历史等列表滚到底部时显示「已到底 · 共 N 项」，方便查看总数。

### 修复

- 版本卡片上的「删除已下载安装包」按钮与相邻按钮等高。列表行按下时的高亮和小标签也统一了圆角样式。

## [0.4.1] - 2026-11-20

### 优化

- 首页还没有订阅时，提示居中显示，原先的口号换成一行简短说明。搜索分支或本机应用没有结果时，也会显示提示。
- 设置页的主题和 Wi-Fi 图标改为居中的对称图形。首页「收起全部」改用列表加双上箭头的图标，与旁边的排序按钮呼应。

### 修复

- 经聊天工具转发的备份文件也能在文件选择器中选中。备份中的仓库都已订阅时会提示「没有新增」，导入大文件时会在后台读取。
- 统一了英文界面中应用更新通知渠道的名称和版本提示的大小写。

## [0.4.0] - 2026-11-20

### 新增

- 更新日志支持 Markdown 表格、粗体、删除线、代码块和自动链接。点 `#123` 这样的编号可打开对应的 GitHub Issue。过长的说明会截短显示，以免打开时卡顿。
- 点「仓库新版本」通知可打开仓库详情，点 CandyTick 自身更新通知可打开设置。同一轮同步发现多个仓库发版时，会合并为一条通知。
- 平板和展开的折叠屏可横屏使用，内容居中显示，并限制宽度以方便阅读。
- 首页展开多张仓库卡片后，可点顶部的「收起全部」一次收起。
- 打开搜索框即可输入，有内容时可一键清空，按回车可收起键盘。GitHub 令牌输入框也增加了提示标签。

### 优化

- 在添加仓库页点选搜索结果，即可在浮层中预览。关闭后保留搜索列表的阅读位置。预览中的本机应用关联状态与详情页显示一致。
- 按下卡片时表面会略微变暗，深色主题下的按压反馈更自然。
- 展开首页卡片时会同时加载提交与版本，缩短等待时间。刷新后，已展开的卡片会自动更新内容。
- 安装完成后，首页和详情页会立即刷新「有新版」标记。详情页顶部的下载入口也会显示进度。
- 同步部分失败时，提示会显示失败的仓库数量。全部失败时会单独说明。
- 首页搜索无结果时，引导文字在键盘上方居中显示。设置页的主题和 Wi-Fi 图标也调整为居中的对称图形。
- 各处应用图标使用一致的显示样式，白底图标加上细边框。清理缓存时，两类安装包合并为一项。

### 修复

- 开启「仅 Wi-Fi 同步」后，打开应用时的自动同步也会遵循此设置。
- 调整了下载失败提示的位置和样式，并在错误提示中加入重试按钮。
- 修复了安装包名称中的普通单词被误判为 ARM 架构标记的问题，例如 `Swarm`。
- 首页卡片提示有新版后，展开可看到最新内容。通过通知进入页面后，旋转屏幕也不会再次跳转。

## [0.3.2] - 2026-11-19

### 新增

- 点版本号可展开更新日志，其中的 GitHub 表情符号也能正常显示。日志中的链接和「查看完整更新说明」可在浏览器打开。点提交记录可前往 GitHub 查看变更。
- 版本历史为本机已安装的版本加上「当前」标签。有多个安装包时，使用 Windows 风格的单选框选择。

### 优化

- 设置页将权限选项移到对应功能旁，移除了「仅充电时同步」选项。
- 仓库路径改用等宽字体，与版本号和提交哈希的显示方式一致。
- 按下卡片或列表项时，两者都会略微下沉。
- 在较长的列表里展开更新日志后，滚动更流畅。

## [0.3.1] - 2026-11-19

### 优化

- 移除了未使用的语言资源和冗余数据，安装包从约 3.9 MB 减至 3.6 MB，缩小约 270 KB。
- 在设置页点版本号即可检查更新，检查状态会随进度显示。「自动更新」选项单独成组。
- 应用更新弹窗中的版本说明、下载进度和安装按钮之间增加了过渡动画。
- 切换所选安装包时，选中动画更平稳。
- 详情页切换页签时，顶部图标会平滑出现或隐藏。卡片展开区域有独立底色，按下其中的选项时会显示圆角高亮。
- 「解除关联」改用断开的链条图标。
- 本机应用列表改在后台加载。打开选择器时可先看到加载动画，减少等待时的卡顿。
- 在系统无障碍设置中关闭动画后，加载和删除效果也会停止播放。

### 修复

- 增加了应用关联提示与上方分隔线的间距。
- 应用图标改用纯色背景，修复在部分第三方安装程序中显示异常的问题。
- 重复检查更新时，已下载的更新包会保留。
- 移除了可能引起系统组件显示异常的长按复制文字功能。

## [0.3.0] - 2026-11-19

### 新增

- 手动或后台检查发现 CandyTick 新版本时，会打开独立弹窗。可在其中查看版本号、阅读更新说明、跟踪下载进度并安装。
- 安装包下载过程中可查看文件大小或取消下载。失败时会说明是网络错误、超时还是请求受限，并提供重试按钮。
- 清理缓存时可分别查看已下载的安装包、CandyTick 自身更新包和崩溃日志占用的空间，再勾选要清理的项目。
- 添加仓库页增加了输入提示。连续输入时会稍候再发起搜索，搜索或预览失败时可重试，遇到已订阅的仓库可直接打开。
- 切换语言后，界面会立即显示所选语言。
- 触发下拉刷新、滑动达到操作距离或确认删除时，会有振动反馈。
- 点首页或详情页顶部的标题，即可回到列表顶部。

### 优化

- 统一采用 Lucide 图标，并修复了部分图标显示不完整的问题。
- 桌面和启动画面的图标随系统主题切换，浅色模式使用奶油色背景，深色模式使用墨绿色背景。
- 缩小了各页卡片的边距，并统一了不同层级的行间距。
- 设置页将应用更新单独列出，合并同步与通知选项。「关于」中保留版本和链接信息。
- 调整了首页排序选项的顺序，增加「最近添加」。按 A–Z 排序时使用应用中显示的名称。
- 各列表底部统一显示「已到底」，搜索时的加载动画居中显示。
- 支持 APK、APKS、XAPK 和 APKM 四种安装包格式，可自动过滤没有安装包的版本。
- 同步期间下拉刷新时，状态提示保持可见。等待检查更新时，可重新打开更新弹窗查看进度。

### 修复

- 修复了只提供桌面端安装包的仓库（如部分 nightly 构建）被误标为「可更新」的问题。
- 清理崩溃日志后，设置页会立即显示新的占用空间。
- 删除仓库时的确认提示会说明实际清理的内容。
- 修复了滑动卡片时操作图标看不见、卡片抖动的问题。
- 修复了查看缺少发布说明的仓库时可能发生的崩溃。

## [0.2.1] - 2026-11-17

### 新增

- 删除仓库、完成同步、导入备份或复制内容后，结果都显示在顶部提示条中。删除仓库后可直接点提示条上的「撤销」恢复。
- 发现 CandyTick 新版本后，检查更新入口下方会显示版本号、更新说明和下载按钮。
- 更新说明会显示 GitHub 上的发布正文。

## [0.2.0] - 2026-11-17

### 新增

- 在详情页可切换分支查看提交记录，也可按名称搜索分支。
- 在首页左滑仓库卡片可将它置顶，再次左滑可取消置顶。已置顶的仓库排在列表最前面。
- 添加仓库时会自动识别已安装的对应应用。关联后，仓库标题会使用应用名称，应用改名时也会随之更新。
- 详情页顶部可下载新版、查看进度并安装，也可设置为下载完成后自动打开安装程序。
- 版本比较支持日期加提交哈希的写法（如 `26.11.caccb78`），以及 `2.2.2-rc0` 这样的预发布版本号。

### 优化

- 删除仓库时，卡片会以粒子消散的动画退出。
- 首页标题旁用三个跳动的圆点表示正在同步，完成后显示结果。
- 刷新或后台同步改变排序时，位置变化较大的卡片直接就位。
- 弹窗和底部浮层统一使用滑入和滑出动画。
- 打开添加仓库页或 GitHub 令牌输入框即可输入。
- 加载时统一显示三点动画，切换到内容时更平稳。
- 深色模式下，按钮的按压反馈和浮层边缘更清晰。
- 搜索时可按返回键退出搜索，或按回车收起键盘。

### 修复

- 修复了某些含 Git 提交哈希的版本号被误报为「无法比较」的问题。
- 修复了活跃仓库反复出现「有更新」标记的问题。
- 应用名称与仓库名称不完全相同时，也能自动关联对应的应用。
- 修复了导入订阅后首次同步把所有仓库都标为「有更新」的问题。
- 从其他页面返回首页时，修复了列表偶尔闪烁、顶部露出卡片边缘的问题。
- 修复了冷启动时偶尔发生的闪退。

## [0.1.0] - 2026-11-16

首个公开版本。

### 功能

- 订阅 GitHub 仓库，按最近提交时间显示「活跃」「一般」「低活跃」或「疑似弃坑」，已归档的仓库单独标记。
- 在仓库详情页分页浏览提交历史和版本发布记录。
- 关联本机应用后会自动比较版本，并标记可用更新。
- 有多个 APK 时，按设备架构推荐下载。已下载的安装包会保留，安装失败后可再次使用。
- 后台定时同步并通知新版本，可设置仅在 Wi-Fi 下同步，或仅在充电时同步。
- 订阅公开仓库无需登录，添加 GitHub 令牌可将 API 请求限额提高到每小时 5,000 次。
- 首页支持搜索、多种排序和滑动删除，误删后可撤销。
- 可导出订阅并在另一台设备上合并导入。
- CandyTick 可检查自身更新。在 Android 12 及以上版本上，如果由 CandyTick 自身完成安装，可免确认更新。
- 支持深浅色主题、简体中文和英文界面。
