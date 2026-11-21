[简体中文](README.md) | English

<div align="center">

# CandyTick

**Track GitHub repo health & updates**
*GitHub 仓库维护状态追踪与新版本安装*

[![License](https://img.shields.io/badge/License-GPL--3.0-blue)](LICENSE)
[![Stars](https://img.shields.io/github/stars/JeffioZ/CandyTick?style=flat&color=yellow)](https://github.com/JeffioZ/CandyTick/stargazers)
![Platform](https://img.shields.io/badge/Android-3DDC84?logo=android&logoColor=white)
![API](https://img.shields.io/badge/API-29%2B-9E9E9E)

Track GitHub repositories and their maintenance status; download and install new releases when available.

[![Download](https://img.shields.io/github/v/release/JeffioZ/CandyTick?style=for-the-badge&label=Download&logo=github&color=1fb574)](https://github.com/JeffioZ/CandyTick/releases)

</div>

<p align="center">
  <img src="docs/fastlane/metadata/android/en-US/images/phoneScreenshots/1.png" width="270" />
  <img src="docs/fastlane/metadata/android/en-US/images/phoneScreenshots/2.png" width="270" />
</p>

## Features

### Tracking

- See how recently a repository received a push. Activity levels range from Active to Inactive. Archived repositories have their own label.
- Commit history and release history; commits can be browsed on any branch
- Sync on app open or on a schedule, optionally only on unmetered networks. Tap a new-package notification to open the matching release. Reading it clears that release's unread marker.
- Search, sorting, pinning, delete with undo

### Versions & install

- Read Markdown tables, code blocks, task lists, and linked issue references in release notes. Long notes have a preview and a link to the full text.
- Link an installed app for version comparison, with the title following the app name; matching installed apps are recognized automatically
- Version comparison covers common formats (x.y.z, CalVer, pre-release); if a version can't be compared, it says so
- Follow download progress, cancel a download, or have the installer open automatically when the download finishes
- Recommends packages by device architecture. Standard APKs open in the system installer. APKS / XAPK / APKM bundles require a compatible third-party installer.

### Data & experience

- No account needed; an optional GitHub token raises the rate limit (60 → 5,000 req/h)
- Subscriptions can be exported and merged back in
- Clean up downloaded packages and crash logs
- CandyTick can check for and download its own updates automatically, then install without a prompt when Android permits it.
- Light & dark themes, English / 简体中文; tablet and foldable friendly (bounded content column, free orientation)

## Building

JDK 17+:

```bash
bash gradlew assembleDebug
```

In Windows PowerShell, run `.\gradlew.bat assembleDebug`.

## Tech stack

Kotlin · Compose + [Miuix](https://github.com/compose-miuix-ui/miuix) · Navigation 3 · OkHttp · WorkManager · [commonmark-java](https://github.com/commonmark/commonmark-java) (Markdown) · single JSON state file (no Room / no KSP)

## License

[GPL-3.0](LICENSE) · Version comparison and APK selection are informed by [Obtainium](https://github.com/ImranR98/Obtainium) · UI icons come from [Lucide](https://lucide.dev). The [bundled license](app/src/main/assets/licenses/lucide.txt) includes the Lucide and Feather notices.
