<div align="center">

# CandyTick

<p><strong>在 Android 上订阅 GitHub 仓库，跟进提交和版本发布，为已安装的应用查找更新。</strong></p>

<p>无需登录即可订阅公开仓库，按分支浏览提交记录，<br>阅读发布说明，并在新版本发布时收到提醒。</p>

<p>
  <a href="https://github.com/JeffioZ/CandyTick/releases/latest"><img alt="最新版本" src="https://img.shields.io/github/v/release/JeffioZ/CandyTick?display_name=tag&sort=semver&style=flat&color=14755B"></a>
  <a href="https://github.com/JeffioZ/CandyTick/releases"><img alt="下载总量" src="https://img.shields.io/github/downloads/JeffioZ/CandyTick/total?style=flat&label=downloads&color=0F9D7A"></a>
  <img alt="Android 10 及以上" src="https://img.shields.io/badge/Android-10%2B%20%28API%2029%2B%29-32674F?style=flat&logo=android&logoColor=white">
  <a href="LICENSE"><img alt="GPL-3.0 许可证" src="https://img.shields.io/github/license/JeffioZ/CandyTick?style=flat&color=3C6284&label=GPL--3.0"></a>
  <a href="https://github.com/JeffioZ/CandyTick/stargazers"><img alt="星标" src="https://img.shields.io/github/stars/JeffioZ/CandyTick?style=flat&color=806119"></a>
</p>

<p>
  <a href="https://github.com/JeffioZ/CandyTick/releases/latest"><img alt="下载最新版 APK" src="https://img.shields.io/badge/下载-最新版%20APK-14755B?style=for-the-badge&logo=github&logoColor=white"></a>
</p>

<p><a href="README.en.md">English</a></p>

</div>

> **系统要求：** Android 10（API 29）及以上。安装包为 32 位与 64 位 ARM 通用，覆盖绝大多数设备。

## 🏠 首页

<p align="center"><sub>订阅的仓库以卡片呈现，随刷新保持当前阅读位置。<br>
按最近推送、可用更新、活跃程度等五种方式排序，支持反向排列。</sub></p>

<p align="center">
  <a href="docs/fastlane/metadata/android/zh-CN/images/phoneScreenshots/1.png"><img src="docs/fastlane/metadata/android/zh-CN/images/phoneScreenshots/1.png" alt="浅色模式下的首页，列出订阅的仓库及其活跃程度" width="42%"></a>
  &ensp;
  <a href="docs/fastlane/metadata/android/zh-CN/images/phoneScreenshots/2.png"><img src="docs/fastlane/metadata/android/zh-CN/images/phoneScreenshots/2.png" alt="深色模式下的同一份仓库列表" width="42%"></a>
</p>

## ✨ 功能一览

| | 功能 | 说明 |
| --- | --- | --- |
| 📬 | **订阅仓库** | 粘贴链接、按名称或关键词搜索，无需登录 GitHub 账号。 |
| 📜 | **浏览动态** | 按分支查看提交记录，在版本历史中阅读 Markdown 发布说明，`#123` 编号可直达 Issue。 |
| 📲 | **更新应用** | 自动识别仓库对应的本机应用并比较版本；多安装包时按设备架构推荐合适的安装包。 |
| 🔔 | **更新提醒** | 后台定期同步，发现新版安装包时通知你，可限制仅在不计费网络下自动同步。 |
| 📌 | **整理订阅** | 置顶常看的仓库、按名称搜索、按活跃度或可用更新排序，删除后可撤销。 |
| 💾 | **备份迁移** | 将订阅导出为 JSON，换机后直接导入；已订阅的仓库会自动跳过。 |

**轻量为本：** CandyTick 不需要账号、不依赖云服务，所有数据留在本机。订阅公开仓库的匿名请求限额为每小时 60 次；在设置中添加 [GitHub 令牌](https://docs.github.com/zh/rest/using-the-rest-api/rate-limits-for-the-rest-api)可提高到 5,000 次。

## 🚀 快速上手

1. 从 [Releases](https://github.com/JeffioZ/CandyTick/releases/latest) 下载 APK 并安装。
2. 点首页右上角的 **＋**，粘贴 GitHub 仓库链接（如 `JeffioZ/CandyTick`），或输入仓库名、按关键词搜索。
3. 确认预览中的仓库信息，点 **添加仓库**。

想跟进某个应用的更新？添加它的 GitHub 仓库，CandyTick 会自动关联同名应用，有新版时提醒你。

## 📚 更多

| | 阅读 |
| --- | --- |
| 📝 | [更新日志](CHANGELOG.md) — 每个版本的全部变化，附英文摘要 |
| 🔨 | [从源码构建](#从源码构建) — JDK 21 + Android SDK，一条命令构建调试包 |

## 🔨 从源码构建

发布构建使用 JDK 21，所需 Android SDK 平台见[应用构建配置](app/build.gradle.kts)。要试用主分支上[尚未发布的改动](CHANGELOG.md#未发布)，可在项目根目录构建调试包。

```sh
bash gradlew assembleDebug
```

在 Windows PowerShell 中运行 `.\gradlew.bat assembleDebug`。调试包生成在 `app/build/outputs/apk/debug/app-debug.apk`。

## 🤝 致谢

CandyTick 的界面使用 [Miuix](https://github.com/compose-miuix-ui/miuix) 和 [Lucide 图标](https://lucide.dev)。版本比较和安装包选择参考了 [Obtainium](https://github.com/ImranR98/Obtainium)。

## 📄 许可证

[GPL-3.0](LICENSE)
