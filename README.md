<div align="center">

# CandyTick

在 Android 上订阅 GitHub 仓库，跟进提交和版本发布，为已安装的应用查找更新。

**[下载最新版 APK](https://github.com/JeffioZ/CandyTick/releases/latest)** · [更新日志](CHANGELOG.md) · [English](README.en.md)

Android 10 及以上 · 32 位与 64 位 ARM 通用

</div>

## 添加第一个仓库

1. 点首页右上角的 **＋**。粘贴 GitHub 链接、输入 `LibChecker/LibChecker` 这样的仓库名，或按关键词搜索。
2. 在预览中点 **添加仓库** 即可订阅。公开仓库无需登录。

<p align="center">
  <a href="docs/fastlane/metadata/android/zh-CN/images/phoneScreenshots/1.png"><img src="docs/fastlane/metadata/android/zh-CN/images/phoneScreenshots/1.png" width="36%" alt="浅色模式下的首页，列出订阅的仓库及其活跃程度"></a>
  &ensp;
  <a href="docs/fastlane/metadata/android/zh-CN/images/phoneScreenshots/2.png"><img src="docs/fastlane/metadata/android/zh-CN/images/phoneScreenshots/2.png" width="36%" alt="深色模式下的同一份仓库列表"></a>
</p>

## 浏览仓库动态

以下介绍主分支的功能，尚未发布的改动列在更新日志顶部。

- **查看提交和版本。** 在详情页切换分支可查看该分支的提交。版本历史里可以阅读发布说明，包括表格、代码块和任务列表，也可以点开关联的 GitHub Issue。较长的说明可在 GitHub 阅读全文。
- **整理首页。** 可以搜索、置顶或移除仓库，误删后可撤销。支持按最近活动、可用更新、活跃程度、添加时间或仓库名称排序。再点一次当前选项即可反向排列。
- **判断活跃程度。** 仓库的活跃程度取决于最近一次推送的时间，已归档的仓库会单独标记。
- **同步仓库。** 打开应用时或按设定的周期自动同步，可限制为仅使用非计费网络。手动刷新可以使用移动数据。
- **查看更新通知。** 点开单个仓库的新版安装包通知，会定位到对应版本，只清除这次发布的未读标记。「可更新」由本机安装的版本决定。

## 更新已安装的应用

CandyTick 会尝试将仓库与本机应用关联，也可以手动选择。关联后，仓库会显示应用名，并将本机版本与发布版本进行比较。常见版本号、日期版本和预发布版本均可比较，遇到无法判断的格式会给出提示。

同一版本有多个安装包时，CandyTick 会根据设备架构推荐。下载过程中可查看进度或取消，也可设置为下载完成后打开安装程序。标准 APK 使用系统安装程序，APKS、XAPK 和 APKM 需要兼容的第三方安装程序。

## 设置与备份

- **备份订阅。** 可将订阅列表及仓库概况导出为 JSON，导入时会跳过已订阅的仓库。个人设置、GitHub 令牌和已下载的安装包不包含在备份中。
- **清理存储空间。** 可分别清理已下载的安装包和崩溃日志。
- **GitHub 令牌。** 如果经常遇到请求次数限制，可在设置中添加令牌以提高 [API 请求限额](https://docs.github.com/zh/rest/using-the-rest-api/rate-limits-for-the-rest-api)。
- **更新 CandyTick。** 可自动检查自身更新。开启自动下载后，Wi-Fi 和移动数据均可用于下载新版。同时开启「下载后打开安装程序」时，若系统允许，CandyTick 可免确认安装自身更新。
- **语言与外观。** 提供简体中文和英文，支持深浅色主题、横屏、平板与折叠屏。

## 从源码构建

发布构建使用 JDK 21，所需 Android SDK 平台见[应用构建配置](app/build.gradle.kts)。在项目根目录运行以下命令即可生成调试包。

```sh
bash gradlew assembleDebug
```

在 Windows PowerShell 中运行 `.\gradlew.bat assembleDebug`。调试包生成在 `app/build/outputs/apk/debug/app-debug.apk`。

项目使用 Kotlin、Jetpack Compose、[Miuix](https://github.com/compose-miuix-ui/miuix)、Navigation 3、OkHttp 和 WorkManager。发布说明由 [commonmark-java](https://github.com/commonmark/commonmark-java) 解析，订阅数据以 JSON 保存在本机。

## 许可与致谢

CandyTick 以 [GPL-3.0](LICENSE) 发布。版本比较和安装包选择参考了 [Obtainium](https://github.com/ImranR98/Obtainium)。界面图标来自 [Lucide](https://lucide.dev)，应用内附有 [Lucide 和 Feather 的许可声明](app/src/main/assets/licenses/lucide.txt)。
