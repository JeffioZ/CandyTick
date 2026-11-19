[简体中文](README.md) | English

<div align="center">

# CandyTick

**Track GitHub repo health & updates**
*GitHub 仓库维护状态追踪与新版本安装*

[![License](https://img.shields.io/badge/License-GPL--3.0-blue)](LICENSE)
[![Release](https://img.shields.io/github/v/release/JeffioZ/CandyTick)](https://github.com/JeffioZ/CandyTick/releases)
![Platform](https://img.shields.io/badge/Android-3DDC84?logo=android&logoColor=white)
![API](https://img.shields.io/badge/API-29%2B-9E9E9E)

Track GitHub repositories and their maintenance status; download and install new releases when available.

[→ Download from Releases](https://github.com/JeffioZ/CandyTick/releases)

</div>

<!-- Screenshot placeholder: add 2-4 images, then uncomment
<p align="center">
  <img src="docs/fastlane/metadata/android/en-US/images/phoneScreenshots/1.png" width="270" />
</p>
-->

## Features

- Maintenance status by latest commit: Active / Fair / Low activity / Likely abandoned; archived repos marked separately
- Commit history and release history; commits can be browsed on any branch
- Link an installed app for version comparison, with the title following the app name; matching installed apps are recognized automatically
- Version comparison covers common formats (x.y.z, CalVer, pre-release); if a version can't be compared, it says so
- Download → progress → install in one flow, cancellable; optional auto-install after download
- Recommends the right package for your device when multiple are offered; supports apk / apks / xapk / apkm
- Background sync with new-release notifications; can be restricted to Wi-Fi
- Search, sorting, pinning, delete with undo
- Light & dark themes; English / 简体中文
- No account needed; an optional GitHub token raises the rate limit (60 → 5,000 req/h)
- Subscriptions can be exported and merged back in
- Cache cleanup: downloaded packages and crash logs
- In-app updates: check, auto download, silent install

## Building

JDK 17+:

```bash
gradlew assembleDebug
```

## Tech stack

Kotlin · Compose + [Miuix](https://github.com/compose-miuix-ui/miuix) · Navigation 3 · OkHttp · WorkManager · single JSON state file (no Room / no KSP)

## License

[GPL-3.0](LICENSE) · Version comparison and APK selection are informed by [Obtainium](https://github.com/ImranR98/Obtainium) · UI icons from [Lucide](https://lucide.dev) (ISC)
