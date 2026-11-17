简体中文 | [English](README.en.md)

<div align="center">

# CandyTick

**还在维护吗？** —— GitHub 仓库生命体征监测器

[![License](https://img.shields.io/badge/License-GPL--3.0-blue)](LICENSE)
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

- 维护状态：按默认分支最近提交时间分为 活跃 / 一般 / 低活跃 / 疑似弃坑，归档仓库单独标记
- 提交历史与 Release 历史，详情页分页浏览；提交视图可切换任意分支
- 关联本机应用后比对版本，标题自动显示应用名（应用改名会自动跟随）；高置信自动关联（应用名 / 包名匹配），无需手动操作
- 版本比较覆盖真实世界格式：x.y.z、CalVer+git hash（26.11.caccb78）、预发布（2.2.2-rc0）等，无法比较时如实提示，不做猜测
- 详情页一键下载更新：下载 → 进度 → 安装；可开启下载完成后自动安装
- 多 APK 按设备 ABI 推荐，可手动改选；下载的 APK 会保留，安装失败无需重新下载
- 后台定时同步，新版本通知；同步可限制仅 Wi-Fi、仅充电；同步进度与结果轻量反馈
- 列表：搜索、排序、左滑置顶、右滑删除（带粒子消散动效与撤销）、有更新标识
- 深浅色主题，跟随系统或手动指定；应用内中英双语
- 匿名即可使用；可选 GitHub Token 提升接口限额（60 次/小时 → 5000 次/小时）
- 订阅支持导出与合并导入（含关联应用、置顶等全部数据）
- 应用自身更新：手动检查、自动检查、仅 Wi-Fi 自动下载、Android 12+ 且本应用为安装来源时静默安装

## 构建

JDK 17+：

```bash
gradlew assembleDebug
```

## 技术栈

Kotlin · Compose + [Miuix](https://github.com/compose-miuix-ui/miuix) · Navigation 3 · OkHttp · WorkManager · 单 JSON 状态文件（无 Room / 无 KSP）

## 许可

[GPL-3.0](LICENSE) · 版本比较与 APK 选择的思路参考了 [Obtainium](https://github.com/ImranR98/Obtainium)
