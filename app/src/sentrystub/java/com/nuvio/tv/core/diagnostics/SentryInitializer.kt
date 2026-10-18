package com.nuvio.tv.core.diagnostics

import android.app.Application
import com.nuvio.tv.data.local.SentrySettingsDataStore

/**
 * Lite-edition stub: the Sentry SDK is not bundled in the lite flavor, so
 * initialization is a no-op. Mirrors the real SentryInitializer's public API so
 * call sites compile unchanged across flavors.
 */
object SentryInitializer {
    fun start(application: Application, settings: SentrySettingsDataStore) {
        // No-op: crash reporting is disabled in the lite flavor.
    }
}
