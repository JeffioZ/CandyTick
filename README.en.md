[简体中文](README.md) | English

<div align="center">

# CandyTick

**Still ticking?** — A vital-signs monitor for GitHub repositories

[![License](https://img.shields.io/badge/License-GPL--3.0-blue)](LICENSE)
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
- Commit history and release history, paginated in the detail view
- Optional link to an installed app for version comparison; only standard version formats are compared (x.y.z / x.y / digits-only) — otherwise it says so instead of guessing
- Multi-APK releases: recommended by device ABI, manually selectable; downloaded APKs are kept, so a failed install does not require re-downloading
- Periodic background sync with new-release notifications; sync can be restricted to Wi-Fi or charging
- Light and dark themes: follow system or set manually
- Works anonymously; an optional GitHub token raises the rate limit (60/h → 5000/h)
- List search, sorting, swipe-to-delete; subscriptions can be exported and merged back in
- In-app updates: manual check, auto check, Wi-Fi-only auto download, and silent install on Android 12+ when CandyTick is the install source

## Building

JDK 17+:

```bash
gradlew assembleDebug
```

## Tech stack

Kotlin · Compose + [Miuix](https://github.com/compose-miuix-ui/miuix) · Navigation 3 · OkHttp · WorkManager · single JSON state file (no Room / no KSP)

## License

[GPL-3.0](LICENSE) · Version comparison and APK selection are informed by [Obtainium](https://github.com/ImranR98/Obtainium)
