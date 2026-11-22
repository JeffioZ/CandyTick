<div align="center">

# CandyTick

<p><strong>Follow GitHub repositories on Android, browse commits and releases, and find updates for your installed apps.</strong></p>

<p>Subscribe to public repositories without an account, browse commits by branch,<br>read release notes, and get notified when a new version is published.</p>

<p>
  <a href="https://github.com/JeffioZ/CandyTick/releases/latest"><img alt="Latest release" src="https://img.shields.io/github/v/release/JeffioZ/CandyTick?display_name=tag&sort=semver&style=flat&color=14755B"></a>
  <a href="https://github.com/JeffioZ/CandyTick/releases"><img alt="Total downloads" src="https://img.shields.io/github/downloads/JeffioZ/CandyTick/total?style=flat&label=downloads&color=0F9D7A"></a>
  <img alt="Android 10 or later" src="https://img.shields.io/badge/Android-10%2B%20%28API%2029%2B%29-32674F?style=flat&logo=android&logoColor=white">
  <a href="LICENSE"><img alt="GPL-3.0 license" src="https://img.shields.io/github/license/JeffioZ/CandyTick?style=flat&color=3C6284&label=GPL--3.0"></a>
  <a href="https://github.com/JeffioZ/CandyTick/stargazers"><img alt="Stars" src="https://img.shields.io/github/stars/JeffioZ/CandyTick?style=flat&color=806119"></a>
</p>

<p>
  <a href="https://github.com/JeffioZ/CandyTick/releases/latest"><img alt="Download the latest APK" src="https://img.shields.io/badge/Download-Latest%20APK-14755B?style=for-the-badge&logo=github&logoColor=white"></a>
</p>

<p><a href="README.md">简体中文</a></p>

</div>

> **System requirements:** Android 10 (API 29) or later. The APK covers both 32- and 64-bit ARM, which suits most devices.

## 🏠 Home

<p align="center"><sub>Subscribed repositories appear as cards that keep your reading position across refreshes.<br>
Sort five ways — recent pushes, available updates, activity, and more — each with a reverse order.</sub></p>

<p align="center">
  <a href="docs/fastlane/metadata/android/en-US/images/phoneScreenshots/1.png"><img src="docs/fastlane/metadata/android/en-US/images/phoneScreenshots/1.png" alt="Home screen in light mode showing followed repositories and their activity levels" width="42%"></a>
  &ensp;
  <a href="docs/fastlane/metadata/android/en-US/images/phoneScreenshots/2.png"><img src="docs/fastlane/metadata/android/en-US/images/phoneScreenshots/2.png" alt="The same repository list in dark mode" width="42%"></a>
</p>

## ✨ At a Glance

| | Capability | Built for |
| --- | --- | --- |
| 📬 | **Subscribe** | Paste a link, type a repository name, or search by keyword — no GitHub account needed. |
| 📜 | **Browse activity** | Read commits by branch and Markdown release notes; `#123` links straight to the issue. |
| 📲 | **Update apps** | Matches each repository to its installed app and compares versions; recommends the right package for your device's architecture. |
| 🔔 | **Notifications** | Background sync checks for new packages and notifies you; automatic checks can wait for unmetered networks. |
| 📌 | **Organize** | Pin what you check often, search by name, sort by activity or available updates, and undo a deletion. |
| 💾 | **Backup** | Export subscriptions as JSON and import them on another device; already-followed repositories are skipped. |

**Small by design:** CandyTick needs no account and no cloud service — all data stays on your device. Anonymous requests are limited to 60 per hour; adding a [GitHub token](https://docs.github.com/en/rest/using-the-rest-api/rate-limits-for-the-rest-api) in Settings raises it to 5,000.

## 🚀 Get Started

1. Download the APK from [Releases](https://github.com/JeffioZ/CandyTick/releases/latest) and install it.
2. Tap **+** at the top right of the home screen. Paste a GitHub repository URL (such as `JeffioZ/CandyTick`), enter a name, or search by keyword.
3. Check the repository in the preview and tap **Add repository**.

Want to track updates for an app you already installed? Add its GitHub repository — CandyTick links apps with matching names automatically and pings you when a new version lands.

## 📚 More

| | Read this |
| --- | --- |
| 📝 | [Changelog](CHANGELOG.md) — every change per version, with an English summary |
| 🔨 | [Build from source](#build-from-source) — JDK 21 + Android SDK, one command for a debug APK |

## 🔨 Build from source

Release builds use JDK 21. See the [app build configuration](app/build.gradle.kts) for the required Android SDK platform. To try [unreleased changes](CHANGELOG.md#未发布) on the main branch, build a debug APK from the project root.

```sh
bash gradlew assembleDebug
```

In Windows PowerShell, run `.\gradlew.bat assembleDebug`. The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## 🤝 Credits

CandyTick uses [Miuix](https://github.com/compose-miuix-ui/miuix) components and [Lucide](https://lucide.dev) icons. [Obtainium](https://github.com/ImranR98/Obtainium) inspired how CandyTick compares versions and selects packages.

## 📄 License

[GPL-3.0](LICENSE)
