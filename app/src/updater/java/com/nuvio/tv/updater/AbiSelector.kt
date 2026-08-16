package com.nuvio.tv.updater

import android.os.Build
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.data.remote.dto.GitHubAssetDto

internal object AbiSelector {

    private val knownAbis = listOf(
        "arm64-v8a",
        "armeabi-v7a",
        "x86_64",
        "x86"
    )

    fun chooseBestApkAsset(assets: List<GitHubAssetDto>): GitHubAssetDto? {
        val allApk = assets.filter { it.name.endsWith(".apk", ignoreCase = true) }
        if (allApk.isEmpty()) return null
        // Never cross editions: a lowram install only accepts "-lowram" APKs; a non-lowram
        // install rejects them. Falls back to the full set if a release has no matching edition.
        val apkAssets = if (BuildConfig.FEATURE_LOW_RAM_EDITION) {
            allApk.filter { it.name.contains("-lowram", ignoreCase = true) }
        } else {
            allApk.filter { !it.name.contains("-lowram", ignoreCase = true) }
        }.ifEmpty { allApk }
        if (apkAssets.size == 1) return apkAssets.first()

        val supported = Build.SUPPORTED_ABIS?.toList().orEmpty()

        // Prefer exact ABI match (in device preference order)
        for (abi in supported) {
            val candidate = apkAssets.firstOrNull { it.name.contains(abi, ignoreCase = true) }
            if (candidate != null) return candidate
        }

        // Fallback to a universal APK if present
        val universal = apkAssets.firstOrNull {
            val n = it.name.lowercase()
            n.contains("universal") || n.contains("all") || n.contains("universal-release")
        }
        if (universal != null) return universal

        // If we can at least avoid wrong-ABI picks, prefer APKs that don't mention a known ABI.
        val noAbiMention = apkAssets.firstOrNull { asset ->
            knownAbis.none { abi -> asset.name.contains(abi, ignoreCase = true) }
        }
        return noAbiMention ?: apkAssets.first()
    }
}
