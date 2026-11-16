# CandyTick

> still ticking? —— 仓库生命体征监测器，先告诉你「还在维护吗」，再谈更新。

一个 Android 应用：订阅 GitHub 仓库，优先监测其维护活跃度（最近提交、归档状态），其次检测新版本 release 并下载安装。

## 命名

- 仓库名：`candytick`（全小写）· 显示名：CandyTick · 包名：`io.candy.tick`（candy 家族命名空间，不带个人 ID）
- 语义：英语 "still ticking" =（钟表/心脏）还在走；每次 commit 是一次 tick
- 系列：candy 家族（candykit、candytick……），已查证全网无重名应用
- License：GPL-3.0

## 为什么做这个

Obtainium 这类工具专注于「从源码仓库抓最新 APK」，每个源都要配一套规则，复杂，且不关心仓库死活。CandyTick 把优先级反过来：

1. **维护状态优先** —— 默认分支最近提交时间 + 归档状态，一眼看出哪些应用还在活跃维护
2. **更新其次** —— release 新版本检测、APK 下载、拉起系统安装器

## 功能（V1）

- 粘贴 GitHub 仓库链接添加订阅（自动解析 `owner/repo`），GitHub 搜索辅助
- 维护状态徽章：
  - 已归档（repo archived）
  - 活跃：默认分支最新提交 ≤ 30 天（绿）
  - 一般：≤ 180 天（黄）
  - 低活跃：≤ 365 天（橙）
  - 疑似弃坑：> 365 天（红）
- 展开卡片看最近提交（标题 / 作者 / 相对时间），详情页浏览完整提交历史
- 最新 release 与本机已装版本对比，可更新时高亮；一键下载 APK 安装
- 每日后台同步（WorkManager）；可选填 GitHub Token（匿名 60 次/小时 → 认证 5000 次/小时）

## 范围

- 仅手机（竖屏锁定），不适配平板/折叠屏/桌面模式
- 深浅模式完整适配：默认跟随系统，设置内可选 系统/浅色/深色

## 路线图

- [ ] V2：扫描本机已装应用，经 F-Droid / IzzyOnDroid 索引与 GitHub 搜索匹配仓库，批量导入
- [ ] V2：静默更新（Android 12+，本 app 安装过的应用免确认，借鉴 Obtainium）
- [ ] V2+：GitLab / Gitee / Codeberg 源支持（`SourceProvider` 抽象已预留）
- [ ] 桌面小部件：状态一览

## 技术栈

依赖原则：尽量少依赖，自写成本低的一律自写；第三方库不魔改、不 fork，随上游升级。

Kotlin 2.x · Jetpack Compose + Miuix（HyperOS 风格）· OkHttp 5 + kotlinx.serialization · Coil 3 · AndroidLiquidGlass · WorkManager · 手动 DI · 单文件 JSON 持久化 · 无 KSP · Gradle KTS + Version Catalog

## 设计

设计文档内部保存（`docs/`，不入库）。
