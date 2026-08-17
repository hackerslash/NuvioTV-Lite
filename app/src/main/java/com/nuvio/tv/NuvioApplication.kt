package com.nuvio.tv

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.StrictMode
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.gif.GifDecoder
import coil3.gif.AnimatedImageDecoder
import coil3.svg.SvgDecoder
import coil3.request.crossfade
import coil3.request.allowHardware
import coil3.request.allowRgb565
import coil3.bitmapFactoryMaxParallelism

import okio.Path.Companion.toOkioPath
import com.nuvio.tv.core.build.AppFeaturePolicy
import com.nuvio.tv.core.device.DeviceMemoryTier
import com.nuvio.tv.core.diagnostics.MemoryDiagnostics
import com.nuvio.tv.core.diagnostics.SentryInitializer
import com.nuvio.tv.core.runtime.PluginRuntimeHooks
import com.nuvio.tv.core.sync.androidtv.AndroidTvChannelSyncService
import com.nuvio.tv.core.network.IPv4FirstDns
import com.nuvio.tv.data.local.SentrySettingsDataStore
import com.nuvio.tv.data.simkl.SimklAnimeIdPreferenceHolder
import coil3.network.cachecontrol.CacheControlCacheStrategy
import dagger.hilt.android.HiltAndroidApp
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

@HiltAndroidApp
class NuvioApplication : Application(), SingletonImageLoader.Factory {

    @Inject lateinit var androidTvChannelSyncService: AndroidTvChannelSyncService
    @Inject lateinit var sentrySettingsDataStore: SentrySettingsDataStore
    @Inject lateinit var simklAnimeIdPreferenceHolder: SimklAnimeIdPreferenceHolder

    companion object {
        /**
         * Shared cookie jar for CloudStream extension HTTP requests.
         * Accessible so the player's OkHttpClient can share cookies
         * obtained during scraping (e.g., session tokens needed for playback).
         */
        val extensionCookieJar: CookieJar = object : CookieJar {
            private val store = ConcurrentHashMap<String, MutableList<Cookie>>()

            override fun loadForRequest(url: HttpUrl): List<Cookie> {
                val hostCookies = store[url.host] ?: return emptyList()
                synchronized(hostCookies) {
                    return hostCookies.filter { cookie ->
                        cookie.expiresAt > System.currentTimeMillis()
                    }
                }
            }

            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                val hostCookies = store.getOrPut(url.host) { mutableListOf() }
                synchronized(hostCookies) {
                    cookies.forEach { newCookie ->
                        hostCookies.removeAll { it.name == newCookie.name }
                        hostCookies.add(newCookie)
                    }
                }
            }
        }
    }

    override fun onCreate() {
        // Before super.onCreate(), which triggers Hilt field injection: MemoryBudget caches
        // its tier on first touch, so the real device tier has to be resolved before any
        // injected singleton can read it. The base context is already attached here.
        DeviceMemoryTier.init(this)
        super.onCreate()
        // Lite edition skips crash reporting (Sentry SDK footprint + a blocking
        // DataStore read on the main thread) and the Android TV launcher-channel sync
        // (the JobService/boot receiver are stripped from the lite manifest).
        if (!AppFeaturePolicy.liteMode) {
            SentryInitializer.start(this, sentrySettingsDataStore)
        }
        PluginRuntimeHooks.onApplicationCreate(this)
        if (!AppFeaturePolicy.liteMode) {
            androidTvChannelSyncService.start()
        }
        // Opt-in memory diagnostics: always on for the Lite edition and debug builds.
        MemoryDiagnostics.enabled = AppFeaturePolicy.liteMode || BuildConfig.IS_DEBUG_BUILD
        MemoryDiagnostics.snapshot(this, "app-onCreate")
        // Load locale synchronously so it's available before Activity.attachBaseContext.
        // SharedPreferences reads are fast (cached in memory after first access).
        val tag = getSharedPreferences("app_locale", Context.MODE_PRIVATE)
            .getString("locale_tag", null)
        LocaleCache.localeTag = tag ?: ""
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        MemoryDiagnostics.onTrim(level)
        MemoryDiagnostics.snapshot(this, "trim-$level")
    }

    override fun newImageLoader(context: android.content.Context): ImageLoader {
        return ImageLoader.Builder(this)
            .components {
                // Lite edition skips animated-image decoding: an animated GIF/WebP/HEIF
                // from an arbitrary poster URL retains every frame, dwarfing the poster cache.
                if (!AppFeaturePolicy.liteMode) {
                    if (Build.VERSION.SDK_INT >= 28) {
                        add(AnimatedImageDecoder.Factory())
                    } else {
                        add(GifDecoder.Factory())
                    }
                }
                add(SvgDecoder.Factory())
                // CacheControlCacheStrategy respects server Cache-Control headers,
                // so dynamic images (e.g. BetterPosters with max-age) revalidate.
                add(
                    coil3.network.okhttp.OkHttpNetworkFetcherFactory(
                        callFactory = {
                            OkHttpClient.Builder()
                                .dns(IPv4FirstDns())
                                .followRedirects(true)
                                .followSslRedirects(true)
                                .build()
                        },
                        cacheStrategy = { CacheControlCacheStrategy() },
                    )
                )
            }
            .memoryCache {
                val totalRamMb = DeviceMemoryTier.totalRamMb
                // Low-RAM devices (≤2GB): use 0.10 — larger cache reduces GC pressure
                // from rapid bitmap eviction during scrolling.
                // Mid-range devices (≤3GB): use 0.15 for decent image caching.
                // Normal devices (>3GB): use 0.20 for snappy image loading.
                val cachePercent = when {
                    AppFeaturePolicy.liteMode -> 0.08
                    totalRamMb <= 2048 -> 0.10
                    totalRamMb <= 3072 -> 0.15
                    else -> 0.20
                }
                MemoryCache.Builder()
                    .maxSizePercent(context, cachePercent)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache").toOkioPath())
                    .maxSizeBytes((if (AppFeaturePolicy.liteMode) 100L else 200L) * 1024 * 1024)
                    .build()
            }
            .crossfade(false)
            .precision(coil3.size.Precision.INEXACT)
            // Hardware bitmaps are always RGBA_8888, so allowRgb565 only takes effect with them
            // off; low-RAM disables them to halve poster bytes (at some draw cost).
            .allowHardware(!AppFeaturePolicy.liteMode)
            .allowRgb565(true)
            .bitmapFactoryMaxParallelism(if (AppFeaturePolicy.liteMode) 2 else 4)
            .build()
    }
}
