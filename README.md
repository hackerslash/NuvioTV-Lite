<div align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="assets/brand/lite_logo_dark.png" />
    <img src="assets/brand/lite_logo_light.png" alt="Nuvio Lite" width="221" />
  </picture>
</div>

# NuvioTV Lite

An unofficial fork of [NuvioTV](https://github.com/NuvioMedia/NuvioTV) for Android TV boxes with little RAM. It keeps upstream's Stremio-addon playback core and caps memory use so the low-memory killer leaves the app alone.

## Differences from upstream

- Devices are tiered by physical RAM instead of heap size.
- The playback buffer tops out at 250 MB on low-RAM devices (upstream reached about 435 MB).
- At most 3 addon stream queries run at once on low-RAM devices, 8 on others.
- The Lite build drops JS plugins, trailers, launcher-channel sync and Sentry. P2P runs in-process on the Nuvio Engine (about 4 MB).
- Lite installs as `com.nuvio.tv.lite`, so it sits next to a regular NuvioTV install.
- Updates come in-app from this repo's GitHub Releases.

Upstream changes are merged regularly. See the [changelog](CHANGELOG.md) for each release.

## Install

Download an APK from [Releases](https://github.com/hackerslash/NuvioTV-Lite/releases/latest). The `-lite` APKs are the Lite build. Most low-RAM boxes are 32-bit, so start with `armeabi-v7a`. Use `arm64-v8a` on 64-bit boxes, `x86`/`x86_64` on emulators, or `universal` if unsure. After the first install, in-app updates pick the right ABI.

Sideloaded apps start 2-3x slower until Android compiles them in the background. With ADB debugging on, you can compile right away:

```bash
adb shell cmd package compile -m speed-profile -f com.nuvio.tv.lite
```

`scripts/adb-optimize.sh com.nuvio.tv.lite` does the same, finds the device for you and measures cold start before and after. Undo with `adb shell cmd package compile --reset com.nuvio.tv.lite`.

## Build

```bash
./gradlew :app:assembleFullDebug     # full build
./gradlew :app:assembleLiteRelease   # Lite, per-ABI + universal APKs
```

Flavors: `full`, `playstore` (Play-compliant) and `lite`.
