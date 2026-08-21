# Changelog — NuvioTV Lite Edition

All notable changes to the Lite Edition are documented here. Versions use the
`X.Y.Z-lite` scheme; every release ships torrent-free per-ABI APKs and receives
in-app OTA updates.

Release tags are `v<versionName>` (e.g. `v1.0.0-lite`) and are derived from the
build itself. The in-app updater compares the release tag against the installed
`versionName`, so tags must stay version-shaped and releases must be published as
full releases — the updater ignores prereleases and drafts.

## v1.3.0-lite — 2026-08-21

### Memory ceilings now actually hold
- Performance mode raised the parallel-connection ceiling to 16 and stopped applying the
  chunk-size cap, so a 2GB box could be asked for roughly 2.1GB of download buffers on top
  of its playback buffer. Low-RAM devices now keep the budget-aware caps; boxes with the
  headroom keep the raised ceilings. The clamp is applied where the buffers are actually
  allocated, so a setting saved by an older build is bounded too.
- Subtitle search fanned out one request per installed addon at once, holding every
  response and parsed list in memory together. It now runs at most 3 at a time on low-RAM
  hardware and 8 elsewhere, the same limit stream search already used.
- Artwork lookups for large film collections (a 30-part franchise fired 30 at once) are
  capped the same way.

### Low-RAM hardware is classified correctly
- The memory tier could answer "not low RAM" before it had been read, and a box that
  reported its own size as 0 was treated as high-end. Both now count as low-RAM, so an
  uncertain answer errs toward the smaller budgets instead of the larger ones.
- Physical RAM is read once in one place, with a `/proc/meminfo` fallback for boxes whose
  system service reports nothing.

### Memory cuts follow the hardware, not the edition
- Six limits keyed only on "is this the Lite build" — animated poster decoding, poster
  cache share, decode parallelism, the poster revalidation pass and its per-poster
  watcher, and the 48MB playback buffer — now also apply on any low-RAM device. A standard
  NuvioTV build on a 2GB box finally gets the same treatment Lite has had.
- Nothing changes for Lite. Standard builds between 2 and 2.5GB get a smaller poster cache
  than before; that is deliberate, since those are the boxes being killed for memory.

### Less work in the background
- Release builds ran a frame-timing callback on every frame whose only result was a log
  line nothing reads. It is now debug-only.
- Lite did crash-reporter bookkeeping on every network request despite never starting the
  crash reporter.

### Trakt now points somewhere useful
- The Trakt screen used to say only that the maintainer has no license. It now names the
  missing Trakt API key and links the Sync Bridge web tool at nuvio.wiki/tools#sync-bridge,
  so you can get your Trakt data into Nuvio instead of hitting a dead end.

### Launcher artwork
- Refreshed the Lite Android TV banner.

### Synced with upstream NuvioTV (0.8.7-beta)
- [upstream] Simkl: anime films are recognised as films, so their progress is tracked
  against the film itself instead of being held back for an episode number. @skoruppa
- [upstream] Removing an addon now asks for confirmation first, instead of deleting on the
  first press. @tapframe
- [upstream] Brazilian Portuguese translations updated. @danilopagotto82
- Upstream's new anime-film lookup rescanned the whole Simkl library once per playback
  session and rebuilt the same id set inside that loop. On this edition it is resolved once
  per sync, which matters on a box with a large anime library.

## v1.2.1-lite — 2026-08-21

### Simkl runs on this edition's own keys
- Simkl now runs on the Lite edition's own API credentials, so linking a Simkl
  account works again.
- Trakt stays gated with a clear notice. Will be fixed once api keys are sorted.

### Updates are checked before they install
- Update APKs are verified against the installed signing certificate first, and a
  mismatched download is discarded instead of being handed to the installer.

### Its own identity on the launcher
- Lite carries its own Android TV banner, so it is tellable apart from a standard
  Nuvio install sitting next to it on the launcher home row.

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
