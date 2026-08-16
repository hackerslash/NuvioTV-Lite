# NuvioTV — Low-RAM Edition (0.8.4-beta-lowram)

A deliberately stripped-down build of NuvioTV tuned for the **lowest practical RAM
footprint on 2 GB Android TV hardware**, where the OS and launcher already eat a large
share of memory. It preserves the core experience — **browse content → pick a source →
reliable playback** — and trades away heavier features to stay alive under memory
pressure.

Build it with the new `lowram` product flavor:

```
./gradlew :app:assembleLowramRelease      # split APKs per ABI (+ universal)
./gradlew :app:bundleLowramRelease        # AAB
```

Application ID and versionCode are unchanged from the base build; the version name is
suffixed `-lowram` so the edition is identifiable.

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
- **In-app updater removed**; update manually from GitHub Releases.
- **Auto-frame-rate (AFR) matching disabled** — skips the native MediaInfo probe. 24p
  content may show mild judder on displays that don't already match.
- **Dolby Vision Profile 7 → HDR10.** DV7 content plays as its HDR10 base layer instead
  of running the off-heap libdovi conversion (the conversion buffers stack on top of the
  video buffer and were a leading cause of the low-memory-killer on 2 GB boxes).
- **Android TV launcher-channel sync removed**, including the `BOOT_COMPLETED` receiver —
  the app no longer spins up a process at device boot or runs a 15-minute background job.
- **Crash reporting (Sentry) disabled** — removes the SDK footprint and a blocking
  startup read.

### Degraded / tightened (still works, uses less)

- **Playback buffer capped hard at ~48 MB** (20 s max / 5 s back buffer) out of the box.
  Expect slightly more rebuffering on slow connections in exchange for a much smaller
  playback heap.
- **Image memory cache reduced** to 8% of app RAM (from 10%), disk cache 100 MB (from
  200 MB), decode parallelism halved, and **animated-image decoding disabled** (animated
  posters render their first frame) — animated art was the one uncapped bitmap allocation.

### Kept

AV1 and MPEG-H decoders, the FFmpeg audio decoder (TrueHD/DTS fallback + Bluetooth
downmix), libass styled subtitles, and the full browse/detail/source-selection UI.

---

## Under-the-hood improvements (benefit all builds, not just low-RAM)

- Bounded (LRU) all previously-unbounded metadata caches (TMDB enrichment/person/rail/
  collection, IMDb episode ratings, parental guide, skip-intro, MDBList, Trakt-related) —
  they grew for the whole session before.
- Home screen no longer runs a placeholder-shimmer animation forever — it stops once rows
  finish loading, so the frame pipeline can idle.
- Fixed a slow session-long leak of per-row `FocusRequester` objects on the home screen.
- Removed a per-recomposition debug log and a per-recomposition allocation on the detail
  and home screens.

## Diagnostics

A lightweight memory logger (tag `NuvioMem`) is enabled in this edition. It logs a PSS
breakdown (total / dalvik / native / graphics), Java heap and system-available memory at
app start, on every `onTrimMemory`, and at player init — so the footprint can be measured
on a real 2 GB device with just `adb logcat -s NuvioMem`.

---

*See `docs/LOW_RAM_PLAN.md` for the full analysis, per-change memory-impact estimates,
and risk assessment.*
