# NuvioTV — Lite Edition (v1.0.2-lite)

## What's new in 1.0.2

- **Faster, smoother home browsing.** Catalog rows are cached in memory with in-flight
  request de-duplication, so returning to the home screen no longer re-fetches every row
  and duplicate requests are collapsed. List keys are now stable, so rows keep their
  place and focus when data refreshes instead of jumping.
- **Less UI stutter.** Removed per-frame recomposition on the comments, library, and
  detail screens, and the hero logo now decodes at display size — lower memory and no
  re-decode while it animates.
- **Synced with upstream NuvioTV**, bringing (where they apply to Lite): embedded
  subtitle styling and fixes for overlapping subtitle cues; Cloud library progress
  preserved and playback routing + auto-next restored; continue-watching fixes (correct
  "aired" state, poster art kept when episode thumbnails are off); a stream-source
  "refresh" action with steadier result focus; Turkish translations; and assorted player
  stability fixes.
- **Image memory:** hardware bitmaps are now off across the board so RGB565 halves poster
  bytes; the Lite cache stays at its conservative 8% of app RAM.

Upgrading from 1.0.0 / 1.0.1 is in-app; no sideloading needed.

---

A deliberately stripped-down build of NuvioTV tuned for the **lowest practical RAM
footprint on 2 GB Android TV hardware**, where the OS and launcher already eat a large
share of memory. It preserves the core experience — **browse content → pick a source →
reliable playback** — and trades away heavier features to stay alive under memory
pressure.

**Installs alongside the standard build.** This edition ships as `com.nuvio.tv.lite`
under the name **Nuvio Lite**, so you can keep it next to a regular NuvioTV install and
compare them. Note it therefore won't upgrade an older side-loaded Lite build — that one
was `com.nuvio.tv` and stays as a separate app until you remove it.

**Download:** grab the APK matching your box's CPU. Most 2 GB TV boxes and sticks are
32-bit, so **start with `armeabi-v7a`**; pick `arm64-v8a` only if you know your box is
64-bit. `universal` works on any device but is the largest download. You only choose
once — in-app updates detect your ABI and fetch the right APK automatically.

Build it yourself with the `lite` product flavor:

```
./gradlew :app:assembleLiteRelease      # split APKs per ABI (+ universal)
./gradlew :app:bundleLiteRelease        # AAB
```

**Measured vs. the standard build (armeabi-v7a):** APK **~40 MB, down from ~63 MB**
(`libtorrserver.so` removed); universal **~102 MB, down from ~200 MB**.

---

## What's different from the standard build

### Removed / disabled features (intentional)

- **Torrent streaming is removed.** The 41 MB `libtorrserver.so` co-process binary is
  stripped from the APK, and torrent sources fail fast with a clear message. HTTP and
  debrid sources are unaffected. *(≈41 MB smaller APK per ABI; no torrent co-process
  competing for system RAM.)*
- **Plugin / JS addon runtime removed.** No QuickJS, Cloudstream, jsoup, or jackson —
  the entire scripted-extension engine is compiled out (inherited from the lean
  `playstore` base). Stremio-style HTTP addons still work.
- **In-app trailers removed** (no second, always-alive ExoPlayer while browsing).
- **In-app OTA updates kept** — checks this repo's GitHub Releases for the newest full
  release, compares its `v<version>-lite` tag against your installed version, and
  downloads the APK matching your CPU. Future updates arrive in-app; no sideloading.
- **Auto-frame-rate (AFR) matching disabled** — skips the native MediaInfo probe. 24p
  content may show mild judder on displays that don't already match.
- **Dolby Vision handling is unchanged from the full build.** DV7 conversion follows your
  player setting, exactly as on `full`. Note the off-heap libdovi path is only compiled in
  when `DOVI_NATIVE_ENABLED` is set at build time; the published APKs are built without it,
  so DV7 falls back to its HDR10 base layer on every flavor.
- **Android TV launcher-channel sync removed**, including the `BOOT_COMPLETED` receiver —
  the app no longer spins up a process at device boot or runs a 15-minute background job.
- **Crash reporting (Sentry) disabled** — removes the SDK footprint and a blocking
  startup read.

### Degraded / tightened (still works, uses less)

- **Playback buffer is 100 MB with no back buffer** out of the box (70 s of forward
  buffer). If you turn on performance mode or custom buffers, the target is capped by
  device RAM tier — 250 MB on a 2 GB box — so a tuned setup can't out-allocate the
  device. Expect slightly more rebuffering on slow connections in exchange for a much
  smaller playback heap.
- **Addon stream lookups are bounded** to 3 at a time on low-RAM devices (8 elsewhere)
  rather than querying every installed source at once. Sources appear progressively
  instead of all at the end, and a long addon list no longer risks the process being
  killed mid-search.
- **Image memory cache reduced** to 8% of app RAM (the standard build scales 15–25% by
  RAM tier), disk cache 100 MB (from 200 MB), decode parallelism halved, and
  **animated-image decoding disabled** (animated posters render their first frame) —
  animated art was the one uncapped bitmap allocation.

### Kept

AV1 and MPEG-H decoders, the FFmpeg audio decoder (TrueHD/DTS fallback + Bluetooth
downmix), libass styled subtitles, and the full browse/detail/source-selection UI.

---

## Under-the-hood improvements (benefit all builds, not just low-RAM)

- Bounded (LRU) all previously-unbounded metadata caches (TMDB enrichment/person/rail/
  collection, IMDb episode ratings, parental guide, skip-intro, MDBList, Trakt-related) —
  they grew for the whole session before.
- Home screen no longer runs a placeholder-shimmer animation forever — it stops once rows
  finish loading, so the frame pipeline can idle. In this edition placeholders and
  skeletons stay static, so nothing animates while catalogs are loading.
- Device memory tier is now read from physical RAM rather than heap size, so a 2 GB box
  is no longer mistaken for a high-RAM device (`largeHeap` makes both report the same
  ~512 MB heap). Image caches and playback budgets share that one tier.
- Fixed a slow session-long leak of per-row `FocusRequester` objects on the home screen.
- Removed a per-recomposition debug log and a per-recomposition allocation on the detail
  and home screens.

## Diagnostics

A lightweight memory logger (tag `NuvioMem`) is enabled in this edition. It logs a PSS
breakdown (total / dalvik / native / graphics), Java heap and system-available memory at
app start, on every `onTrimMemory`, and at player init — so the footprint can be measured
on a real 2 GB device with just `adb logcat -s NuvioMem`.

---

*See `docs/LITE_PLAN.md` for the full analysis, per-change memory-impact estimates,
and risk assessment.*
