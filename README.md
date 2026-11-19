简体中文 | [English](README.en.md)

<div align="center">

# CandyTick

**GitHub 仓库维护状态追踪与新版本安装**
*Track repo health & updates*

[![License](https://img.shields.io/badge/License-GPL--3.0-blue)](LICENSE)
[![Release](https://img.shields.io/github/v/release/JeffioZ/CandyTick)](https://github.com/JeffioZ/CandyTick/releases)
![Platform](https://img.shields.io/badge/Android-3DDC84?logo=android&logoColor=white)
![API](https://img.shields.io/badge/API-29%2B-9E9E9E)

订阅 GitHub 仓库，跟踪维护状态；有新版本时可以下载安装。

[→ 从 Releases 下载](https://github.com/JeffioZ/CandyTick/releases)

</div>

<!-- 截图占位：补充 2~4 张后取消注释
<p align="center">
  <img src="docs/fastlane/metadata/android/zh-CN/images/phoneScreenshots/1.png" width="270" />
</p>
-->

## 功能

- 维护状态：按最近提交时间分为 活跃 / 一般 / 低活跃 / 疑似弃坑，归档仓库单独标记
- 提交历史与版本历史，提交可切换分支查看
- 关联本机应用后自动比对版本，标题跟随应用名；能自动识别已装的对应应用
- 版本比较支持常见格式（x.y.z、日期式、预发布版），比不了的会直接说明
- 下载 → 进度 → 安装一步到位，可取消；可开启下载完自动安装
- 多安装包时自动推荐适合设备的版本，支持 apk / apks / xapk / apkm
- 后台定期同步，有新版本时通知；可限制仅 Wi-Fi
- 搜索、排序、置顶、删除（可撤销）
- 深浅色主题；中英双语
- 无需注册，可选 GitHub Token 提高限额（60 → 5000 次/小时）
- 订阅可导出、合并导入
- 缓存清理：已下载的安装包与崩溃日志
- 应用自身更新：检查、自动下载、静默安装

## 构建

JDK 17+：

```bash
gradlew assembleDebug
```

## 技术栈

Kotlin · Compose + [Miuix](https://github.com/compose-miuix-ui/miuix) · Navigation 3 · OkHttp · WorkManager · 单 JSON 状态文件（无 Room / 无 KSP）

## 许可

[GPL-3.0](LICENSE) · 版本比较与 APK 选择的思路参考了 [Obtainium](https://github.com/ImranR98/Obtainium) · UI 图标集取自 [Lucide](https://lucide.dev)（ISC）
