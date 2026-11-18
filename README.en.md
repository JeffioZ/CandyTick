[简体中文](README.md) | English

<div align="center">

# CandyTick

**Still ticking?** — A vital-signs monitor for GitHub repositories

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

- Maintenance status based on the latest commit on the default branch: Active / Fair / Low activity / Likely abandoned; archived repositories marked separately
- Commit history and release history, paginated in the detail view; the commit view can browse any branch
- Optional link to an installed app for version comparison; the title follows the app name (and tracks renames); high-confidence auto-linking by app label or package name
- Version comparison covers real-world formats: x.y.z, CalVer + git hash (26.11.caccb78), pre-releases (2.2.2-rc0), and more — when a version can't be compared, it says so instead of guessing
- One-tap update download in the detail view: download (cancellable) → progress → install; auto-install after download is optional
- Multi-APK releases: recommended by device ABI, manually selectable; package formats include apk / apks / xapk / apkm; downloads are kept so a failed install does not require re-downloading
- Periodic background sync with new-release notifications; sync can be restricted to Wi-Fi or charging
- List: search, sorting, swipe-to-pin, swipe-to-delete (with particle dissolve and undo); update badges
- Light and dark themes: follow system or set manually; English / 简体中文 in-app
- Works anonymously; an optional GitHub token raises the rate limit (60/h → 5,000/h)
- Subscriptions can be exported and merged back in (including linked apps and pins)
- Cache cleanup: downloaded packages and crash logs, with sizes per category and one-tap clearing
- In-app updates: manual check, auto check, Wi-Fi-only auto download, and silent install on Android 12+ when CandyTick is the install source

## Building

JDK 17+:

```bash
gradlew assembleDebug
```

## Tech stack

Kotlin · Compose + [Miuix](https://github.com/compose-miuix-ui/miuix) · Navigation 3 · OkHttp · WorkManager · single JSON state file (no Room / no KSP)

## License

[GPL-3.0](LICENSE) · Version comparison and APK selection are informed by [Obtainium](https://github.com/ImranR98/Obtainium) · UI icon set from [Lucide](https://lucide.dev) (ISC)
