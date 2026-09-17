// TG-ONLY-FILE: Telegram module — keep whole file on upstream merge
package com.nuvio.tv.core.telegram

import android.content.Context
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.io.RandomAccessFile
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * Lector fino sobre [TgDownloadSessionManager] (ventana disciplinada).
 *
 * - open()/read()/close() NUNCA emiten DownloadFile: el cursor lo posee la
 *   sesión (una emisión por motivo, single-flight). El doble open del
 *   extractor, los seeks y las re-entradas solo leen o esperan.
 * - Solo se lee dentro de bytes verificados (prefijo push o sonda
 *   GetFileDownloadedPrefixSize); fuera del prefijo TDLib deja basura.
 * - Stall acotado (30s) → TgStallTimeoutException → reintentos Exo +
 *   auto-rebuild con posición. Nunca spinner infinito.
 */
@UnstableApi
class TelegramDataSource private constructor(
    private val sessionManager: TgDownloadSessionManager
) : DataSource {

    companion object {
        private const val TAG = "TgDataSource"
        private val streamSeq = AtomicLong(1L)
    }

    private var fileId: Int = 0
    private var totalSize: Long = 0
    private var position: Long = 0
    private var bytesRemaining: Long = 0
    private var closed = false
    private var readerRegistered = false
    private var raf: RandomAccessFile? = null
    private var currentPath: String? = null
    private var lastEpoch: Long = -1L

    private var lastLogMs: Long = 0
    private var lastLogBytes: Long = 0
    private var lastConsumeSnapBytes: Long = 0
    private var lastConsumeSnapMs: Long = 0

    private val streamSessionId: Long = streamSeq.getAndIncrement()
    private var transferListener: TransferListener? = null

    override fun addTransferListener(transferListener: TransferListener) {
        this.transferListener = transferListener
    }

    override fun getUri(): android.net.Uri? = null

    @Throws(IOException::class)
    override fun open(dataSpec: DataSpec): Long {
        closed = false
        val uri = dataSpec.uri
        val pathParts = uri.path?.trim('/')?.split('/')
            ?: throw IOException("Invalid URI: $uri")
        if (pathParts.size != 4 || pathParts[0] != "tg")
            throw IOException("Invalid Telegram URI: $uri")
        fileId = pathParts[3].toIntOrNull()
            ?: throw IOException("Invalid fileId in URI: $uri")

        // Una sola llamada: meta + pin + ventana (≤1 emisión) + fichero.
        val handle = sessionManager.openReader(fileId, dataSpec.position)

        if (currentPath != handle.filePath) {
            runCatching { raf?.close() }
            currentPath = handle.filePath
            raf = RandomAccessFile(File(handle.filePath), "r")
        } else if (raf == null) {
            raf = RandomAccessFile(File(handle.filePath), "r")
        }
        readerRegistered = true

        totalSize = handle.totalSize
        position = dataSpec.position
        bytesRemaining = totalSize - dataSpec.position
        lastEpoch = sessionManager.sessionEpoch(fileId)
        lastLogMs = System.currentTimeMillis()
        lastLogBytes = position
        lastConsumeSnapBytes = position
        lastConsumeSnapMs = lastLogMs

        Log.i(
            TAG,
            "OPEN sid=$streamSessionId fileId=$fileId size=${totalSize / 1048576}MB " +
                "pos=${position / 1048576}MB free=${handle.freeBytes / 1048576}MB"
        )
        return bytesRemaining
    }

    @Throws(IOException::class)
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (closed || bytesRemaining <= 0) return -1

        // Rotación del temp (disco crítico): reabrir el raf en el nuevo fichero.
        if (!ensureEpoch()) return -1

        // Espera acotada a bytes verificados; expira → retry Exo.
        val available = try {
            sessionManager.awaitReadable(fileId, position, length) { closed }
        } catch (e: InterruptedIOException) {
            throw e
        } catch (e: IOException) {
            throw e
        }
        if (available <= 0) {
            Log.i(TAG, "READ EOF sid=$streamSessionId fileId=$fileId")
            return -1
        }
        if (closed) return -1
        // La espera puede haber rotado el temp: revalidar antes de leer.
        if (!ensureEpoch()) return -1

        // El path puede rotar (TDLib mueve el temp); reabrir si cambió.
        val livePath = sessionManager.currentPath(fileId)
        if (livePath != null && livePath != currentPath) {
            val f = File(livePath)
            if (f.exists() && f.canRead()) {
                runCatching { raf?.close() }
                currentPath = livePath
                raf = RandomAccessFile(f, "r")
                Log.i(TAG, "PATH ROTATE sid=$streamSessionId fileId=$fileId")
            }
        }

        val toRead = minOf(length.toLong(), available, bytesRemaining).toInt()
        raf?.seek(position)
        val bytesRead = raf?.read(buffer, offset, toRead) ?: -1
        if (bytesRead > 0) {
            position += bytesRead
            bytesRemaining -= bytesRead
            logProgress()
            return bytesRead
        }
        if (bytesRemaining <= 0) return -1
        throw IOException("Short read at pos=$position fileId=$fileId")
    }

    @Throws(IOException::class)
    override fun close() {
        if (closed) return
        closed = true
        runCatching { raf?.close() }
        raf = null
        if (readerRegistered) {
            readerRegistered = false
            sessionManager.releaseReader(fileId)
        }
        Log.i(TAG, "CLOSE sid=$streamSessionId fileId=$fileId pos=${position / 1048576}MB")
    }

    /**
     * Reabre el raf si la sesión rotó el temp (epoch). Espera acotada a que el
     * nuevo fichero exista. Devuelve false si cerrado.
     */
    @Throws(IOException::class)
    private fun ensureEpoch(): Boolean {
        if (closed) return false
        val epoch = sessionManager.sessionEpoch(fileId)
        if (epoch == lastEpoch && raf != null) return true
        val deadline = System.currentTimeMillis() + 15_000L
        while (System.currentTimeMillis() < deadline) {
            if (closed) return false
            val path = sessionManager.currentPath(fileId)
            if (path != null) {
                val f = File(path)
                if (f.exists() && f.canRead()) {
                    if (path != currentPath) {
                        runCatching { raf?.close() }
                        currentPath = path
                        raf = RandomAccessFile(f, "r")
                        Log.i(TAG, "EPOCH sid=$streamSessionId fileId=$fileId epoch=$epoch path=$path")
                    } else if (raf == null) {
                        raf = RandomAccessFile(f, "r")
                    }
                    lastEpoch = epoch
                    return true
                }
            }
            try {
                Thread.sleep(250L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException("ensureEpoch interrupted fileId=$fileId")
            }
        }
        throw IOException("Temp file unavailable after rotate fileId=$fileId")
    }

    private fun logProgress() {
        val now = System.currentTimeMillis()
        if (now - lastLogMs < TgWindowConfig.TELEMETRY_INTERVAL_MS) return
        val deltaBytes = position - lastLogBytes
        val deltaSec = (now - lastLogMs) / 1000.0
        val consume = if (deltaSec > 0) deltaBytes / 1024.0 / deltaSec else 0.0
        val snap = sessionManager.snapshot(fileId)
        Log.i(
            TAG,
            "READ sid=$streamSessionId fileId=$fileId pos=${position / 1048576}MB " +
                "verified=${snap?.verifiedStartMb}..${snap?.verifiedEndMb}MB " +
                "dl=${snap?.downloadedMb}/${snap?.totalMb}MB active=${snap?.active} " +
                "consume=${String.format(Locale.US, "%.0f", consume)}KB/s " +
                "ingress=${snap?.ingressKBs}KB/s issues=${snap?.issues}"
        )
        lastLogMs = now
        lastLogBytes = position
    }

    // ── Factory ───────────────────────────────────────────────────

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface TelegramClientEntryPoint {
        fun telegramClientManager(): TelegramClientManager
        fun telegramStorageManager(): TelegramStorageManager
        fun tgDownloadSessionManager(): TgDownloadSessionManager
    }

    class Factory(private val context: Context) : DataSource.Factory {
        override fun createDataSource(): DataSource {
            val appContext = context.applicationContext
            val entryPoint = EntryPointAccessors.fromApplication(
                appContext, TelegramClientEntryPoint::class.java
            )
            return TelegramDataSource(
                sessionManager = entryPoint.tgDownloadSessionManager()
            )
        }
    }
}
