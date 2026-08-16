package com.nuvio.tv.core.build

import com.nuvio.tv.BuildConfig

// Shared by the playstore (lean) and lowram (low-RAM) flavors. Fields that differ
// between the two read from BuildConfig so a single source serves both.
object AppFeaturePolicy {
    val pluginsEnabled: Boolean = false
    // lowram enables the real in-app updater (OTA from GitHub Releases); playstore keeps it off.
    val inAppUpdatesEnabled: Boolean = BuildConfig.FEATURE_IN_APP_UPDATES_ENABLED
    val inAppTrailerPlaybackEnabled: Boolean = false
    val externalTrailerPlaybackEnabled: Boolean = BuildConfig.FEATURE_EXTERNAL_TRAILERS_ENABLED
    val trailerPlaybackMode: TrailerPlaybackMode = TrailerPlaybackMode.EXTERNAL
    val imdbRatingLogoEnabled: Boolean = false

    // Low-RAM edition (lowram flavor). false on playstore, true on lowram.
    val lowRamMode: Boolean = BuildConfig.FEATURE_LOW_RAM_EDITION
    val torrentEnabled: Boolean = BuildConfig.FEATURE_TORRENT_ENABLED
    val autoFrameRateProbeEnabled: Boolean = !BuildConfig.FEATURE_LOW_RAM_EDITION
    val dolbyVisionNativeConversionEnabled: Boolean = !BuildConfig.FEATURE_LOW_RAM_EDITION
}
