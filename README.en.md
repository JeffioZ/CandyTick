<div align="center">

# CandyTick

![Android 10 or later (API 29+)](https://img.shields.io/badge/Android-10%2B%20%28API%2029%2B%29-32674F?logo=android&logoColor=white)
[![GPL-3.0 license](https://img.shields.io/badge/License-GPL--3.0-3C6284)](LICENSE)
[![GitHub stars](https://img.shields.io/github/stars/JeffioZ/CandyTick?style=flat&label=Stars&color=806119)](https://github.com/JeffioZ/CandyTick/stargazers)

Follow GitHub repositories on Android, browse commits and releases, and find updates for your installed apps.

[![Download the latest APK](https://img.shields.io/github/v/release/JeffioZ/CandyTick?style=for-the-badge&label=Download%20APK&logo=github&logoColor=white&color=14755B)](https://github.com/JeffioZ/CandyTick/releases/latest)

32- and 64-bit ARM · [Release notes](https://github.com/JeffioZ/CandyTick/releases) · [简体中文](README.md)

</div>

## Follow your first repository

1. Tap **+** at the top right of the home screen. Paste a GitHub repository URL, enter a name such as `LibChecker/LibChecker`, or search by keyword.
2. Check the repository in the preview and tap **Add repository**. You can follow public repositories without signing in.

<p align="center">
  <a href="docs/fastlane/metadata/android/en-US/images/phoneScreenshots/1.png"><img src="docs/fastlane/metadata/android/en-US/images/phoneScreenshots/1.png" width="36%" alt="Home screen in light mode showing followed repositories and their activity levels"></a>
  &ensp;
  <a href="docs/fastlane/metadata/android/en-US/images/phoneScreenshots/2.png"><img src="docs/fastlane/metadata/android/en-US/images/phoneScreenshots/2.png" width="36%" alt="The same repository list in dark mode"></a>
</p>

## Following repositories

- **Follow development.** Browse commits by branch and read release notes. The home screen shows how active each repository is and whether it’s archived.
- **Organize your list.** Pin repositories you check often, sort by recent activity or available updates, or find a repository by name.
- **Get update notifications.** CandyTick can check repositories periodically and notify you when a new Android package is available. You can limit automatic checks to unmetered networks. You can still refresh manually over mobile data.
- **Take your subscriptions with you.** Export your subscriptions and cached repository details to JSON, then import them on another device. CandyTick skips repositories you already follow.

If you often hit GitHub’s request limit, add a GitHub token in Settings to raise your [API rate limit](https://docs.github.com/en/rest/using-the-rest-api/rate-limits-for-the-rest-api).

## Update an installed app

CandyTick tries to match each repository to an installed app and compares the installed version with the one published on GitHub. You can also link an app manually from the repository details screen.

Choose a package to download from the release history. When a release has several packages, CandyTick recommends a match for your device’s architecture if it can find one. Android’s installer handles standard APKs. APKS, XAPK, and APKM bundles need a compatible third-party installer.

## Build from source

Release builds use JDK 21. See the [app build configuration](app/build.gradle.kts) for the required Android SDK platform. To try [unreleased changes](CHANGELOG.md#未发布) on the main branch, build a debug APK from the project root.

```sh
bash gradlew assembleDebug
```

In Windows PowerShell, run `.\gradlew.bat assembleDebug`. The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Credits

CandyTick uses [Miuix](https://github.com/compose-miuix-ui/miuix) components and [Lucide](https://lucide.dev) icons. [Obtainium](https://github.com/ImranR98/Obtainium) inspired how CandyTick compares versions and selects packages.

## License

CandyTick is licensed under the [GNU General Public License v3.0](LICENSE).
