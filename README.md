# CandyTick

> still ticking? —— 仓库生命体征监测器，先告诉你「还在维护吗」，再谈更新。

一个 Android 应用：订阅 GitHub 仓库，优先监测其维护活跃度（最近提交、归档状态），其次检测新版本 release 并下载安装。

![License](https://img.shields.io/badge/License-GPL--3.0-blue) ![Platform](https://img.shields.io/badge/platform-Android-3DDC84) ![Min SDK](https://img.shields.io/badge/minSDK-29%20(Android%2010)-9E9E9E) ![Version](https://img.shields.io/badge/version-0.1.0-18936C)

## 命名

- 仓库名：`CandyTick` · 包名：`io.candy.tick`（candy 家族命名空间，不带个人 ID）
- 语义：英语 "still ticking" =（钟表/心脏）还在走；每次 commit 是一次 tick
- 系列：candy 家族（candykit、candytick……），小而美的个人工具箱

## 为什么做这个

Obtainium 这类工具专注于「从源码仓库抓最新 APK」，每个源都要配一套规则，复杂，且不关心仓库死活。CandyTick 把优先级反过来：

1. **维护状态优先** —— 默认分支最近提交时间 + 归档状态，一眼看出哪些应用还在活跃维护
2. **更新其次** —— release 新版本检测、APK 下载、拉起系统安装器

## 功能（v0.1.0）

### 监控

- 粘贴 GitHub 仓库链接添加订阅（自动解析 `owner/repo`），GitHub 搜索辅助
- 维护状态徽章（深浅双主题各一档配色，WCAG 达标）：
  - 已归档（repo archived）
  - 活跃：默认分支最新提交 ≤ 30 天（绿）
  - 一般：≤ 180 天（黄）
  - 低活跃：≤ 365 天（橙）
  - 疑似弃坑：> 365 天（红）
- 展开卡片看最近提交；详情页浏览完整提交历史（分页）
- 排序：最后提交时间 / 状态 / 名称 / 可更新优先
- 改名跟随：仓库改名后经 301 重定向自动归位

### 更新

- 最新 release 与本机已装版本对比（关联本机应用后生效）；**版本比较宁可不比**——仅支持 `x.y.z` / `x.y` / 纯数字标准格式，不可比时如实显示「无法比较版本」，绝不字符串不等就报更新
- 多 APK 自动按设备 ABI 推荐（arm64-v8a / armeabi-v7a / x86_64 / universal 别名表），可手选
- APK 下载后**保留**，安装失败不删，可直接重装
- 系统安装器安装；下载保留复用避免重复下载几十 MB

### 自身更新

- 设置页手动「检查更新」+ 后台同步顺带检查（默认开）
- 自动下载默认关，开启后仅 Wi-Fi 下载
- Android 12+ 且 CandyTick 为自身安装来源时静默安装；否则通知栏「新版本已下载 · 点击安装」走系统确认

### 后台与省流

- 周期同步（每 6 / 12 / 24 小时），可约束「仅 Wi-Fi」「仅充电」
- ETag 条件请求：304 响应不计配额
- 可选 GitHub Token（匿名 60 次/小时 → 认证 5000 次/小时），classic PAT 不勾任何 scope 即可
- 单仓库失败不拖垮列表；限流时显示恢复时间

## 范围

- 仅手机（竖屏锁定），不适配平板/折叠屏/桌面模式
- 深浅模式完整适配：默认跟随系统，设置内三档可选
- 监控的仓库一律**手动**下载安装；自动升级仅指 CandyTick 自身

## 下载

GitHub Releases：https://github.com/JeffioZ/CandyTick/releases

## 构建

- Android Studio 最新 stable（内置 JDK 17+），或命令行 JDK 17~21
- 版本单点管理于 `gradle/libs.versions.toml`

```bash
# Windows
gradlew.bat assembleDebug
# macOS / Linux
./gradlew assembleDebug
```

产物位于 `app/build/outputs/apk/debug/`。

## 技术栈

依赖原则：尽量少依赖，自写成本低的一律自写；第三方库不魔改、不 fork，随上游升级（版本只改 `libs.versions.toml` 一行）。

Kotlin 2.4（AGP 内置 Kotlin）· Jetpack Compose + **Miuix**（HyperOS 风格组件体系，整体替代 Material 3 组件）· Navigation 3 · OkHttp 5 + kotlinx.serialization（手写 API 封装收敛在单文件）· Coil 3 · WorkManager · core-splashscreen · 手动 DI（`AppContainer`）· 单 JSON 状态文件原子写（无 Room / 无 KSP / 无 DataStore）· Gradle KTS + Version Catalog · minSdk 29 / targetSdk 37

## 开源许可与致谢

本项目以 [GPL-3.0](LICENSE) 发布。

依赖的开源项目（均为 Apache License 2.0，与 GPL-3.0 兼容）：

| 项目 | 用途 |
|---|---|
| [Miuix](https://github.com/compose-miuix-ui/miuix) | HyperOS 风格 Compose 组件体系（UI 主框架） |
| [Jetpack Compose](https://developer.android.com/compose) / [androidx](https://developer.android.com/jetpack) | UI、核心 KTX、SplashScreen |
| [Navigation 3](https://developer.android.com/develop/ui/navigation3) | 类型安全导航与返回栈 |
| [OkHttp](https://square.github.io/okhttp/) | 网络与 APK 下载 |
| [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) | JSON 模型与持久化 |
| [Coil](https://coil-kt.github.io/coil/) | 图片加载 |
| [WorkManager](https://developer.android.com/jetpack/androidx/releases/work) | 周期后台同步 |

产品思路上借鉴了 [Obtainium](https://github.com/ImranR98/Obtainium) 的保守版本比较与多 APK ABI 选择经验（Obtainium 为 GPL-3.0 项目，本项目同为其精神同源）。

## 路线图

- [ ] V2：扫描本机已装应用，经 F-Droid / IzzyOnDroid 索引与 GitHub 搜索匹配仓库，批量导入
- [ ] V2+：GitLab / Gitee / Codeberg 源支持（`SourceProvider` 抽象已预留）
- [ ] 桌面小部件：状态一览
- [ ] 液态玻璃浮层（TopBar / FAB）视觉打磨

## 设计

设计文档内部保存（`docs/`，不入库）。
