<div align="center">

  <img src="assets/brand/app_logo_wordmark.png" alt="NuvioTV" width="300" />
  <br />
  <br />

  <p>
    NuvioTV, rebuilt to survive on a low-RAM Android TV box.
    <br />
    Same Stremio-addon playback core • capped memory • self-updating from GitHub Releases
  </p>

</div>

## Why this fork exists

Plenty of Android TV sticks and boxes sold today are low-RAM devices, and stock NuvioTV
([NuvioMedia/NuvioTV](https://github.com/NuvioMedia/NuvioTV)) is tuned for devices with
more headroom than that. On a low-RAM box its buffer sizing, unbounded concurrent addon
queries, and heavier features (torrent streaming, the JS plugin runtime, launcher-channel
sync) push it into the kernel's low-memory killer well before anything throws an
`OutOfMemoryError`. This fork exists to fix that class of device, not to add features.

Everything here is upstream's playback core with the memory ceiling actually enforced,
plus a **Lite Edition** build flavor that strips what a low-RAM box can't afford. See the
[Changelog](CHANGELOG.md) for what shipped release-by-release.

## What's actually different from upstream

| | Upstream NuvioTV | This fork |
|---|---|---|
| Device tiering | Heap size (`largeHeap` reports ~512 MB on both a low-RAM and high-RAM device) | Physical RAM (`isLowRamDevice` + a cutoff) |
| Playback buffer on low-RAM | Heap-ratio only, ~435 MB observed on a low-RAM device | Hard 250 MB ceiling |
| Concurrent addon stream queries | Unbounded — every configured addon queried at once | Capped at 3 (low-RAM) / 8 (elsewhere) |
| Torrent streaming, JS plugins, in-app trailers, launcher sync | Present | Stripped in **Lite Edition** only (still present in this fork's `full`/`playstore` flavors) |
| Updates | Play Store / manual APK | In-app OTA updates from this fork's GitHub Releases |
| Settings UI | — | Unchanged — no new toggles, this is all build-flavor and internal tuning |

Day-to-day upstream fixes and features are merged in regularly; this fork isn't a
permanent divergence, just upstream plus the memory work above.

## ⚡ Lite Edition

The build flavor this fork is really about: tuned for the **lowest practical RAM
footprint on low-RAM Android TV boxes**, where the OS and launcher already eat most of the
memory. It preserves the core loop — **browse → pick a source → reliable playback** —
and trades away heavier features to stay alive under memory pressure.

- Torrent streaming dropped → **~23 MB smaller APK**, no 41 MB torrent co-process
- Plugins/JS runtime, in-app + external trailers, launcher-channel sync + boot receiver,
  and Sentry all disabled
- Installs as a separate `com.nuvio.tv.lite` package ("Nuvio Lite") alongside a standard
  NuvioTV install — nothing is overwritten

## Installation

### Android TV

Download the latest APK from **[GitHub Releases](https://github.com/hackerslash/NuvioTV-Lite/releases/latest)** and sideload it on your Android TV device.

Pick the APK matching your box's CPU:

- **`armeabi-v7a`** — start here: most low-RAM TV boxes and sticks are 32-bit
- **`arm64-v8a`** — 64-bit boxes (check your device before picking this)
- **`x86_64` / `x86`** — emulators and x86 devices
- **`universal`** — works everywhere but is the largest download

Only the first install needs a choice; in-app updates detect your device's ABI and
fetch the matching APK automatically.

The `-lite` APKs are the Lite Edition; the standard APKs are the full build.

### Speed up cold start after sideloading

A sideloaded APK is installed at compilation filter `verify` — no AOT compilation — so
cold start is 2-3x slower than the same build from the Play Store until Android's
background dexopt gets around to it (it waits for idle + charging, often a day or more).
Forcing that compilation now is a one-off:

```bash
scripts/adb-optimize.sh com.nuvio.tv.lite
```

It finds the device (USB, or a sweep of the local subnet for adb on 5555), checks the
app hasn't already been compiled, measures the median cold start before and after, and
prints the speedup. Use `com.nuvio.tv` for the full build.

If adb is already connected and you just want the one command:

```bash
adb shell cmd package compile -m speed-profile -f com.nuvio.tv.lite
```

Undo with `adb shell cmd package compile --reset com.nuvio.tv.lite`. Both need
ADB/USB debugging enabled in the TV's Developer options.

## Build from source

```bash
git clone https://github.com/hackerslash/NuvioTV-Lite.git
cd NuvioTV-Lite
./gradlew :app:assembleFullDebug     # full build
./gradlew :app:assembleLiteRelease   # Lite Edition, per-ABI + universal APKs
```

Flavors: `full` (all features), `playstore` (lean, Play-compliant), `lite`
(memory-optimized low-RAM edition).

