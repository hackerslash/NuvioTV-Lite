package com.nuvio.tv.updater

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import com.nuvio.tv.BuildConfig
import java.io.File
import java.security.MessageDigest

object ApkInstaller {

    // TG-START: updater observability, pre-install gate outcome (re-apply on upstream merge)
    private const val TAG = "ApkInstaller"

    /** Outcome of the pre-install gate in [launchInstall]. */
    enum class LaunchGate { LAUNCH, SIGNATURE_MISMATCH, UNVERIFIABLE }

    /**
     * Pure gate: an empty [archiveDigests] (platforms where getPackageArchiveInfo
     * yields no certificates even for a well-formed APK) must NOT read as a
     * mismatch — the system installer, which verifies rigorously, gets the
     * final verdict instead.
     */
    internal fun gate(installedDigests: Set<String>, archiveDigests: Set<String>): LaunchGate =
        when {
            archiveDigests.isEmpty() -> LaunchGate.UNVERIFIABLE
            archiveDigests.none { it in installedDigests } -> LaunchGate.SIGNATURE_MISMATCH
            else -> LaunchGate.LAUNCH
        }
    // TG-END

    fun canRequestPackageInstalls(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    fun buildUnknownSourcesSettingsIntent(context: Context): Intent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } else {
            null
        }
    }

    // TG-START: updater observability, delegate unverifiable files to system installer (re-apply on upstream merge)
    /**
     * Hands [apkFile] to the system installer. Genuine mismatches are still
     * blocked here; when the archive certificates cannot even be read (or the
     * install intent cannot be fired), the outcome is UNVERIFIABLE so the UI
     * can say so instead of crying wolf about signatures.
     */
    fun launchInstall(context: Context, apkFile: File): LaunchGate {
        val installed = signingDigests(context, null)
        val archive = signingDigests(context, apkFile.absolutePath)
        Log.d(TAG, "launchInstall file=${apkFile.name} size=${apkFile.length()} installedDigests=$installed archiveDigests=$archive")
        if (gate(installed, archive) == LaunchGate.SIGNATURE_MISMATCH) {
            Log.w(TAG, "launchInstall rejected: genuine signature mismatch")
            return LaunchGate.SIGNATURE_MISMATCH
        }
        val launched = runCatching {
            val authority = "${BuildConfig.APPLICATION_ID}.fileprovider"
            val uri = FileProvider.getUriForFile(context, authority, apkFile)

            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            context.startActivity(intent)
        }.onFailure { e ->
            Log.e(TAG, "launchInstall startActivity failed", e)
        }.isSuccess
        return if (launched) LaunchGate.LAUNCH else LaunchGate.UNVERIFIABLE
    }
    // TG-END

    /** SHA-256 of each signing certificate of [apkPath], or of the installed app when null. */
    @Suppress("DEPRECATION")
    private fun signingDigests(context: Context, apkPath: String?): Set<String> {
        val pm = context.packageManager
        fun read(flags: Int): PackageInfo? = runCatching {
            if (apkPath == null) pm.getPackageInfo(context.packageName, flags)
            else pm.getPackageArchiveInfo(apkPath, flags)
        }.getOrNull()

        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            read(PackageManager.GET_SIGNING_CERTIFICATES)?.signingInfo?.let {
                // History covers a later key rotation; multi-signer APKs expose signers instead.
                if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory
            }
        } else {
            read(PackageManager.GET_SIGNATURES)?.signatures
        }
        return signatures.orEmpty().map { signature ->
            MessageDigest.getInstance("SHA-256")
                .digest(signature.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }.toSet()
    }
}
