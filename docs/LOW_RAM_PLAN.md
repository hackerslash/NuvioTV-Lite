# NuvioTV Low-RAM Edition — Analysis & Implementation Plan

Target: Android TV boxes with **2 GB total RAM**, where the OS + launcher already
consume a large share of the budget. Goal: lowest practical real-world memory
footprint while preserving the **core loop — browse content → pick a source →
reliable playback**. Everything else is negotiable.

> Memory numbers below are **reasoned estimates from the code**, not device
> measurements (no 2 GB device was available during analysis). Treat them as
> relative priorities, and confirm with the diagnostics added in Phase 4.

---

## 0. Baseline: what the app is today

- 628 Kotlin files, ~192k LOC. Mature, and **already well-optimized** in several
  areas: Compose recomposition (stability config, `@Immutable` models, `StableList`,
  `derivedStateOf`), Coil (RGB565 + hardware bitmaps + sized requests + 10% cache
  tier on ≤2 GB), player lifecycle (leak-clean teardown), baseline profile present.
- Two flavors already exist:
  - **`full`** — plugins, in-app updates, in-app trailers, keep-alive, custom servers.
  - **`playstore`** — all of the above **disabled**; the JS/DEX plugin runtime
    (QuickJS, Cloudstream, jsoup, jackson, conscrypt) is **compiled out entirely**
    (`app/build.gradle.kts:162-171`, verified by the no-op stubs in
    `app/src/playstore/.../plugin/PluginManager.kt`).
- Only bundled native lib: **`libtorrserver.so` = 41.7 MB × 4 ABIs**
  (`app/src/main/jniLibs`). Decoder `.so`s ride inside AARs.
- Native DoVi build is **off by default** (`DOVI_NATIVE_ENABLED` unset).

**Strategic conclusion:** the Low-RAM edition is `playstore`-lean + native-payload
cuts + a set of `src/main` runtime tweaks that help *every* flavor.

### Chosen path (confirmed with maintainer)

- **Packaging:** new dedicated **`lowram`** product flavor, reusing the `playstore`
  stub source set (plugins/updates/trailers/keep-alive off) + extra native cuts +
  aggressive defaults. `full` and `playstore` untouched.
- **Feature cuts:** torrent streaming (drop `libtorrserver.so`), MediaInfo/auto-frame-rate
  probe, Dolby Vision → HDR10 base layer. **AV1 + MPEG-H decoders are kept.**
- **Buffers:** aggressive (native limit ~80–90 MB, ~20 s max, ~5 s back buffer).

> Note on MediaInfo: the **14 MB APK** cut requires a flavor source-split that hard
> references must be moved out of `src/main`, which needs a compiler to verify. As a
> safe first step the AFR probe is **disabled at runtime** on `lowram` (the RAM/CPU
> win), and the dependency-level APK cut is left as a verified-in-CI follow-up.
> Torrent's `.so` cut is safe now because `libtorrserver.so` is loaded via
> `ProcessBuilder` (no compile-time symbol), so excluding it does not break the build.

---

## 1. Prioritized plan

Legend — **Impact**: est. RAM (or APK/startup) saved. **Risk**: to the core loop.
Changes marked **[main]** live in `src/main` and help all flavors; **[flavor]**
is a build-config / packaging change; **[product]** removes a user-facing feature.

### Tier 1 — Highest impact, low risk (do first)

| # | Change | Where | Est. impact | Risk |
|---|--------|-------|-------------|------|
| 1 | **Trim ExoPlayer buffer budget** on the 2 GB tier: native limit 250 MB → ~90 MB, max buffer 45 s → ~20 s, back buffer 15 s → ~5 s; reconsider `prioritizeTimeOverSizeThresholds` (it lets high-bitrate files blow past the byte cap). | `NuvioExoPlayerPerformanceHelper.kt:227,257-272`, `BitrateAwareLoadControl.kt:69-77`, `PlayerSettingsDataStore.kt:163-171` | **60–160 MB peak heap during playback** — the single biggest consumer | Low–Med (more rebuffering on slow networks) |
| 2 | **Cap the 8 unbounded TMDB caches** (`enrichmentCache`, `episodeCache`, `personCache`, `moreLikeThisCache`, `entityHeaderCache`, `entityRailCache`, `entityBrowseCache`, `collectionCache`) with the existing `createLruCacheMap` LRU (cap 32–64). | `core/tmdb/TmdbMetadataService.kt:54-62`, pattern from `MetaRepositoryImpl.kt:51-57` | **Several MB, monotonic** (grows all session) — both flavors | Low |
| 3 | **Cap the enrichment side-caches** the same way. | `ParentalGuideRepository.kt:26`, `ImdbEpisodeRatingsRepository.kt:28`, `SkipIntroRepository.kt:35-37`, `MDBListRepository.kt:50`, `TraktRelatedService.kt:44`, `TraktEpisodeMapping.kt:92` | **Low-single-digit MB, monotonic** — both flavors | Low |
| 4 | **Gate the forever-running shimmer `InfiniteTransition`** so it exists only while placeholder rows are on screen. Today it runs for the whole home lifetime → Choreographer never idles. | `ModernHomeRowsList.kt:250`, `PlaceholderShimmer.kt:28-39` | Idle **CPU/GPU** (no steady RAM), better thermals/power | None |
| 5 | **Defer `StartupSyncService` off `NuvioApplication`** — it drags ~30 singletons + a `runBlocking` Supabase client build onto the Application/boot path. Already injected in `MainActivity`. Also drop `SimklAnimeIdPreferenceHolder`/channel-sync eager fields. | `NuvioApplication.kt:40-43,79`, `StartupSyncService.kt:34-101`, `SupabaseModule.kt:40-77` | **Startup latency + main-thread jank**; keeps the graph off device-boot | Low |
| 6 | **Make Sentry init non-blocking / build-flag gated** — remove the `runBlocking` DataStore read on the main thread in `onCreate`. | `NuvioApplication.kt:77`, `SentryInitializer.kt:30-33` | Startup ms; small heap if disabled | Low (lose crash reports if off) |

### Tier 2 — Native payload & feature cuts (APK + on-demand RAM) — **needs product sign-off**

| # | Change | Where | Est. impact | Risk |
|---|--------|-------|-------------|------|
| 7 | **Drop `libtorrserver.so`** (torrent streaming) from the Low-RAM APK. Runs as a 41 MB co-process that competes for system RAM on 2 GB. | `app/src/main/jniLibs/*/libtorrserver.so`, `core/torrent/*` | **−41 MB APK/ABI** + no heavyweight co-process | **[product]** loses direct-torrent playback (HTTP/debrid unaffected) |
| 8 | **Flavor-gate AV1 + MPEG-H decoders out.** | `app/build.gradle.kts:478-481` | −4.3 MB APK, no chance of native load | **[product]** loses AV1 SW-decode / MPEG-H audio (rare on 2 GB TV HW) |
| 9 | **Flavor-gate `nextlib-mediainfo` out** (auto-frame-rate probe only). | `app/build.gradle.kts:491-492` | −14 MB APK | **[product]** loses accurate AFR matching (24p judder) — cosmetic |
| 10 | **Default DoVi to `STRIP_DV` / HDR10 base layer** on low-RAM (avoids off-heap libdovi conversion stacked on the Java buffer — the Fire-TV LMK spiral). | `PlayerRuntimeControllerInitialization.kt:448-452,852-853` | Avoids **off-heap conversion buffers** during DV playback | **[product]** DV plays as HDR10 — quality downgrade, not a failure |
| 11 | **Ship Low-RAM on the `playstore`-lean base** (plugins/trailers/keep-alive/updates off). | flavor config | **20–40 MB resident** for heavy-plugin users + big APK | **[product]** no JS addons / in-app trailers |

### Tier 3 — Idle & background

| # | Change | Where | Est. impact | Risk |
|---|--------|-------|-------------|------|
| 12 | **Gate TV-channel sync + drop `BOOT_COMPLETED` receiver** (keep `INITIALIZE_PROGRAMS`). Stops a whole-process spin-up at every device boot + a 15-min `JobScheduler` job that rebuilds the singleton graph. Drop the pointless `NETWORK_TYPE_ANY` constraint. | `AndroidManifest.xml:20,64-76`, `TvChannelRefreshJobService.kt:82-95`, `AndroidTvChannelSyncService.kt:74` | Removes the **only standing periodic background cost** + boot wake | Low–Med (launcher CW channel populates on next launch) |
| 13 | **Feature-flag `TrailerPlayerPool`** off (already off in playstore) — removes a 2nd persistent ExoPlayer + a startup thread. | `TrailerPlayerPool.kt:34-59`, `MainActivity.kt:258` | Tens of MB during browsing (idle ExoPlayer) | None (not on playback path) |
| 14 | **Disable `ExternalPlaybackKeepAliveService`** on low-RAM (already off in playstore) — `START_STICKY` + 8 h cap can pin the full process in RAM on a missed stop. | `ExternalPlaybackKeepAliveService.kt:33`, `AndroidManifest.xml:78-81` | Avoids worst-case **full-process pin** | None if external playback unused |

### Tier 4 — Cheap cleanups (low individual impact, near-zero risk)

| # | Change | Where | Impact |
|---|--------|-------|--------|
| 15 | Remove `coil-gif` / `AnimatedImageDecoder` (uncapped multi-frame decode from arbitrary addon art URLs). | `NuvioApplication.kt:90-94`, `build.gradle.kts:434` | Kills the one uncapped bitmap allocation; posters show first frame |
| 16 | `retainAll(activeRowKeys)` the two leaking `FocusRequester` maps. | `ModernHomeRowsList.kt:156-157`, cleanup at `ModernHomeContent.kt:324-325` | Stops slow session RAM creep |
| 17 | Delete per-recomposition `Log.d` in detail hero. | `HeroSection.kt:716` | CPU + allocations on detail screen |
| 18 | `remember(enrichedPreviews)` instead of `.apply{value=…}` each recomposition. | `ModernHomeContent.kt:1033-1034` | Allocation churn on the biggest home composable |
| 19 | `bitmapFactoryMaxParallelism(4)` → `2`. | `NuvioApplication.kt:139` | Halves concurrent decode buffer spikes |
| 20 | Fix Coil cache tier inversion (≤3 GB = 0.25 > >3 GB = 0.20). | `NuvioApplication.kt:120-124` | Correctness (helps 3 GB) |
| 21 | Collapse `customServerAuth`/`directDebrid`/`simkl` OkHttp clients onto `main.newBuilder()` (trakt already does). | `NetworkModule.kt:149,170,189` | 3 fewer dispatcher+connection pools |
| 22 | Conditionally drop `CompositingStrategy.Offscreen` on cards without a GIF/trailer overlay. | `ModernHomeRows.kt:1300-1302` | GPU RAM (up to ~20 offscreen buffers) — **needs visual check** |

### Deliberately NOT changing

- **Coil pipeline** — already RGB565 + hardware bitmaps + sized + 10% tier. Leave it.
- **`android:largeHeap="true"`** (`AndroidManifest.xml:40`) — flipping it *reduces*
  headroom and can push the process to the low-memory-killer sooner. With buffers/caches
  trimmed, actual usage won't reach the ceiling anyway. **Keep it; revisit only with device data.**
- **FFmpeg decoder** — core (TrueHD/DTS fallback, BT downmix). Keep.
- **libass**, **QuickJS internals**, **debrid**, **NanoHTTPD servers**, **periodic
  foreground pulls** — already bounded / correctly scoped.

### Out of scope but flagged

- **Trust-all TLS** on the main OkHttpClient (`NetworkModule.kt:104-115`) disables all
  cert/hostname validation. Security issue, unrelated to RAM. Recommend fixing separately.

---

## 2. Diagnostics (Phase 4)

Add a cheap, flag-gated memory logger: `onTrimMemory` levels + a periodic
`Debug.MemoryInfo` / `ActivityManager.getProcessMemoryInfo` PSS snapshot
(totalPss, dalvikPss, nativePss, graphics) logged during browse and playback, so the
above estimates can be turned into real before/after numbers on a 2 GB box.

---

## 3. Build & release (Phase 5) — environment gap

This machine has **no Android SDK, no Gradle-compatible JDK (JDK 26 present; Gradle
8.13 needs 17), no signing keystore (`nuviotv.jks`), and no `local.properties`
secrets** (TMDB/Trakt/Supabase keys). A **signed, functional release cannot be
produced here.** The plan prepares everything (version bump, release notes,
reproducible command) and hands off the actual build to an environment that has the
keystore + secrets:

```
./gradlew :app:assembleLowramRelease     # or assemblePlaystoreRelease if reusing that flavor
```
