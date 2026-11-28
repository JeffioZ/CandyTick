<div align="center">

# CandyTick

<p><strong>Subscribe to GitHub repositories on Android, browse commits and releases, and find updates for your installed apps.</strong></p>

<p>Subscribe to public repositories without an account, browse commits by branch,<br>read release notes, and get notified when a new version is published.</p>

<p>
  <a href="https://github.com/JeffioZ/CandyTick/releases/latest"><img alt="Latest release" src="https://img.shields.io/github/v/release/JeffioZ/CandyTick?display_name=tag&sort=semver&style=flat&color=0E805E"></a>
  <a href="https://github.com/JeffioZ/CandyTick/releases"><img alt="Total downloads" src="https://img.shields.io/github/downloads/JeffioZ/CandyTick/total?style=flat&label=downloads&color=16A075"></a>
  <img alt="Android 10 or later" src="https://img.shields.io/badge/Android-10%2B%20%28API%2029%2B%29-0E805E?style=flat&logo=android&logoColor=white">
  <a href="LICENSE"><img alt="GPL-3.0 license" src="https://img.shields.io/github/license/JeffioZ/CandyTick?style=flat&color=3C6284&label=GPL--3.0"></a>
  <a href="https://github.com/JeffioZ/CandyTick/stargazers"><img alt="Stars" src="https://img.shields.io/github/stars/JeffioZ/CandyTick?style=flat&color=806119"></a>
</p>

<p>
  <a href="https://github.com/JeffioZ/CandyTick/releases/latest"><img alt="Download the latest APK" src="https://img.shields.io/badge/Download-Latest%20APK-16A075?style=for-the-badge&logo=github&logoColor=white"></a>
</p>

<p><a href="README.md">简体中文</a></p>

</div>

> **System requirements:** Android 10 (API 29) or later. Universal ~2 MB ARM APK compatible with most Android phones and tablets.

## 🏠 Home

<p align="center"><sub>Subscribed repositories appear as cards that keep your reading position steady across refreshes.<br>
Sort five ways — recent pushes, available updates, activity, and more — each reversible.</sub></p>

<p align="center">
  <a href="docs/fastlane/metadata/android/en-US/images/phoneScreenshots/1.png"><img src="docs/fastlane/metadata/android/en-US/images/phoneScreenshots/1.png" alt="Home screen in light mode showing subscribed repositories and their activity levels" width="42%"></a>
  &ensp;
  <a href="docs/fastlane/metadata/android/en-US/images/phoneScreenshots/2.png"><img src="docs/fastlane/metadata/android/en-US/images/phoneScreenshots/2.png" alt="The same repository list in dark mode" width="42%"></a>
</p>

## ✨ At a Glance

<table>
  <thead>
    <tr>
      <th width="175" align="left">Capability</th>
      <th align="left">Details</th>
    </tr>
  </thead>
  <tbody>
    <tr>
      <td>📬&nbsp;<b>Subscribe</b></td>
      <td>Paste a link, type a repository name, or search by keyword — no GitHub account needed.</td>
    </tr>
    <tr>
      <td>📜&nbsp;<b>Browse activity</b></td>
      <td>Read commits by branch and Markdown release notes; <code>#123</code> links straight to the issue.</td>
    </tr>
    <tr>
      <td>📲&nbsp;<b>Update apps</b></td>
      <td>Matches each repository to its installed app, compares versions, and recommends the right APK for your device; includes built-in self-updates too.</td>
    </tr>
    <tr>
      <td>🔔&nbsp;<b>Notifications</b></td>
      <td>Background sync notifies you when new packages land, with an option to pause auto-sync on mobile data.</td>
    </tr>
    <tr>
      <td>📌&nbsp;<b>Organize</b></td>
      <td>Pin favorites, search by name, sort five ways, and undo accidental deletions.</td>
    </tr>
    <tr>
      <td>💾&nbsp;<b>Backup</b></td>
      <td>Save subscriptions to a local JSON file, copy to clipboard, or share; importing automatically skips duplicates.</td>
    </tr>
    <tr>
      <td>🎨&nbsp;<b>Appearance</b></td>
      <td>Light, dark, or system theme, three switchable launcher icons, and English / Chinese support.</td>
    </tr>
  </tbody>
</table>

The CandyTick APK is about 2 MB. You can use it without an account, and it stores your subscriptions on your phone. The GitHub API allows up to 60 anonymous requests per hour. Adding a [GitHub token](https://docs.github.com/en/rest/using-the-rest-api/rate-limits-for-the-rest-api) in Settings typically raises that limit to 5,000 per hour.

When checking for app updates, CandyTick sends the package names of the apps it checks to the online stores you enable. Some stores also receive details such as the installed app version and your phone model. For F-Droid and IzzyOnDroid, CandyTick downloads the app catalog and compares versions on your phone.

When you use online translation in a public or private repository, CandyTick sends the text to Google Translate. If Google Translate is unavailable, CandyTick may use MyMemory instead.

## 🚀 Get Started

1. Download the APK from [Releases](https://github.com/JeffioZ/CandyTick/releases/latest) and install it.
2. Tap **+** at the top right of the home screen. Paste a GitHub repository URL (such as `JeffioZ/CandyTick`), enter a name, or search by keyword.
3. Check the repository in the preview and tap **Add repository**.

Want updates for a specific app? Add its GitHub repository — CandyTick links apps with matching names and lets you know when a new version lands.

## 📚 More

- 📝 [Changelog](CHANGELOG.md) — every change per version, with an English summary
- 🔨 [Build from source](#build-from-source) — JDK 21 + Android SDK, one command to build a debug APK

## 🔨 Build from source

Release builds use JDK 21. See the [app build configuration](app/build.gradle.kts) for the required Android SDK platform. To try the latest changes on `main`, build a debug APK from the project root:

```sh
bash gradlew assembleDebug
```

In Windows PowerShell, run `.\gradlew.bat assembleDebug`. The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## 🤝 Credits

CandyTick uses [Miuix](https://github.com/compose-miuix-ui/miuix) components and [Phosphor](https://phosphoricons.com) icons. [Obtainium](https://github.com/ImranR98/Obtainium) inspired how CandyTick compares versions and selects packages.

## 📄 License

[GPL-3.0](LICENSE)
