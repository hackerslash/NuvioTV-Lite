# Changelog — NuvioTV Lite Edition

All notable changes to the Lite Edition are documented here. Versions use the
`X.Y.Z-lite` scheme; every release ships torrent-free per-ABI APKs and receives
in-app OTA updates.

## v1.1.0-lite

Performance release — no new features, no behaviour changes to browsing or playback.

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

### Less memory
- Lite edition now disables hardware bitmaps so RGB_565 actually takes effect,
  roughly halving poster-cache bytes (hardware bitmaps are always RGBA_8888).
- Continue-Watching cards build their overlay gradient once per size instead of every
  frame during scroll (`drawWithCache`).
- Poster/folder cards remember their scrim gradient brushes instead of reallocating
  them on every focus change.

## v1.0.0-lite

Initial public release of the Lite Edition — a build of NuvioTV tuned for the
lowest practical RAM footprint on 2 GB Android TV hardware. Torrent streaming, the
JS addon runtime, in-app trailers, AFR probing, and
launcher-channel sync are removed; playback buffers and image caches are tightened.
In-app OTA updates are kept. See `RELEASE_NOTES_LITE.md` for the full breakdown.
