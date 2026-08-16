package com.nuvio.tv.core.build

object AppFeaturePolicy {
    val pluginsEnabled: Boolean = true
    val inAppUpdatesEnabled: Boolean = true
    val inAppTrailerPlaybackEnabled: Boolean = true
    val externalTrailerPlaybackEnabled: Boolean = true
    val trailerPlaybackMode: TrailerPlaybackMode = TrailerPlaybackMode.IN_APP
    val imdbRatingLogoEnabled: Boolean = true

    // Low-RAM edition markers — the full flavor is never low-RAM.
    val lowRamMode: Boolean = false
    val torrentEnabled: Boolean = true
    val autoFrameRateProbeEnabled: Boolean = true
    val dolbyVisionNativeConversionEnabled: Boolean = true
}
