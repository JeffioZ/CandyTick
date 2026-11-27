# 更新日志

点版本号可打开发布页，查看说明或下载安装包。「未发布」列出上个正式版以来已合入主分支的改动。

<!--
格式借鉴 Keep a Changelog（https://keepachangelog.com/zh-CN/1.1.0/），版本号遵循语义化版本。
按用户可见的变化写，内部重构、CI 调整一般不单列。
「新增」是以前没有的功能，「优化」是原有功能的改进，「修复」是修了不正常的行为。
条目归属以 tag 包含的提交为准（git log v前版..v本版）。
在相应版本的中文条目后附英文摘要。发版工作流会提取整个版本章节作为 GitHub Release 正文。
-->

[未发布]: https://github.com/JeffioZ/CandyTick/compare/v0.7.2...main
[0.7.2]: https://github.com/JeffioZ/CandyTick/releases/tag/v0.7.2
[0.7.1]: https://github.com/JeffioZ/CandyTick/releases/tag/v0.7.1
[0.7.0]: https://github.com/JeffioZ/CandyTick/releases/tag/v0.7.0
[0.6.0]: https://github.com/JeffioZ/CandyTick/releases/tag/v0.6.0
[0.5.1]: https://github.com/JeffioZ/CandyTick/releases/tag/v0.5.1
[0.5.0]: https://github.com/JeffioZ/CandyTick/releases/tag/v0.5.0
[0.4.2]: https://github.com/JeffioZ/CandyTick/releases/tag/v0.4.2
[0.4.1]: https://github.com/JeffioZ/CandyTick/releases/tag/v0.4.1
[0.4.0]: https://github.com/JeffioZ/CandyTick/releases/tag/v0.4.0
[0.3.2]: https://github.com/JeffioZ/CandyTick/releases/tag/v0.3.2
[0.3.1]: https://github.com/JeffioZ/CandyTick/releases/tag/v0.3.1
[0.3.0]: https://github.com/JeffioZ/CandyTick/releases/tag/v0.3.0
[0.2.1]: https://github.com/JeffioZ/CandyTick/releases/tag/v0.2.1
[0.2.0]: https://github.com/JeffioZ/CandyTick/releases/tag/v0.2.0
[0.1.0]: https://github.com/JeffioZ/CandyTick/releases/tag/v0.1.0

## [0.7.2]

### 新增

- 「导出订阅」新增应用内三列导出去向浮层（本地文件 / 剪贴板 / 分享），支持直接通过系统文件选择器保存 JSON 备份文件，按需再唤起系统分享面板。

### 优化

- 首页列表排序与同步重排体验升级：在列表顶部切换排序维度、反转升降序或刷新发现新版本时，卡片以无回弹柔和物理弹簧平滑滑位，上移卡片临时浮于上层，屏外新进卡片原地轻淡入；滚动至半路/底部时后台自动同步继续锁定阅读顺序，杜绝跳屏。
- 浅色主题主色提亮为更清透的薄荷翠（`#16A075`），下载主按钮、开关开启态与进度条更清爽，白底文字与图标统一采用 `#0E805E` 保持 WCAG AA 可读性。
- 未关联本地应用的仓库头像升级为 8 组 WCAG AA 现代渐变底色，按 `owner/repo` 均匀着色，并支持英文双字母与中文单字智能缩写。
- 应用自身更新浮层优化：顶部单行版本条新增相对发布时间展示，点击下载后版本条与更新日志全程常驻不隐藏、仅底部动作区原地切换为进度条与安装按钮，并均衡调整各区块纵向间距。
- 全面统一应用内浮层设计语言：详情页「更多操作」改为淡薄荷图标徽章与克制中性危险卡片；「清理缓存」合并为单张软底分组卡与缩进分割线；「导入订阅」对齐为同款极简卡片浮层；「同步频率」升级为单行 4 列可视化缩略图，「深浅模式」「应用语言」同步精修缩略图与等高对齐；非搜索浮层留白与圆角阶梯全面统一。
- 设置页「检查更新」行在后台静默检查与瞬时检查失败时保持干净版本号常驻，仅手动点击时短暂显示检查中并复用在途请求。

### English summary

- Added an in-app 3-column Export Subscriptions sheet (File / Clipboard / Share) with direct SAF JSON file saving so the system share sheet only opens when requested.
- Upgraded home list sort and sync reorders at the top of the list with a smooth no-bounce spring placement animation (upward cards layered above, newcomers gently fading in) while keeping reading order locked when scrolled down.
- Brightened the light-theme primary mint to `#16A075` for buttons, switches, and progress indicators while keeping `#0E805E` for WCAG AA text and icons.
- Upgraded unlinked repository avatars with 8 WCAG-AA gradients, `owner/repo` hashing, and smart dual-letter English / single-character CJK monograms.
- Refined the self-update sheet to show relative release time in the version banner, keep release notes visible throughout download and install states, and balance vertical spacing.
- Unified sheet design language across the app (mint icon badges and calm danger row in More Actions, single grouped card with inset divider in Cache Cleanup, matching minimal Import sheet, single-row 4-column Sync Frequency picker, refined Theme/Language thumbnails, and consistent radii/spacing).
- Kept the Settings Check for Updates row showing a clean monospace version during silent background checks, showing "Checking…" only on manual tap and reusing in-flight checks.

## [0.7.1]

### 优化

- 首页顶栏排序入口图标支持随升序 / 降序做 180° 平滑垂直翻转，与排序浮层内的方向指示保持一致。
- 首页仓库卡片展开 / 收起箭头统一为 180° 平滑旋转动效（遵循系统减弱动效），与详情页版本卡片手感一致。
- 自身更新浮层顶部新增无框柔和版本信息条（展示当前 → 目标版本与安装包体积），并在下载过程中保持连贯展示；后台已下载完成的新版在打开应用时也会主动提醒一次安装。
- 设置页「开源许可」改用天平图标，避免与「本地文件导入」重复。

### English summary

- Added a smooth 180° vertical flip animation to the home top-bar sort icon so it reflects the active ascending or descending order.
- Unified the home repository card expand/collapse chevron with a smooth 180° rotation (respecting reduced-motion settings) matching detail release cards.
- Polished the self-update sheet with a soft version banner showing current → target version and package size across NewVersion, Downloading, and Downloaded states, and auto-prompts once to install already-downloaded updates on launch.
- Updated the Open-source licenses row to use a Lucide scale icon.

## [0.7.0]

### 优化

- 桌面图标与开屏标志精修：调整中心糖珠光晕、渐变层次与 1.10× 比例，浅色与深色模式观感更居中柔和。
- 全面升级并统一应用内底部浮层设计语言：
  - 外观与偏好类（「桌面图标」「深浅模式」「应用语言」）升级为 3 列可视化缩略图选择器，附 2dp 聚焦环与大字号防裁切保护；
  - 「同步频率」升级为 2×2 柔和实色卡片，「导入订阅」升级为并排双动作卡片，「清理缓存」与详情页「更多操作」采用去线框柔和实色层与立体图标徽章；
  - 首页「排序方式」回归通透原生列表，选中项以圆角薄荷胶囊高亮并补齐 180° 平滑翻转箭头动效，反向排序提示移至底部。
- 顶部提示胶囊（Toast）样式与排版优化：提升悬浮阴影与错误态柔和红描边，错误码改以弱化等宽字样呈现，并启用均衡断行消除多行孤儿尾词与右侧大块留白；同步精简导入失败与导入来源文案。
- 细节与无障碍打磨：深色模式按压染色强度调柔至 9%（浅色保持 16%）；GitHub 令牌额度卡片新增语义状态点并将刷新按钮触控区扩至 48dp；全部浮层补齐横屏与大字号滚动兜底；全应用动效 100% 遵循系统「减弱动效」开关。
- 正式安装包体积减半（约 4.0 MB 降至约 2.0 MB）：开启 Release dex 压缩并剔除构建/调试期冗余元数据，保留全部开源许可声明。

### 修复

- 修复在前台直接切换「桌面图标」时可能触发系统杀进程闪退的问题，改为退回后台且无活跃下载时再平滑切换。
- 修复后台同步期间 GitHub 令牌额度显示偶发回退或重置时间抖动的问题，以及「打开应用时同步」残留旧任务导致不触发刷新的问题。

### English summary

- Refined the launcher and splash icon with balanced mint glow, gradients, and 1.10× proportions across light and dark themes.
- Unified bottom sheet design across the app: 3-column visual thumbnail pickers for App icon, Theme, and Language; borderless soft-surface cards for Sync frequency, Import subscriptions, Cache cleanup, and Detail More actions; and a clean frameless Sort sheet with a rounded mint selection pill and smooth 180° arrow flip.
- Polished feedback toasts with softer elevation, subtle error borders, muted monospace error codes, and balanced line breaking (`LineBreak.Heading`) to eliminate awkward orphan wraps.
- Reduced dark-mode press tint to 9% for softer tap feedback, expanded the token quota refresh button to a 48dp touch target, added scroll fallbacks to all sheets for landscape/large-font layouts, and ensured all animations respect reduced-motion settings.
- Cut release APK download size in half (~4.0 MB to ~2.0 MB) by enabling dex compression and stripping non-runtime build metadata while preserving all open-source licenses.
- Fixed a foreground crash when switching launcher icons by deferring component updates until the app enters the background with no active downloads, and stabilized GitHub token quota display and on-open sync scheduling.

## [0.6.0]

### 新增

- 详情页顶栏新增一键翻译按钮，点击后自动按应用当前语言翻译展示中的仓库简介、提交标题和已展开的版本更新日志，再点切回原文；内置多条免费通道自动切换。
- 设置页「GitHub 令牌」支持查看当前接口配额（已用、剩余、上限与重置倒计时），未填写时显示匿名额度，验证通过后显示对应用户名与认证额度，并支持手动刷新。
- 设置页「外观」新增「桌面图标」选项，支持在跟随系统、浅色、深色之间固定桌面图标颜色。
- 失败提示（顶部胶囊与行内错误条）末尾统一附带简短错误码，方便排查网络、限流、翻译或安装问题。

### 优化

- 详情页顶栏精简为「翻译」和「更多」两个按钮，将切换分支、在浏览器打开、复制仓库链接和移除仓库收进底部菜单，为仓库标题留出更多显示空间。
- 未关联本机已安装应用的订阅仓库不再推送新版本系统通知，仅保留应用内未读红点提醒。
- 令牌验证时的按钮变色与结果提示改为平滑过渡，避免弹窗高度突跳；外观设置项中文统一为「应用语言」「深浅模式」「桌面图标」。
- 按下按钮和设置项时的反馈更明显：一按就变色，松手后稍作停留再淡出，与卡片、图标按钮的手感一致。
- 对齐了几处界面细节：版本卡片里展开的更新内容与标题齐平，首页卡片展开后的内容、清除缓存弹层里的行和帮助链接的图标也落在同一条线上。
- 首页卡片展开、收起时的几处硬切改成过渡：未读圆点、「发现新版」标签、面板高度和「正在刷新」文字。同步结果提示改为列表更新完成后再出现。

### 修复

- 修复下载进度条走动时因数字宽度变化导致进度条左右伸缩的问题，并统一了头卡、版本卡片与自身更新弹层的进度条高度与间距。
- 输入框没有焦点时几乎看不出边界，现在带一圈淡淡的描边。
- 底部弹层的内容现在会避开系统导航栏，不再被它挡住。
- 粘贴以 `.git` 结尾的仓库克隆地址时，不再被误报为「内容不存在或无权访问」。
- 下载安装包时存储空间不足，现在提示「存储空间不足」，不再显示成网络不可用；下载不完整、安装包保存失败也改为明确的提示，不再显示英文原文。

### English summary

- Added an on-demand translate toggle in the repository detail top bar to translate visible repository descriptions, commit titles, and expanded release notes into the app's language, with automatic fallback across free translation endpoints.
- GitHub token settings now show live core API quota (used, remaining, limit, and reset time) for both anonymous and authenticated requests, along with the signed-in `@username` and a manual refresh button.
- Added an App icon setting under Appearance to keep the launcher icon on Follow system, Light, or Dark.
- Failure toasts and inline errors now include short diagnostic error codes (such as `E-NET`, `HTTP 403`, or `E-TR-429`) to make troubleshooting easier.
- Moved low-frequency detail actions (branch picker, open in browser, copy repository link, and remove repository) into a More sheet so repository titles have more room.
- Repositories without a linked local app no longer post new-release system notifications and only show in-app unread indicators.
- Smoothed button color and result-banner transitions in the token sheet, and fixed download progress bars changing width as numbers update while unifying height and spacing across all download views.
- Pressing a button or a settings row now gives clearer feedback, unfocused text fields show a faint outline, bottom sheets clear the system navigation bar, and several card/sheet alignments and home card expand transitions were tightened up.
- Pasting a `.git` clone URL no longer fails as inaccessible, and low-storage, incomplete-download, or save failures now show clear localized messages.

## [0.5.1]

### 优化

- 检查 CandyTick 更新遇到 GitHub 请求上限时，会直接说明稍后再试，不再显示成网络问题。
- 读屏更清楚：仓库卡片、搜索结果和提交记录会读出点按后的动作；展开卡片里的错误提示不再每分钟重复播报整张卡片。
- 英文界面全面校对：设置项和输入框提示不再折行，用词和标点更统一。

### 修复

- 订阅数据损坏被重置时，已下载的安装包不再被当作残留清掉，会保留一段时间供你找回。
- GitHub 令牌失效后，打开分支选择器仍会一页页加载全部日期，白白消耗每小时有限的请求次数；现在会自动改用省请求的方式。
- 剩余存储空间不够时，下载会直接失败并提示，不再下载到一半才出错。

### English summary

- Update checks that hit the GitHub request limit now say to try again later, instead of reporting a network problem.
- TalkBack reads out the action for repository cards, search results, and commit rows, and an error inside an expanded card no longer re-reads the whole card every minute.
- The English interface had a full copy pass: settings rows and input hints fit on one line, with more consistent wording and punctuation.
- When local data is corrupted and reset, downloaded packages are kept for a while instead of being cleaned up as leftovers.
- With an invalid GitHub token, the branch picker no longer pages through every branch and burns the hourly request quota.
- Downloads fail fast with a clear message when storage is low, instead of failing halfway through.

## [0.5.0]

### 新增

- 首页的五种排序都支持反向排列。再次点选当前排序方式即可切换方向，已置顶的仓库仍排在前面。
- 长按详情页右上角的地球图标，可复制仓库网址。
- 订阅或设置保存失败时，可以直接重试，也可以稍后从页面顶部重试。
- 订阅 CandyTick 自己的仓库时会自动关联本应用；手动取消关联后，详情页仍会给出重新关联的建议。
- 没有订阅时，首页提供「导入已有订阅」入口，点击后直达设置页并自动打开导入窗口。

### 优化

- 首页的「最近活动」时间，取所选分支最新提交和最新安装包发布时间中更近的一个。刷新、置顶或取消置顶时，列表会尽量保留当前阅读位置。
- 点开单个仓库的新版安装包通知后，详情页会定位到对应版本，只清除这次发布的未读标记。「可更新」仍按本机实际安装的版本判断。
- 离开详情页后，安装包会继续下载，返回即可查看进度。分包文件保留原扩展名，方便用兼容的安装程序打开。
- 仓库安装包在后台下载完成后，可从通知打开对应版本，即使离线也能安装本机已下载的文件。清理缓存时默认选中扫描到的安装包，崩溃日志可单独勾选。
- 「跟随系统」会按系统语言列表匹配，后台通知也会使用应用选定的语言。关闭 CandyTick 自动检查后会暂停自动下载，重新开启时恢复此前的下载偏好。
- 打开仓库时，已缓存的提交和版本记录随页面一起出现，刷新期间仍可查看、点按。没有缓存且首屏请求稍慢时，占位卡片会更早出现，切换到正在加载的页签也不用再等一轮。手动下拉时，仓库概况和历史记录随「刷新完成」一起更新。
- 首页搜索框展开后再弹出键盘，减少展开长列表时的卡顿。
- 搜索本机应用时，面板高度保持稳定。
- 分支选择器支持继续加载下一页。详情页空间不足时，提交和版本页签会一起改用短名称。
- 仓库卡片为完整路径留出更多宽度，长名称可在连字符等分隔处换行。清空搜索框后可直接继续输入。
- 已关联的仓库在搜索结果和预览中显示所关联应用的图标，与详情页一致。首次匹配本机应用时，也可以直接点「添加仓库」。
- 关闭仓库预览会回到原来的搜索位置。通过链接打开预览时，返回后可继续编辑链接或按「搜索」重新打开。
- 仓库搜索失败时会显示原因。提交或版本历史翻页失败时，可在现有列表末尾重试。
- 开启 CandyTick 自身更新的自动下载后，使用移动数据时也能下载新版。仓库自动同步仍遵循单独的网络设置。
- 首页排序浮层的标题和选项上移，与其他浮层的标题间距一致。
- 首页同步时，三点出现后才会显示完成图标。刷新期间，已展开的仓库卡片会保留活动时间和已有提交。
- 首页和详情页的版本摘要统一省略数字前的 `v`，方便对照本机版本。版本历史保留仓库的原始标签。
- 横屏或分屏时可滚动设置浮层，键盘弹出后也能找到保存按钮。窗口较矮时，自身更新的下载按钮会排在更新说明前面。
- 首页和详情页点标题回到顶部；已在顶部时再点一下，收起全部展开的卡片或更新日志。
- 没有订阅时，首页不再显示搜索和排序按钮，也不响应下拉刷新。
- 后台同步全部因 GitHub 限额失败时，结果胶囊直接说明限额和恢复时间；详情页任一列表加载成功后，会自动收起过期的限额提示。
- 删除仓库后，提示条会显示仓库名，连续删除时容易区分；撤销按钮加上浅色底，更容易点按。
- 确认滑动删除后，卡片在松手位置原地消散，不再回弹。
- 成功与失败的提示图标加上外圈，与部分失败时的图标风格统一。
- 下载中的取消按钮与进度条对齐，两侧间距相等；下载完成后安装与删除按钮的间距对称。
- 输入框未选中时有淡淡的描边，更容易辨认。
- 导出的备份只含仓库基础信息（关联应用、备注、置顶等），体积大幅缩小；旧版的完整备份仍可正常导入。断网导入时，卡片先显示名称等基本信息，联网后自动补全。
- 列表末尾的「已到底」标记颜色整体变淡，减少对内容的干扰。

### 修复

- 启动时暂时读不到本机数据，可以重新读取。大型订阅备份会以 JSON 文件分享。
- CandyTick 自身更新下载失败后，重试成功也会按「下载后打开安装程序」的设置处理。
- 手动取消应用关联后，重启 CandyTick 仍会记住这个选择。卸载已关联的应用后，CandyTick 会在下次检查时解除关联。
- 粘贴较长的 GitHub 令牌时，输入框会保留完整内容。
- 安装完成后，CandyTick 会清理与已装应用和版本匹配的 APK，并检查关联仓库的更新通知是否已过期。
- 在设置中点一次「通知权限」即可进入系统设置。系统无法打开权限设置、分享菜单或文件选择器时，应用会给出提示。
- 限额恢复后的首次刷新，不再把已看过的提交和版本重新标记为未读。
- 只有仓库确实无法访问（已删除或令牌无权限）时才显示「不可访问」；网络波动和限额不再误标，首页与详情页保持一致。
- 只有少量订阅时，仓库卡片不再出现在屏幕底部。
- 下载缺少文件大小的安装包时，会显示「正在下载」，避免进度一直停在 0%。
- 修复了自动同步与下拉刷新重叠时，刷新提示提前收起、展开卡片偶尔仍显示旧提交的问题。
- 修复相同的打包版本偶尔无法比对的问题。同一版本号末尾的 Git 提交哈希不同时，保留两边的版本信息供核对。

### English summary

- Retry unsaved subscription or settings changes immediately or from the banner later. If stored data is temporarily unavailable at startup, you can retry loading it. Large backups are shared as JSON files.
- Load more branches from the branch picker. The history tabs use shorter labels when space is tight.
- Tap the selected sort option again to reverse the order. Pinned repositories stay at the top. Recent activity includes commits on the selected branch and releases with installable packages. Refreshing or pinning a repository keeps the card you’re reading in view where possible.
- Open a notification for a single repository to go to that release. Only that release is marked as read. Update availability still depends on the installed version.
- Downloads continue after you leave the details page. Split packages keep their original extensions. After installation, CandyTick cleans up the matching APK and checks for stale update notifications.
- When a repository download finishes in the background, its notification opens the matching release. You can install the saved file even while offline. Clear cache preselects packages found during its scan and lets you choose whether to delete the crash log.
- Following the system language respects your full language preference list. Background notifications also use the app’s selected language. Turning off automatic CandyTick update checks pauses automatic downloads and remembers your choice for next time.
- Long-press the globe icon to copy the repository URL. The installed-app picker stays the same height as you search.
- Repository cards give the full path more room and wrap long names at separators. Clearing a search field brings the keyboard back for the next query.
- Search results and previews show the same app icon for a linked repository. You can tap Add while CandyTick looks for a matching installed app.
- Closing a repository preview returns you to your place in the search results. If you opened a link, you can edit it or press Search to reopen it.
- Repository search shows the reason for a failed request. If another page of commits or releases fails to load, you can retry at the end of the list.
- Cached commits and releases appear as the detail page opens and stay interactive during refresh. When the first page takes a moment, placeholders appear sooner, including when switching to a tab that is already loading. Pulling to refresh updates the overview and history together when the indicator shows “Refresh complete”.
- The keyboard opens after the home search field expands, reducing layout work when searching a long repository list.
- CandyTick can download its own updates over mobile data when automatic downloads are enabled. Repository sync still follows your network settings.
- Retrying a CandyTick update download now honors the setting to open the installer when the download finishes.
- Unlinking an app now persists across restarts. Uninstalled apps are unlinked on the next check, and long GitHub tokens can be pasted in full.
- The sort sheet now uses the same title spacing as other sheets. Tapping notification permission in Settings opens the system settings on the first tap. CandyTick shows a message if a settings page, share menu, or file picker is unavailable.
- The home screen shows a sync result icon only after the loading dots have appeared. Expanded cards keep their activity times and commits visible during refresh. When a pull-to-refresh overlaps with automatic sync, the indicator waits for expanded cards to catch up.
- Downloads show “Downloading…” when the package size is unknown. The percentage appears when the size is available.
- Version summaries omit the `v` prefix before a number, while release history preserves the original tag. Identical packaging labels now match, and different Git hashes on the same version no longer imply an update.
- Settings sheets scroll in short windows so their actions remain reachable with the keyboard open. In short windows the CandyTick update sheet places the download button above the release notes.
- Tap the home or detail title to scroll back to the top; tapping again while already there collapses the expanded cards or release notes.
- Subscribing to CandyTick's own repository links the app automatically, and the suggestion returns after you unlink it. An empty home screen offers an import entry that opens the import sheet in Settings.
- Without subscriptions the home screen hides search and sort and ignores pull-to-refresh. When a background sync fails entirely from GitHub rate limiting, the result message names the limit and when it resets.
- The undo pill after deleting a repository names it, and its action button sits on a subtle chip. A confirmed swipe delete dissolves in place instead of bouncing back.
- Result glyphs gained a ring, download controls are evenly spaced, and text fields show a faint stroke when unfocused.
- Backups now contain repository basics only; old full backups still import, and offline imports fill in once the network returns. Seen commits no longer relight as unread after a rate-limit window, and only truly inaccessible repositories show as such. The end-of-list marker stays faint, and a short list no longer sinks its last card to the bottom of the screen.

## [0.4.2] - 2026-11-20

### 优化

- 提交历史、版本历史等列表滚到底部时显示「已到底 · 共 N 项」，方便查看总数。

### 修复

- 版本卡片上的「删除已下载安装包」按钮与相邻按钮等高。列表行按下时的高亮和小标签也统一了圆角样式。

### English summary

- Paginated lists such as commit and release history show an end marker with the total count once you scroll to the bottom.
- The delete-download button aligns with its neighbors in height, and pressed rows share the same rounded highlight and chip styles as the rest of the app.

## [0.4.1] - 2026-11-20

### 优化

- 首页还没有订阅时，提示居中显示，原先的口号换成一行简短说明。搜索分支或本机应用没有结果时，也会显示提示。
- 设置页的主题和 Wi-Fi 图标改为居中的对称图形。首页「收起全部」改用列表加双上箭头的图标，与旁边的排序按钮呼应。

### 修复

- 经聊天工具转发的备份文件也能在文件选择器中选中。备份中的仓库都已订阅时会提示「没有新增」，导入大文件时会在后台读取。
- 统一了英文界面中应用更新通知渠道的名称和版本提示的大小写。

### English summary

- Empty and no-result states are centered with shorter, clearer copy, including branch and app searches with no matches.
- Theme and Wi-Fi icons are symmetric, and Collapse all pairs with the sort button using a list-and-chevrons icon.
- Backup files forwarded through chat apps can be selected in the file picker. Importing a backup with nothing new says so, and large files are read in the background.
- The English interface now uses consistent capitalization for the update channel name and version hints.

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

### English summary

- Release notes render Markdown tables, bold, strikethrough, code blocks, and autolinks; `#123` opens the matching issue, and very long notes are trimmed to keep the page fast.
- Notification taps open the right screen — repository updates open the detail page, CandyTick updates open Settings — and simultaneous releases collapse into one grouped notification.
- Tablets and unfolded foldables get a centered, width-capped landscape layout, and expanded cards can all be collapsed from a single top-bar button.
- The search field focuses on open, clears with one tap, and dismisses the keyboard on Enter; the token field gained a hint label.
- Tapping a search result previews the repository in a sheet that keeps your place in the results when closed. Press states darken slightly, more naturally in dark mode.
- Expanding a card loads commits and releases together; installing an APK refreshes the update badges immediately, and the header download entry shows its progress.
- Sync reports the number of failed repositories when only some fail, and a full failure is called out separately.

## [0.3.2] - 2026-11-19

### 新增

- 点版本号可展开更新日志，其中的 GitHub 表情符号也能正常显示。日志中的链接和「查看完整更新说明」可在浏览器打开。点提交记录可前往 GitHub 查看变更。
- 版本历史为本机已安装的版本加上「当前」标签。有多个安装包时，使用 Windows 风格的单选框选择。

### 优化

- 设置页将权限选项移到对应功能旁，移除了「仅充电时同步」选项。
- 仓库路径改用等宽字体，与版本号和提交哈希的显示方式一致。
- 按下卡片或列表项时，两者都会略微下沉。
- 在较长的列表里展开更新日志后，滚动更流畅。

### English summary

- Tap a version to expand its release notes, with GitHub emoji rendered and links plus “View full changelog” opening in the browser; tapping a commit opens it on GitHub.
- The installed version is labeled “Current”, and releases with several packages pick between them with a Windows-style radio.
- Permission settings moved next to their features, repository paths use a monospace font, and expanded notes scroll smoothly in long lists.

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

### English summary

- Unused resources were removed, shrinking the APK by about 270 KB, and tapping the version in Settings checks for updates with live status.
- The update sheet, package selection, and tab icons gained smoother transitions, and reduced-motion settings now stop loading and delete animations too.
- The installed-app list loads in the background, unlinking uses a broken-chain icon, and app icons use flat backgrounds that render correctly in third-party installers.
- Repeated update checks keep the downloaded package, and the long-press copy gesture that could glitch system components was removed.

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

### English summary

- A dedicated update sheet shows the new version, its notes, download progress, and install button; failures say whether it was the network, a timeout, or rate limiting, with a retry.
- Clear cache breaks down space by downloaded packages, self-updates, and crash logs before you choose what to remove.
- The add page gained input hints, debounced search, retryable failures, and a shortcut for already-subscribed repositories. Language changes apply immediately.
- Haptics confirm pull-to-refresh, swipe actions, and deletions; tapping the home or detail title scrolls back to the top.
- Icons were unified on Lucide, the launcher adapts to the system theme, and APK, APKS, XAPK, and APKM packages are all supported, with desktop-only releases filtered out and no longer marked updatable.

## [0.2.1] - 2026-11-17

### 新增

- 删除仓库、完成同步、导入备份或复制内容后，结果都显示在顶部提示条中。删除仓库后可直接点提示条上的「撤销」恢复。
- 发现 CandyTick 新版本后，检查更新入口下方会显示版本号、更新说明和下载按钮。
- 更新说明会显示 GitHub 上的发布正文。

### English summary

- Results for deletions, syncs, imports, and copies moved to a top toast bar, with an Undo action for deleted repositories.
- A new CandyTick version shows its number, release notes, and download button right under Check for updates, using the GitHub release body.

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

### English summary

- The detail page can browse other branches and search them by name; swipe a home card left to pin or unpin it.
- Adding a repository auto-links its matching installed app, the title follows the app name, and the header downloads the new version with optional auto-install on completion.
- Version comparison understands date-plus-hash schemes like `26.11.caccb78` and prereleases such as `2.2.2-rc0`.
- Deletions dissolve into particles, syncing shows three bouncing dots by the title, and sheets share one slide animation.
- Fixed hash-bearing versions being reported incomparable, active repositories repeatedly flagged as updated, and all imported repositories lighting up after the first sync.

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

### English summary

- First public release: subscribe to GitHub repositories, browse commits and releases, link installed apps for version checks, architecture-aware APK picks, background sync with notifications, optional token support, search, sort and undo on the home list, backup export and import, self-updates, dark and light themes, and Chinese and English interfaces.
