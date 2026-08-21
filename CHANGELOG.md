# Changelog — NuvioTV Lite Edition

All notable changes to the Lite Edition are documented here. Versions use the
`X.Y.Z-lite` scheme; every release ships torrent-free per-ABI APKs and receives
in-app OTA updates.

Release tags are `v<versionName>` (e.g. `v1.0.0-lite`) and are derived from the
build itself. The in-app updater compares the release tag against the installed
`versionName`, so tags must stay version-shaped and releases must be published as
full releases — the updater ignores prereleases and drafts.

## v1.2.0-lite — 2026-08-21

### Auto frame rate is back, as a choice
- The auto-frame-rate section returns to Playback settings. v1.1.1 hid it because the
  probe was stripped from the build; the probe now ships and is simply off by default.
- Leaving it off costs nothing. Turning it on runs a native FFmpeg probe before playback
  starts, so enable it only if your box has the headroom.

### Synced with upstream NuvioTV (0.8.7-beta)
- Subtitle delay overlay: fixed D-pad navigation and the delay sign reordering on
  right-to-left layouts.
- Hungarian translations brought back to parity with the base strings.
- Upstream's Continue Watching launcher deeplinks landed but do not apply to Lite, which
  does not ship launcher-channel sync.

## v1.1.1-lite — 2026-08-20

### Settings match what the edition ships
- The P2P, crash-report, trailer and auto-frame-rate toggles no longer appear in Lite.
  None of those features are compiled into this build, so the switches did nothing.
- Picking a P2P/torrent stream now says up front that it is unavailable, instead of
  opening the player and failing part-way through the loading overlay.

## v1.1.0-lite — 2026-08-20

### Leaner image pipeline
- Lite skips the new stale-while-revalidate poster cache: no background revalidation
  network traffic and no growing per-URL tracking maps on low-RAM boxes. Posters still
  refresh on normal cache expiry.
- Each visible poster no longer parks a background watcher coroutine that Lite never uses.

### Synced with upstream NuvioTV
- Redesigned episode options overlay on the details screen.
- Steadier subtitles: hardened SDH filtering aligned with the MPV player.
- Bluetooth audio: playback keeps running across headphone/route changes, with audio delay
  applied in place.
- The library now remembers your Movies/Shows type selection.
- Configurable rating visibility, plus new theme and profile personalization options.
- Sharper profile background gradients (fixed banding on full-screen backgrounds).
- Assorted profile, playback, and settings refinements.

## v1.0.2-lite — 2026-08-19

### Faster, smoother home
- Catalog rows are cached in memory with in-flight request de-duplication, so returning
  to the home screen no longer re-fetches every row and concurrent identical requests
  collapse to a single call.
- Stable list keys across the home, detail, and folder grids, so rows keep their position
  and focus when data refreshes instead of remounting.
- Removed per-frame recomposition on the comments, library, and detail screens (remembered
  item callbacks; `derivedStateOf` for scroll-driven reads).
- The hero logo decodes at its on-screen size rather than full resolution, and no longer
  re-decodes while it animates.

### Synced with upstream NuvioTV
- Embedded subtitle styling, and fixes for overlapping/merged subtitle cues.
- Cloud library: playback progress preserved, and playback routing + auto-next restored.
- Continue Watching: correct "aired" state when reusing enrichment overlays; poster art
  kept when episode thumbnails are off.
- Streams: a source "refresh" action, steadier result focus, and no result flashing on
  return from the player.
- Turkish translations completed, plus assorted player stability fixes.

## v1.0.1-lite — 2026-08-17

### Faster cold start
- Startup no longer validates the saved session against the server before drawing
  anything. The app now renders immediately from the saved session and validates in the
  background, signing you out only if the session has actually expired. Previously every
  screen waited on that network round trip, stalling launch on a slow or unreachable
  connection.

## v1.0.0-lite — 2026-08-17

First published release. A build of NuvioTV tuned for the lowest practical RAM
footprint on 2 GB Android TV hardware, where the OS and launcher already claim a
large share of memory. It preserves the core experience — browse content, pick a
source, reliable playback — and drops heavier features to stay alive under memory
pressure.

Torrent streaming (the 41 MB `libtorrserver.so`), the JS addon runtime, in-app
trailers, auto-frame-rate probing, and launcher-channel sync are all removed.
Crash reporting is off. In-app OTA updates are kept, so this build updates itself
from GitHub Releases.

### Installs alongside the standard build
- Package is now `com.nuvio.tv.lite` (was `com.nuvio.tv`) and the app is named
  **Nuvio Lite**, so it installs next to a standard NuvioTV install instead of
  replacing it. Both can be kept and compared on the same device. All provider
  authorities derive from the package, so nothing collides.
- Because the package changed, this does not upgrade an earlier side-loaded Lite
  build — it installs as a separate app. Remove the old one manually if you don't
  want both. Updates from this release onward install over themselves normally.
- Known consequence: with both apps installed, both register the `nuvio://` link
  scheme, so Android may ask which app to use when returning from a provider login.

### Lower memory ceilings
- Device memory tier is now decided by physical RAM (`ActivityManager.isLowRamDevice`
  plus a 2.5 GB cut) instead of heap size. `largeHeap` reports the same ~512 MB heap
  on a 2 GB box as on an 8 GB one, so heap size could not tell them apart and a 2 GB
  device was being treated as high-RAM.
- Low-RAM devices get a hard 250 MB ceiling on combined playback buffer and parallel
  chunk allocations once the tuned buffer path is in use (performance mode or custom
  buffers). Previously the heap ratio alone allowed roughly 435 MB on a 2 GB device,
  which the kernel low-memory killer reclaims before any `OutOfMemoryError` is thrown.
  The stock buffer path was already conservative at 100 MB with no back buffer.
- Image cache tiering and the playback budget now read the same device tier, so they
  can no longer disagree about what class of device they are running on.

### Fewer simultaneous source requests
- Addon stream lookups are now bounded (3 concurrent on low-RAM devices, 8 elsewhere).
  Previously every configured stream addon was queried at once with no limit, holding
  every response body and parsed stream list in memory simultaneously — the cause of
  crashes on 2 GB devices for users with many sources installed.

### Faster startup (main thread unblocked)
- Removed the `runBlocking` that built the Supabase/Ktor client on the main thread
  at launch (the builder is synchronous; the wrapper only hopped threads).
- Removed the blocking DataStore read in Sentry init; the existing collector already
  delivers the first value off the main thread.
- Dropped the redundant eager `StartupSyncService` field from the Application so its
  19-dependency graph no longer builds in the earliest, most latency-sensitive window.

### Snappier UI
- Navigation transitions cut from 350 ms to 180 ms across every screen enter/exit —
  the whole app feels more responsive on each open and back.
- Live-search debounce reduced from 350 ms to 250 ms so results settle sooner.
- Home startup safety timeout cut from 5 s to 2.5 s so a slow/clean-cache launch
  shows available content sooner instead of holding a full-screen spinner.
- Loading placeholders and skeletons hold a static gradient instead of animating.
  They previously ran a frame callback for the whole duration of a catalog load,
  competing with that load for a low-end CPU.

### Less memory
- Hardware bitmaps are disabled so RGB_565 actually takes effect, roughly halving
  poster-cache bytes (hardware bitmaps are always RGBA_8888).
- Continue-Watching cards build their overlay gradient once per size instead of every
  frame during scroll (`drawWithCache`).
- Poster/folder cards remember their scrim gradient brushes instead of reallocating
  them on every focus change.

### Fixed
- Subtitle auto-sync downloads no longer fail to build against OkHttp 4, where
  `Response.body` is nullable. A null body is now treated as empty content.
- Release-info year parsing no longer recompiles its regex for every catalog item on
  each home load; six call sites share two cached patterns.
