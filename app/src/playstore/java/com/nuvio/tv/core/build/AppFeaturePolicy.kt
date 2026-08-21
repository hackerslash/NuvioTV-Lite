package com.nuvio.tv.core.build

import com.nuvio.tv.BuildConfig

// Shared by the playstore (lean) and lite (low-RAM) flavors. Fields that differ
// between the two read from BuildConfig so a single source serves both.
object AppFeaturePolicy {
    val pluginsEnabled: Boolean = false
    // lite enables the real in-app updater (OTA from GitHub Releases); playstore keeps it off.
    val inAppUpdatesEnabled: Boolean = BuildConfig.FEATURE_IN_APP_UPDATES_ENABLED
    val inAppTrailerPlaybackEnabled: Boolean = false
    val externalTrailerPlaybackEnabled: Boolean = BuildConfig.FEATURE_EXTERNAL_TRAILERS_ENABLED
    val trailerPlaybackEnabled: Boolean = inAppTrailerPlaybackEnabled || externalTrailerPlaybackEnabled
    val supportNuvioEnabled: Boolean = false
    val trailerPlaybackMode: TrailerPlaybackMode = TrailerPlaybackMode.EXTERNAL
    val imdbRatingLogoEnabled: Boolean = false

    // Lite edition (lite flavor). false on playstore, true on lite.
    val liteMode: Boolean = BuildConfig.FEATURE_LITE_EDITION
    val torrentEnabled: Boolean = BuildConfig.FEATURE_TORRENT_ENABLED
    val dolbyVisionNativeConversionEnabled: Boolean = true
}
