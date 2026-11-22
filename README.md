<div align="center">

# CandyTick

![Android 10 及以上（API 29+）](https://img.shields.io/badge/Android-10%2B%20%28API%2029%2B%29-32674F?logo=android&logoColor=white)
[![GPL-3.0 许可证](https://img.shields.io/badge/License-GPL--3.0-3C6284)](LICENSE)
[![GitHub 星标数](https://img.shields.io/github/stars/JeffioZ/CandyTick?style=flat&label=Stars&color=806119)](https://github.com/JeffioZ/CandyTick/stargazers)

在 Android 上订阅 GitHub 仓库，跟进提交和版本发布，为已安装的应用查找更新。

[![下载最新版 APK](https://img.shields.io/github/v/release/JeffioZ/CandyTick?style=for-the-badge&label=%E4%B8%8B%E8%BD%BD%20APK&logo=github&logoColor=white&color=14755B)](https://github.com/JeffioZ/CandyTick/releases/latest)

32 位与 64 位 ARM 通用 · [更新日志](CHANGELOG.md) · [English](README.en.md)

</div>

## 添加第一个仓库

1. 点首页右上角的 **＋**，粘贴 GitHub 仓库链接，或输入仓库名（如 `LibChecker/LibChecker`）。也可以按关键词搜索。
2. 确认预览中的仓库，点 **添加仓库**。订阅公开仓库无需登录。

<p align="center">
  <a href="docs/fastlane/metadata/android/zh-CN/images/phoneScreenshots/1.png"><img src="docs/fastlane/metadata/android/zh-CN/images/phoneScreenshots/1.png" width="36%" alt="浅色模式下的首页，列出订阅的仓库及其活跃程度"></a>
  &ensp;
  <a href="docs/fastlane/metadata/android/zh-CN/images/phoneScreenshots/2.png"><img src="docs/fastlane/metadata/android/zh-CN/images/phoneScreenshots/2.png" width="36%" alt="深色模式下的同一份仓库列表"></a>
</p>

## 浏览仓库动态

- **查看最近改动。** 按分支浏览提交记录，在版本历史中阅读发布说明。首页会标出仓库的活跃程度和归档状态。
- **整理订阅。** 可以把常看的仓库置顶，按名称搜索，或让有可用更新的仓库排在前面。
- **接收更新提醒。** CandyTick 可定期同步仓库，并在发现新版安装包时通知你。开启「按流量计费时暂停自动同步」后，仍可使用移动数据下拉刷新。
- **备份与迁移。** 将订阅和仓库概况导出为 JSON，换机后即可导入。导入时会跳过已订阅的仓库。

如果经常看到 GitHub 请求次数已达上限的提示，可以在设置中添加 GitHub 令牌，提高 [API 请求限额](https://docs.github.com/zh/rest/using-the-rest-api/rate-limits-for-the-rest-api)。

## 更新已安装的应用

CandyTick 会尝试识别仓库对应的本机应用，对比已安装版本和仓库发布的版本。你也可以在仓库详情页手动关联应用。

在版本历史里选择安装包并下载。如果同一版本提供多个安装包，CandyTick 会根据设备架构推荐匹配项。普通 APK 可用系统安装程序打开，APKS、XAPK 和 APKM 需要兼容的第三方安装程序。

## 从源码构建

发布构建使用 JDK 21，所需 Android SDK 平台见[应用构建配置](app/build.gradle.kts)。要试用主分支上[尚未发布的改动](CHANGELOG.md#未发布)，可在项目根目录构建调试包。

```sh
bash gradlew assembleDebug
```

在 Windows PowerShell 中运行 `.\gradlew.bat assembleDebug`。调试包生成在 `app/build/outputs/apk/debug/app-debug.apk`。

## 致谢

CandyTick 的界面使用 [Miuix](https://github.com/compose-miuix-ui/miuix) 和 [Lucide 图标](https://lucide.dev)。版本比较和安装包选择参考了 [Obtainium](https://github.com/ImranR98/Obtainium)。

## 许可证

CandyTick 采用 [GNU 通用公共许可证第 3 版](LICENSE)。
