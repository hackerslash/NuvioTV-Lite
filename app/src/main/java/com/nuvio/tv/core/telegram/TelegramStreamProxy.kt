package com.nuvio.tv.core.telegram

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CancellationException
import org.drinkless.tdlib.TdApi

/**
 * Local loopback HTTP server for Telegram file streaming.
 *
 * Reads directly from the local file that TDLib manages on disk, the same way
 * ARVIO and Nagram do it. No ReadFilePart, no manual window coordination.
 * TDLib handles its own multi-connection parallel download; we just read the
 * bytes from local.path as they become available.
 *
 * Binds to 127.0.0.1 on a dynamic port. URLs:
 * http://127.0.0.1:PORT/tg/{chatId}/{messageId}/{fileId}
 */
@Singleton
class TelegramStreamProxy @Inject constructor(
    @ApplicationContext private val context: Context,
    private val clientManager: TelegramClientManager
) {
    companion object {
        private const val TAG = "TelegramProxy"
        private const val DOWNLOAD_PRIORITY = 32
        private const val POLL_PATH_MS = 100L
    private const val INITIAL_BUFFER_TARGET_BYTES = 8L * 1024 * 1024 // 8 MB head start
        private const val POLL_DATA_MS = 50L
        private const val WAIT_DATA_TIMEOUT_MS = 60_000L
        private const val CACHE_CLEANUP_GRACE_MS = 30_000L
        private const val FILE_DELETE_TIMEOUT_MS = 5_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var server: NanoHTTPD? = null

    val port: Int get() = server?.listeningPort ?: -1
    val isRunning: Boolean get() = server != null

    fun start() {
        if (server != null) return
        synchronized(this) {
            if (server != null) return
            val srv = object : NanoHTTPD("127.0.0.1", 0) {
                override fun serve(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response =
                    this@TelegramStreamProxy.serve(session)
            }
            try {
                srv.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
                server = srv
                Log.i(TAG, "Streaming proxy listening on 127.0.0.1:${srv.listeningPort}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start proxy", e)
            }
        }
    }

    fun stop() {
        synchronized(this) {
            server?.stop()
            server = null
        }
    }

    fun buildStreamUrl(chatId: Long, messageId: Long, fileId: Int): String {
        start()
        val path = blockingStartDownload(fileId)
        if (path == null) {
            Log.w(TAG, "buildStreamUrl: local path still null after download start for $fileId, serving anyway")
        }
        return "http://127.0.0.1:$port/tg/$chatId/$messageId/$fileId"
    }

    /**
     * Starts TDLib download and blocks until local.path is available AND
     * some initial bytes are downloaded (up to 30s).
     */
    private fun blockingStartDownload(fileId: Int): String? {
        // Kick off the download
        scope.launch {
            try {
                val dl = TdApi.DownloadFile()
                dl.fileId = fileId
                dl.priority = DOWNLOAD_PRIORITY
                dl.offset = 0
                dl.limit = 0
                dl.synchronous = false
                clientManager.sendRequest(dl)
            } catch (_: Exception) { }
        }

        // Poll until local.path appears AND some bytes are available
        val deadline = System.currentTimeMillis() + 30_000L
        var path: String? = null
        while (System.currentTimeMillis() < deadline) {
            val result = runBlocking {
                try {
                    val file = clientManager.sendRequest(
                        TdApi.GetFile().apply { this.fileId = fileId }
                    ) as? TdApi.File
                    val p = file?.local?.path?.takeIf { it.isNotEmpty() }
                    val downloaded = file?.local?.downloadedPrefixSize ?: 0L
                    val complete = file?.local?.isDownloadingCompleted == true
                    Triple(p, downloaded, complete)
                } catch (_: Exception) { Triple(null, 0L, false) }
            }
            val (p, downloaded, complete) = result
            if (p != null) path = p
            if (path != null && (downloaded >= INITIAL_BUFFER_TARGET_BYTES || complete)) {
                Log.i(TAG, "download ready: fileId=$fileId path=$path downloaded=${downloaded / 1048576}MB complete=$complete")
                return path
            }
            Thread.sleep(POLL_PATH_MS)
        }
        Log.w(TAG, "download head-start timed out for fileId=$fileId, proceeding anyway")
        return path
    }

    // ── HTTP server ──────────────────────────────────────────────────────

    private fun serve(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val uri = session.uri ?: return notFound()
        val parts = uri.trim('/').split('/')
        if (parts.size != 4 || parts[0] != "tg") return notFound()
        val fileId = parts[3].toIntOrNull() ?: return notFound()

        val fileInfo = runCatching { blockingGetFileInfo(fileId) }.getOrNull()
            ?: return notFound()
        val totalSize = fileInfo.totalSize
        val mimeType = fileInfo.mimeType
        val localPath = fileInfo.localPath

        if (totalSize <= 0L) return notFound()

        val rangeHeader = session.headers["range"]
        val (start, endInclusive) = TelegramRangeParser.parse(rangeHeader, totalSize)
        val length = endInclusive - start + 1

        Log.d(TAG, "stream fileId=$fileId range=$start-$endInclusive/$totalSize path=${localPath != null}")

        if (localPath == null || !File(localPath).exists() || !File(localPath).canRead()) {
            val path = waitForPath(fileId, 15_000L)
            if (path != null) {
                return serveFromDisk(fileId, File(path), start, endInclusive, totalSize, mimeType, rangeHeader)
            }
            return serviceUnavailable("TDLib file not available $fileId")
        }

        return serveFromDisk(fileId, File(localPath), start, endInclusive, totalSize, mimeType, rangeHeader)
    }

    private fun waitForPath(fileId: Int, timeoutMs: Long): String? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val path = runBlocking {
                try {
                    val file = clientManager.sendRequest(
                        TdApi.GetFile().apply { this.fileId = fileId }
                    ) as? TdApi.File
                    file?.local?.path?.takeIf { it.isNotEmpty() }
                } catch (_: Exception) { null }
            }
            if (path != null) return path
            Thread.sleep(POLL_PATH_MS)
        }
        return null
    }

    private fun serveFromDisk(
        fileId: Int,
        file: File,
        start: Long,
        endInclusive: Long,
        totalSize: Long,
        mimeType: String,
        rangeHeader: String?
    ): NanoHTTPD.Response {
        val length = endInclusive - start + 1
        Log.d(TAG, "stream fileId=$fileId range=$start-$endInclusive/$totalSize ok")

        beginStreamRequest(fileId)
        val stream = DiskFileStream(fileId, file, start, endInclusive, totalSize)
        val status = if (start == 0L && endInclusive == totalSize - 1 && rangeHeader == null) {
            NanoHTTPD.Response.Status.OK
        } else {
            NanoHTTPD.Response.Status.PARTIAL_CONTENT
        }
        val response = NanoHTTPD.newFixedLengthResponse(status, mimeType, stream, length)
        response.addHeader("Accept-Ranges", "bytes")
        response.addHeader("Content-Range", "bytes $start-$endInclusive/$totalSize")
        return response
    }

    // ── File info / download ─────────────────────────────────────────────

    private data class FileInfo(val totalSize: Long, val mimeType: String, val localPath: String?)

    private fun blockingGetFileInfo(fileId: Int): FileInfo? {
        var result: FileInfo? = null
        val latch = java.util.concurrent.CountDownLatch(1)
        scope.launch {
            try {
                val file = clientManager.sendRequest(
                    TdApi.GetFile().apply { this.fileId = fileId }
                ) as? TdApi.File
                if (file != null) {
                    val size = file.size.takeIf { it > 0 } ?: file.expectedSize
                    if (size > 0L) {
                        result = FileInfo(
                            totalSize = size,
                            mimeType = "application/octet-stream",
                            localPath = file.local?.path?.takeIf { it.isNotEmpty() }
                        )
                    }
                }
            } catch (_: Exception) {
            } finally {
                latch.countDown()
            }
        }
        latch.await(10_000, java.util.concurrent.TimeUnit.MILLISECONDS)
        return result
    }

    // ── Disk-based streaming ─────────────────────────────────────────────

    /**
     * Reads directly from the TDLib local file on disk. When the reader
     * reaches the current end-of-file but the download is still active,
     * it polls until more bytes appear or the download completes.
     *
     * This is exactly how ARVIO/Nagram stream: TDLib writes, we read.
     */
    inner class DiskFileStream(
        private val fileId: Int,
        private val file: File,
        private val rangeStart: Long,
        private val rangeEndInclusive: Long,
        private val totalSize: Long
    ) : InputStream() {

        private var position: Long = rangeStart
        private var closed = false
        private var raf: RandomAccessFile? = null
        private var lastLogMs: Long = System.currentTimeMillis()
        private var lastLogBytes: Long = rangeStart

        private fun ensureRaf(): RandomAccessFile {
            if (raf == null) {
                raf = RandomAccessFile(file, "r")
            }
            return raf!!
        }

        override fun read(): Int {
            if (closed || position > rangeEndInclusive) return -1
            val r = ensureRaf()
            val deadline = System.currentTimeMillis() + WAIT_DATA_TIMEOUT_MS
            while (true) {
                if (position >= file.length()) {
                    if (isDownloadComplete()) return -1
                    if (System.currentTimeMillis() >= deadline) return -1
                    Thread.sleep(POLL_DATA_MS)
                    continue
                }
                r.seek(position)
                val b = r.read()
                if (b == -1) {
                    if (isDownloadComplete()) return -1
                    if (System.currentTimeMillis() >= deadline) return -1
                    Thread.sleep(POLL_DATA_MS)
                    continue
                }
                position++
                return b
            }
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (closed || position > rangeEndInclusive || len == 0) return -1
            val r = ensureRaf()
            val deadline = System.currentTimeMillis() + WAIT_DATA_TIMEOUT_MS
            while (true) {
                val available = file.length() - position
                if (available <= 0) {
                    if (isDownloadComplete()) return -1
                    if (System.currentTimeMillis() >= deadline) return -1
                    Thread.sleep(POLL_DATA_MS)
                    continue
                }
                val toRead = minOf(len.toLong(), available, rangeEndInclusive - position + 1).toInt()
                r.seek(position)
                val bytesRead = r.read(b, off, toRead)
                if (bytesRead <= 0) {
                    if (isDownloadComplete()) return -1
                    if (System.currentTimeMillis() >= deadline) return -1
                    Thread.sleep(POLL_DATA_MS)
                    continue
                }
                position += bytesRead
                logProgress()
                return bytesRead
            }
        }

        private fun logProgress() {
            val now = System.currentTimeMillis()
            if (now - lastLogMs < 3_000) return
            val deltaBytes = position - lastLogBytes
            val deltaSec = (now - lastLogMs) / 1000.0
            val speed = if (deltaSec > 0) deltaBytes / 1024.0 / deltaSec else 0.0
            val diskSize = file.length()
            Log.i(TAG, "TG-READ fileId=$fileId pos=${position / 1048576}MB disk=${diskSize / 1048576}MB total=${totalSize / 1048576}MB speed=${String.format(Locale.US, "%.0f", speed)}KB/s")
            lastLogMs = now
            lastLogBytes = position
        }

        private fun isDownloadComplete(): Boolean {
            var result = false
            val latch = java.util.concurrent.CountDownLatch(1)
            scope.launch {
                try {
                    val file = clientManager.sendRequest(
                        TdApi.GetFile().apply { this.fileId = fileId }
                    ) as? TdApi.File
                    result = file?.local?.isDownloadingCompleted == true
                } catch (_: Exception) {
                } finally {
                    latch.countDown()
                }
            }
            latch.await(2_000, java.util.concurrent.TimeUnit.MILLISECONDS)
            return result
        }

        override fun close() {
            if (closed) return
            closed = true
            runCatching { raf?.close() }
            raf = null
            endStreamRequest(fileId)
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun notFound(): NanoHTTPD.Response =
        NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.NOT_FOUND, "text/plain", "not found")

    private fun serviceUnavailable(reason: String): NanoHTTPD.Response =
        NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.SERVICE_UNAVAILABLE, "text/plain", reason)

    // ── Active-request bookkeeping ───────────────────────────────────────

    private data class RequestState(
        var activeRequests: Int = 0,
        var cleanupJob: kotlinx.coroutines.Job? = null
    )

    private val stateLock = Any()
    private val requestStates = HashMap<Int, RequestState>()

    private fun beginStreamRequest(fileId: Int) {
        synchronized(stateLock) {
            val state = requestStates.getOrPut(fileId) { RequestState() }
            state.cleanupJob?.cancel()
            state.cleanupJob = null
            state.activeRequests += 1
        }
    }

    private fun endStreamRequest(fileId: Int) {
        var scheduleCleanup = false
        synchronized(stateLock) {
            val state = requestStates[fileId] ?: return
            state.activeRequests = (state.activeRequests - 1).coerceAtLeast(0)
            if (state.activeRequests == 0 && state.cleanupJob == null) {
                scheduleCleanup = true
            }
        }
        if (scheduleCleanup) {
            val cleanupJob = scope.launch {
                delay(CACHE_CLEANUP_GRACE_MS)
                var doDelete = false
                synchronized(stateLock) {
                    val current = requestStates[fileId]
                    if (current != null && current.activeRequests == 0) {
                        requestStates.remove(fileId)
                        doDelete = true
                    }
                }
                if (doDelete) deleteCachedFile(fileId)
            }.also { job ->
                synchronized(stateLock) {
                    val state = requestStates[fileId]
                    if (state != null) {
                        state.cleanupJob = job
                    } else {
                        job.cancel()
                    }
                }
            }
        }
    }

    private suspend fun deleteCachedFile(fileId: Int) {
        try {
            withTimeoutOrNull(FILE_DELETE_TIMEOUT_MS) {
                runCatching {
                    clientManager.sendRequest(TdApi.CancelDownloadFile().apply {
                        this.fileId = fileId
                        onlyIfPending = false
                    })
                }
            }
            withTimeoutOrNull(FILE_DELETE_TIMEOUT_MS) {
                runCatching {
                    clientManager.sendRequest(TdApi.DeleteFile().apply { this.fileId = fileId })
                }
            }
            Log.d(TAG, "Deleted cached file $fileId")
        } catch (e: CancellationException) {
            throw e
        }
    }
}
