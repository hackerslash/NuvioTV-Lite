# Changelog — NuvioTV Lite Edition

All notable changes to the Lite Edition are documented here. Versions use the
`X.Y.Z-lite` scheme; every release ships torrent-free per-ABI APKs and receives
in-app OTA updates.

Release tags are `v<versionName>` (e.g. `v1.0.0-lite`) and are derived from the
build itself. The in-app updater compares the release tag against the installed
`versionName`, so tags must stay version-shaped and releases must be published as
full releases — the updater ignores prereleases and drafts.

## v1.4.4-lite — 2026-09-02

### High-bitrate 4K no longer stalls every few minutes
- Lite held the playback buffer to 48 MB on every device, however much memory it had.
  On a high-bitrate 4K file that is roughly five seconds of video — too thin to absorb
  any dip in the connection, so playback ran smoothly, stalled, refilled and stalled
  again. Devices with normal memory now buffer the way the standard NuvioTV build does.
- The 48 MB limit was written for 2GB boxes and still applies there, but it now follows
  the device's real memory budget instead of a flat number, so those boxes hold at least
  15 seconds of video rather than five.

### Synced with upstream NuvioTV (0.8.12-beta)
- Unchanged from v1.4.3-lite. Upstream has had no new commits since that release, so this
  is a Lite-only patch on the same 0.8.12-beta base.

## v1.4.3-lite — 2026-09-01

### Synced with upstream NuvioTV (0.8.12-beta)
- [upstream] The in-app updater has stable and beta channels, picked in About settings. The
  channel starts on the one that matches the installed build, and the download progress
  follows the theme gradient. @tapframe
- [upstream] A profile's whole TV setup can be copied onto another profile, optionally
  including provider credentials. @tapframe
- [upstream] The playback stats HUD no longer opens by itself when playback starts: the
  Advanced setting only makes the button available in stream info, and whether the HUD is
  showing is remembered separately across sessions. Its memory reading now shows the player's
  off-heap use against the calculated buffer target, amber past the safe limit and red past
  the warning limit. @halibiram @ram130
- [upstream] Subtitles carried by the stream itself are offered alongside the addon ones, and
  a track labelled with a full language name ("Portuguese", "Spanish", …) is matched to its
  language rather than shown as a raw label. @halibiram
- [upstream] Translated subtitles no longer come back as mojibake: the double-encoding repair
  is confined to the Hebrew case it was written for instead of firing on any Latin-1-looking
  text. @Rasimsson
- [upstream] mpv no longer sits on the last frame of a finished video. @skoruppa
- [upstream] An HLS rendition whose segments 404 is dropped so playback continues on another
  one, rather than failing the stream. @joaotolovi
- [upstream] Addons: an addon whose manifest cannot be fetched stays visible — and removable —
  in the addon manager instead of vanishing from the list; the manifest cache is honoured on
  the stream path, so querying streams no longer fires an unconditional manifest request per
  addon; a failed refresh no longer re-runs on every addon change; and one repository instance
  now serves the whole app. @ieno
- [upstream] Home: metadata enrichment is retried after a failed fetch instead of being
  written off for the session, enrichment reaches the rows the classic layout actually
  renders, and the failed-enrichment set is bounded. @ieno
- [upstream] Home rows key their cards by item identity, with the focused placeholder card
  lent a positional key so focus survives real data arriving. @Telkaoss
- [upstream] Skip-intro resolves anime ids through Simkl; the ARM service it replaces is gone.
  @skoruppa
- [upstream] A cancelled trailer lookup is no longer cached as "no trailer". @kayochiaradia
- [upstream] An unknown runtime no longer renders as "0m", and the MediaSession placeholder
  item that always threw is gone. @kayochiaradia
- [upstream] Player strings follow the app language rather than the system one, and addon
  certifications are read in the local form when the addon provides one.
  @kayochiaradia @tapframe
- [upstream] The classic home hero backdrop no longer leaks between screens, folders follow
  home in the classic layout, the Edit Profile menu no longer shows through the gradient, and
  the audio-language picker explains its "Original" option. @skoruppa
- [upstream] Translations: Serbian (Latin) added, plus Polish, Vietnamese and other string
  updates. @Alanon202 @skoruppa @blueocean2308 @mmsw91
- Upstream's new update channels parse the version as semver, and every Lite version and tag
  carries a `-lite` suffix — which reads as a prerelease identifier. That marked every Lite
  release a prerelease, so picking the stable channel would have left the device with no
  eligible update, ever. The edition marker is stripped before the version is parsed.
- Upstream's alias table for full language names is missing Portuguese and Spanish, so
  subtitles labelled with either were shown as `PORTUGUESE`/`SPANISH` and never matched a
  pt/es preference. Both added, and the lookup no longer re-sorts the table and recompiles a
  regex on every call — it runs per subtitle and per audio track, and an addon can answer with
  hundreds.
- Upstream's image revalidation now drops the disk copy on any 200 response. A host that sends
  no ETag or Last-Modified can only answer 200, so every stale image from such a host was
  re-downloaded once per cooldown instead of being read from disk. The disk copy is dropped
  only when the request actually carried a validator, and the cooldown map — one entry per
  image for the life of the process — is bounded.
- The Simkl id caches that replaced ARM are capped like the ones beside them, and the redirect
  probe reuses one derived HTTP client instead of building one per lookup.
- The stats HUD reads total RAM from the cached device tier rather than querying
  ActivityManager, which it would otherwise do twice a second while showing.

## v1.4.2-lite — 2026-08-30

### Synced with upstream NuvioTV (0.8.11-beta)
- [upstream] A playback stats overlay: resolution, codecs, bitrates, dropped frames, buffer
  ahead and an average network reading, behind a toggle in Advanced settings and reachable
  from the stream info screen once it is on. The setting stays on the device rather than
  syncing across them. @ram130
- [upstream] The launcher icon and banner can be changed from Appearance settings — five
  alternates beside the original. @tapframe
- [upstream] Signing in accepts a six-character code as well as the QR, so a phone that
  cannot scan the screen still works. @tapframe
- [upstream] Movies get their own post-play threshold: the recommendation card is timed as a
  percentage of the film rather than borrowing the next-episode rules, and it no longer arms
  while an auto-play next episode is already queued. @tapframe @skoruppa
- [upstream] Seasons can be switched from the episode card itself. @haveAnIssue
- [upstream] Player accents follow member gradients. @tapframe
- [upstream] Settings navigation reworked: Back returns to the category rail before leaving
  settings, opening a category lands on its first option, entering a row of options starts on
  the first one, the debrid options enter on a row that can take focus, the rail restores
  focus to the item it was left on, and returning from a category's own screen comes back to
  where settings was. @ieno
- [upstream] Watch-progress sync: the push sync point is kept per profile, advancing it is
  atomic and durable, and it no longer deletes progress that was saved while the push was in
  flight. @ieno
- [upstream] Home focus and scroll survive navigating away and back — continue watching keeps
  both, the grid row restores to a card that is actually visible, the scroll-to-start trigger
  is consumed after use, and focus is no longer lost when placeholders become real data.
  @ieno @skoruppa
- [upstream] Back navigation on home and collection screens: Back walks the row to its first
  card before opening the sidebar, the player OSD closes on one press, the post-play trailer
  keeps Back on the overlay, and a second Back at the top level closes the process.
  @skoruppa @halibiram
- [upstream] The home placeholder shimmer stops once the rows have loaded, and the focus
  marquee comes to rest instead of scrolling forever. @kayochiaradia
- [upstream] Gradient overlays composite offscreen, so a trailer playing behind one no longer
  stutters. @skoruppa
- [upstream] TMDB falls back to English when a detail or collection title comes back in CJK
  script for a language that does not use it. @halibiram
- [upstream] IPTV DASH streams send an Android User-Agent and stop coming back 403.
  @halibiram
- [upstream] Optical AC-3, HTTP/2 and the parallel chunk size stay device-local instead of
  syncing to every device on the account. @halibiram
- [upstream] Season tabs no longer stick after a visit to the ratings section, the fullscreen
  trailer player closes properly, and the addon input field no longer traps input.
  @skoruppa @haveAnIssue
- [upstream] Stripping SDH also strips speaker-change markers. Phantom
- [upstream] Translations: Hebrew added, Greek brought to parity, plus Slovak, Polish,
  Brazilian Portuguese and Vietnamese updates. @haveAnIssue @nosvasedis @mmsw91 @skoruppa
  @blueocean2308 @danilopagotto82
- Upstream's shared row shimmer only stopped once every row in the list had loaded. Its new
  gate — keyed on the rows actually on screen — is taken here instead of this edition's own,
  so the animation stops while rows below the fold are still filling in.
- Relocating the focused home card now compares payload identity instead of the composable
  key, which means building a string for every item of every row. That happens during
  composition rather than in an effect, so it ran on every recomposition of the home screen;
  rows whose items have not changed now reuse the identities from the last pass.
- Upstream's TMDB collection cache grew back into an unbounded map when its value type
  changed. It stays capped at 48 entries here.
- The playback stats overlay costs nothing while it is off: it is composed only when the
  setting is on, and its one-second sampler lives inside it.

## v1.4.1-lite — 2026-08-27

### Synced with upstream NuvioTV (0.8.10-beta)
- [upstream] Post-play recommendations: when playback reaches the end, a full-screen card offers
  the next movie or series with its ratings and artwork. Four candidates can be paged through,
  already-watched titles are excluded, a series card routes to its details, and Back returns to
  the full player. New toggle in Playback settings, on by default. @tapframe @skoruppa @halibiram
- [upstream] The recommendation card no longer arms during a seek preview, keeps its focus while
  playback continues, and restores the detail screen behind it without a flash. @tapframe
  @halibiram
- [upstream] Home catalogs are re-requested on the way back to Home when the last load is over 15
  minutes old, and each result is merged into the row already on screen so a focused row never
  moves under the D-pad. The addon refresh button refreshes the catalogs too. @Telkaoss
- [upstream] The subtitle sync slider reaches 180 seconds. @uHleaf
- [upstream] Simkl no longer treats a null `anime_type` as a non-null string. @skoruppa
- [upstream] Detail screen internals split out: synopsis, the MDBList ratings row and the manual
  play override dialog are their own components now. @tapframe
- [upstream] Translations: Slovak typos and new entries, Polish and Vietnamese strings.
  @mmsw91 @blueocean2308 @skoruppa
- [upstream] Live channels are timed as a watch clock instead of a VOD timeline, the
  next-episode overlay no longer arms on them, and a Media3 buffered-percentage overflow no
  longer crashes live IPTV playback. @halibiram
- [upstream] The selected source is restored after playback instead of resetting. @liongalahad
- [upstream] Subtitles no longer crash on duplicate cue keys, and charset detection is fixed for
  Western European and legacy single-byte subtitles. @halibiram @Rasimsson
- [upstream] Chinese subtitles pick Traditional or Simplified from the language tag rather than
  guessing when the tag says which. @Rasimsson
- [upstream] Playback speed is remembered per show. Phantom
- [upstream] The episode description always updates when switching episodes instead of keeping a
  stale one. @skoruppa
- [upstream] Hiding the parental guide no longer counts as user interaction, so the controls
  don't stay up. @uHleaf
- [upstream] Episode options overlay: appearance options, and less gradient banding.
  @tapframe @skoruppa
- [upstream] Long cast biographies expand from the portrait. @tapframe
- [upstream] TMDB falls back to English/Romaji cast and crew names when it returns native script,
  including filmography and network browse titles. @halibiram
- [upstream] Library: focus returns to the selected mode, search no longer jitters or traps
  focus, catalogs are translated when adding titles, and anime type is respected when adding to
  Simkl. @liongalahad @YLaskco @skoruppa
- [upstream] Search: the discover catalog selection persists, live search no longer wipes the
  keyboard suggestion strip, and the keyboard hides when it should. @tapframe @ieno @uHleaf
- [upstream] Discovery respects landscape posters, and poster corner radius is respected
  everywhere. @skoruppa
- [upstream] The launcher's Continue Watching card opens the right title when the app resumes
  from background, and content language is forwarded from Continue Watching. @skoruppa
- [upstream] Scrolling titles rest and scroll from their own script's side rather than the UI
  locale's, so focusing a card no longer makes the title jump. @haveAnIssue
- [upstream] System font scale is clamped so large-text devices don't break layouts. @uHleaf
- [upstream] Settings pill offsets are absolute, so an RTL locale doesn't mirror them. @uHleaf
- [upstream] 304 and redirect responses are no longer marked no-store, so the HTTP cache works as
  intended. @Telkaoss
- [upstream] Translations updated: Hebrew, Spanish (LatAm), Greek parity, Vietnamese, Portuguese
  QR wording, and missing genre strings. @haveAnIssue @Omavel @nosvasedis @blueocean2308
  Diogo Santos
- Upstream turned RGB565 poster decoding off for image quality; this edition keeps it on for
  low-RAM devices only, where halved poster bytes still matter, and takes the quality win
  everywhere else.
- Upstream's new subtitle mojibake heuristic ran a regex compile and two list allocations per
  dialogue line on this edition's fast path. It now bails before that on any line that can't
  contain the mojibake it looks for.
- Post-play trailers stay off here, as every other in-app trailer does — the card shows its
  artwork instead, and the trailer player is never created.
- Upstream moved the hero runtime parser into a shared `parseRuntimeMinutes` and lost the hoisted
  regexes on the way, recompiling two patterns per call on a path that runs per hero item and per
  card recomposition. They are hoisted again.
- Post-play resolved all four candidates up front — an addon meta fetch, a TMDB id lookup, an
  enrichment call, ratings and a trailer lookup each — all landing while the video pipeline is
  still up. Low-RAM devices now resolve only the card on screen and page the rest in on demand.
  The home refresh above needed nothing: it already goes through this edition's low-RAM catalog
  concurrency limit.

## v1.4.0-lite — 2026-08-25

### Synced with upstream NuvioTV (post-0.8.7-beta)
- [upstream] MP4s with the moov atom at the end now play on the default path instead of
  failing: the session owns its chunks, the playhead and tail chunks survive scatter seeks,
  and prefetch waits for the current chunk before fanning out the next ones. @halibiram
- [upstream] Hosts that answer 429 or 503 are backed off adaptively instead of being
  hammered, and parallel depth recovers on its own once they stop rate-limiting.
- [upstream] Parallel chunk size is hard-capped at 16 MB on low-RAM devices, performance
  mode included.
- [upstream] Classic home: an immersive hero backdrop, less jank moving between rows, focus
  that lands where it should, a normalised backdrop colour space, and no gradient work when
  the gradient is switched off. @tapframe
- [upstream] Episode options overlay shows the episode still behind it, blurred for
  unwatched episodes, and no longer stutters as it opens. @halibiram @skoruppa
- [upstream] Unwatched episode thumbnails blur consistently wherever they appear. @tapframe
- [upstream] Per-episode ratings are read from addon metadata, and addon ratings survive a
  ratings-repository failure instead of vanishing. @6ip
- [upstream] Addon types: `tv` is treated as `series`, and addons that cannot serve the
  requested type are no longer picked. @ieno
- [upstream] Right-to-left fixes: overall layout, focus landing on the first episode in the
  Ratings tab grid, left-to-right subtitle preview lines in the Sync Line dialog, and
  tidied Hebrew strings. @haveAnIssue
- [upstream] Albanian is now available. Rinor Ajeti
- [upstream] Spanish and Spanish (LatAm) translations updated. @Omavel
- [upstream] The player's loading overlay sits where StreamScreen's does. @halibiram
- Upstream's new 16 MB chunk cap and per-session chunk ceiling read this edition's
  physical-RAM tier, so they bind on 2GB boxes that report a full-size heap. Performance
  mode on those devices keeps the budget-aware chunk limit, which is the tighter of the two.
- Lite keeps its own launcher name on Albanian devices.

### Synced with upstream NuvioTV (0.8.8-beta)
- [upstream] Backend requests now honor server backpressure: 429/503 responses are retried
  once with the server's Retry-After delay (idempotent reads only), and a shared coordinator
  serialises the cooldown. @tapframe
- [upstream] Sync traffic cut across the board — unchanged addon/plugin writes are skipped,
  duplicate profile pulls suppressed, avatar-catalog refreshes throttled, terminal progress
  writes deduplicated, provider credentials seeded only when missing remotely, legacy catalog
  reads dropped, and foreground polling reduced. @tapframe
- [upstream] Member assets now restore from cache instantly instead of blanking on open.
  @tapframe
- [upstream] TMDB id lookups are cached per media type, so a movie and a series sharing an
  IMDB id no longer collide (#3057). @skoruppa
- [upstream] Stream-sources panel focus overhauled: chip filters and the stream list share
  one focus model, chips stay mounted while fetching, and focus no longer escapes the modal
  on tab switch or gets trapped on the refresh button. @Rasimsson @skoruppa
- [upstream] Playback and pause state survive a Bluetooth audio-route change. @halibiram
- [upstream] Next Up no longer offers unaired or unavailable episodes. Ramon
- [upstream] Anime films are classified as movies more reliably on the single-addon meta and
  playback paths. @skoruppa @ieno
- [upstream] Trailers follow the TMDB language setting and default audio track.
- [upstream] Assorted fixes: genre metadata no longer overflows the details view (YLaskco),
  the home-hero rating divider shows again (liongalahad), the pause overlay stays hidden
  while adjusting subtitles, and search returns focus to the sidebar (#3095, @skoruppa).
- [upstream] Localization: 467 Arabic strings (Basem), missing Turkish (@halibiram) and
  Polish (@skoruppa) strings, Indonesian no longer mislabelled Melayu, and missing Trakt
  genre labels localized (#2509, supergera13).
- Both TMDB caches kept the fork's 128-entry LRU bound rather than growing unbounded, and
  the Supabase client keeps the fork's non-blocking startup while gaining upstream's retry
  plumbing. The Simkl anime-movie check keeps resolving ids once per snapshot.

## v1.3.0-lite — 2026-08-21

### Memory ceilings now hold
- Performance mode could ask a 2GB box for ~2.1GB of download buffers. Low-RAM devices keep
  the budget caps; roomier boxes keep the raised ceilings.
- Subtitle search no longer fires one request per addon at once: 3 at a time on low-RAM, 8
  elsewhere.
- Artwork lookups for large film collections are capped the same way.

### Low-RAM hardware is classified correctly
- An unread or unreported memory tier used to read as high-end. It now errs low.
- Physical RAM is read in one place, with a /proc/meminfo fallback.

### Memory cuts follow the hardware
- Six limits that keyed on "is this Lite" now apply to any low-RAM device, so a standard
  build on a 2GB box gets them too.
- Nothing changes for Lite. Standard builds on 2-2.5GB get a smaller poster cache.

### Less work in the background
- Release builds no longer run a per-frame callback that only logged.
- Lite no longer does crash-reporter bookkeeping on every request.

### Trakt points somewhere useful
- The notice names the missing Trakt API key and links the Sync Bridge at
  nuvio.wiki/tools#sync-bridge.

### Launcher artwork
- Refreshed the Lite Android TV banner.

### Synced with upstream NuvioTV (0.8.7-beta)
- [upstream] Simkl: anime films are tracked as films, not held back for an episode number.
  @skoruppa
- [upstream] Removing an addon now asks for confirmation. @tapframe
- [upstream] Brazilian Portuguese translations updated. @danilopagotto82
- [upstream] Garbled subtitles are repaired: mojibake is sanitized, legacy codepages are
  detected from the track language, and double-encoded UTF-8 is fixed. @Rasimsson
- [upstream] Addon subtitles now route to a sidecar and are transcoded for mpv and external
  players. @Rasimsson
- Two upstream hot paths trimmed: the anime-film lookup rescanned the whole Simkl library
  per playback session, and subtitle normalisation re-encoded every ASS line. Both avoid
  that work now.

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
