package com.nuvio.tv.updater

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.nuvio.tv.BuildConfig
import java.io.File
import java.security.MessageDigest

object ApkInstaller {

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

    /** Returns false without installing if [apkFile] is not signed by the installed app's certificate. */
    fun launchInstall(context: Context, apkFile: File): Boolean {
        val installed = signingDigests(context, null)
        if (signingDigests(context, apkFile.absolutePath).none { it in installed }) return false

        val authority = "${BuildConfig.APPLICATION_ID}.fileprovider"
        val uri = FileProvider.getUriForFile(context, authority, apkFile)

        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        context.startActivity(intent)
        return true
    }

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
