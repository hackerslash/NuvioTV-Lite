<div align="center">

  <img src="assets/brand/app_logo_wordmark.png" alt="NuvioTV" width="300" />
  <br />
  <br />

  [![Contributors][contributors-shield]][contributors-url]
  [![Forks][forks-shield]][forks-url]
  [![Stargazers][stars-shield]][stars-url]
  [![Issues][issues-shield]][issues-url]
  [![License][license-shield]][license-url]

  <p>
    A modern Android TV media player powered by the Stremio addon ecosystem.
    <br />
    Stremio Addon ecosystem • Android TV optimized • Playback-focused experience
  </p>

</div>

## About

NuvioTV is a modern media player designed specifically for Android TV.

It acts as a client-side playback interface that can integrate with the Stremio addon ecosystem for content discovery and source resolution through user-installed extensions.

Built with Kotlin and optimized for a TV-first viewing experience.

> **NuvioTV Lite is a performance-focused fork** of
> [NuvioMedia/NuvioTV](https://github.com/NuvioMedia/NuvioTV). Alongside the standard
> build it ships a dedicated **Lite Edition** (see below) — tuned for 2 GB Android TV
> boxes — plus memory and startup optimizations that benefit every build.
> In-app updates for the Lite Edition come from this fork's own GitHub Releases.

## ⚡ Lite Edition

A deliberately stripped-down build tuned for the **lowest practical RAM footprint on
2 GB Android TV boxes**, where the OS and launcher already eat most of the memory. It
preserves the core loop — **browse → pick a source → reliable playback** — and trades
away heavier features to stay alive under memory pressure.

**What it removes / tunes** (full detail in [`RELEASE_NOTES_LITE.md`](RELEASE_NOTES_LITE.md)):

- Torrent streaming dropped → **~23 MB smaller APK**, no 41 MB torrent co-process
- Hard 48 MB playback buffer (vs. 50 s stock buffers) — the biggest heap lever
- Plugins/JS runtime, in-app + external trailers, launcher-channel sync + boot receiver, Sentry, custom server connections, and the auto-frame-rate probe all disabled
- Tighter image cache (RGB565, 8 % memory / 100 MB disk), animated-image decode off, bounded metadata caches, no idle animations

See [`docs/LITE_PLAN.md`](docs/LITE_PLAN.md) for the full analysis, per-change memory estimates and risk assessment.

## Installation

### Android TV

Download the latest APK from **[GitHub Releases](https://github.com/hackerslash/NuvioTV-Lite/releases/latest)** and sideload it on your Android TV device.

Pick the APK matching your box's CPU:

- **`arm64-v8a`** — most modern TV boxes / sticks (recommended)
- **`armeabi-v7a`** — older or budget 32-bit boxes
- **`x86_64` / `x86`** — emulators and x86 devices
- **`universal`** — works everywhere but is the largest download

The `-lite` APKs are the Lite Edition; the standard APKs are the full build.

## Development

### Prerequisites

- Android Studio (latest version)
- JDK 17 (CI builds on Temurin 17; newer JDKs are not supported by AGP)
- Android SDK (API 29+)
- Gradle 8.0+

### Setup

```bash
git clone https://github.com/hackerslash/NuvioTV-Lite.git
cd NuvioTV-Lite
```

### Full Debug Build

```bash
./gradlew :app:compileFullDebugKotlin
./gradlew :app:assembleFullDebug
```

### Lite Edition Build

```bash
# per-ABI + universal release APKs
./gradlew :app:assembleLiteRelease
# or an app bundle
./gradlew :app:bundleLiteRelease
```

Flavors: `full` (all features), `playstore` (lean, Play-compliant), `lite`
(memory-optimized 2 GB edition).

### Running on Emulator or Device

```bash
# Full debug build
./gradlew :app:assembleFullDebug

# Run on connected device
adb shell am start -n com.nuviodebug.com/com.nuvio.tv.MainActivity
```

## Legal & DMCA

NuvioTV functions solely as a client-side interface for browsing metadata and playing media provided by user-installed extensions and/or user-provided sources. It is intended for content the user owns or is otherwise authorized to access.

NuvioTV is not affiliated with any third-party extensions or content providers. It does not host, store, or distribute any media content.

For comprehensive legal information, including our full disclaimer, third-party extension policy, and DMCA/Copyright information, please visit our **[Legal & Disclaimer Page](https://nuvioapp.space/legal)**.

## Built With

* Kotlin
* Jetpack Compose & TV Material3
* ExoPlayer / Media3
* Hilt (Dependency Injection)
* Retrofit (Networking)
* Gradle

## Star History

<a href="https://www.star-history.com/?type=date&legend=top-left&repos=hackerslash%2FNuvioTV-Lite">
 <picture>
   <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/chart?repos=hackerslash/NuvioTV-Lite&type=date&theme=dark&legend=top-left" />
   <source media="(prefers-color-scheme: light)" srcset="https://api.star-history.com/chart?repos=hackerslash/NuvioTV-Lite&type=date&legend=top-left" />
   <img alt="Star History Chart" src="https://api.star-history.com/chart?repos=hackerslash/NuvioTV-Lite&type=date&legend=top-left" />
 </picture>
</a>

<!-- MARKDOWN LINKS & IMAGES -->
[contributors-shield]: https://img.shields.io/github/contributors/hackerslash/NuvioTV-Lite.svg?style=for-the-badge
[contributors-url]: https://github.com/hackerslash/NuvioTV-Lite/graphs/contributors
[forks-shield]: https://img.shields.io/github/forks/hackerslash/NuvioTV-Lite.svg?style=for-the-badge
[forks-url]: https://github.com/hackerslash/NuvioTV-Lite/network/members
[stars-shield]: https://img.shields.io/github/stars/hackerslash/NuvioTV-Lite.svg?style=for-the-badge
[stars-url]: https://github.com/hackerslash/NuvioTV-Lite/stargazers
[issues-shield]: https://img.shields.io/github/issues/hackerslash/NuvioTV-Lite.svg?style=for-the-badge
[issues-url]: https://github.com/hackerslash/NuvioTV-Lite/issues
[license-shield]: https://img.shields.io/github/license/hackerslash/NuvioTV-Lite.svg?style=for-the-badge
[license-url]: http://www.gnu.org/licenses/gpl-3.0.en.html
