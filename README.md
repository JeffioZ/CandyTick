<div align="center">

# CandyTick

**还在维护吗？** —— GitHub 仓库生命体征监测器

[![License](https://img.shields.io/badge/License-GPL--3.0-blue)](LICENSE)
![Platform](https://img.shields.io/badge/Android-3DDC84?logo=android&logoColor=white)
![API](https://img.shields.io/badge/API-29%2B-9E9E9E)

订阅 GitHub 仓库，一眼看出哪些还在活跃维护、哪些已经弃坑；顺带发现新版本、下载安装。

[→ 从 Releases 下载](https://github.com/JeffioZ/CandyTick/releases)

</div>

<!-- 截图占位：补充 2~4 张后取消注释
<p align="center">
  <img src="docs/fastlane/metadata/android/zh-CN/images/phoneScreenshots/1.png" width="270" />
</p>
-->

## 功能

- 维护状态徽章：活跃 / 一般 / 低活跃 / 疑似弃坑 / 已归档
- 展开卡片看最近提交；详情页浏览完整提交与 Release 历史
- 关联本机应用后比较版本（宁可不比，绝不误报）
- 多 APK 按设备 ABI 自动推荐；下载保留，安装失败不删
- 后台同步 + 新版本通知；可约束仅 Wi-Fi / 仅充电
- 深浅双主题（HyperOS 风格），跟随系统 / 浅色 / 深色三档
- 匿名可用，可选 GitHub Token 提升限额；ETag 条件请求省配额
- 应用自身更新：自动检查 / 仅 Wi-Fi 自动下载 / Android 12+ 静默安装

## 构建

JDK 17+：

```bash
gradlew assembleDebug
```

## 技术栈

Kotlin · Compose + [Miuix](https://github.com/compose-miuix-ui/miuix) · Navigation 3 · OkHttp · WorkManager · 单 JSON 状态文件（无 Room / 无 KSP）

## 许可

[GPL-3.0](LICENSE) · 版本比较与 ABI 选择思路致谢 [Obtainium](https://github.com/ImranR98/Obtainium)
