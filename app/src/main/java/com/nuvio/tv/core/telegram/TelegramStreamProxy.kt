package com.nuvio.tv.core.telegram

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CancellationException
import org.drinkless.tdlib.TdApi

/**
 * Adaptive disk-prefetch window for the streaming path. Bounded so the eMMC
 * never sees uncontrolled write pressure: TDLib caches at most [max] bytes
 * ahead of the playhead and files are deleted shortly after use.
 */
object TelegramBufferPolicy {
    const val LOW_STORAGE_PREFETCH_BYTES = 2L * 1024 * 1024
    const val MIN_PREFETCH_BYTES = 4L * 1024 * 1024
    const val DEFAULT_PREFETCH_BYTES = 8L * 1024 * 1024
    const val MAX_PREFETCH_BYTES = 12L * 1024 * 1024
    private const val LOW_STORAGE_THRESHOLD_BYTES = 500L * 1024 * 1024

    private const val TARGET_BUFFER_SECONDS = 45L
    private const val ESTIMATED_DURATION_SECONDS = 90L * 60

    fun prefetchBytes(totalSize: Long, usableSpace: Long): Long {
        val target = when {
            usableSpace < LOW_STORAGE_THRESHOLD_BYTES -> LOW_STORAGE_PREFETCH_BYTES
            totalSize <= 0L -> DEFAULT_PREFETCH_BYTES
            else -> {
                val bytesPerSecond = (totalSize / ESTIMATED_DURATION_SECONDS).coerceAtLeast(1L)
                (bytesPerSecond * TARGET_BUFFER_SECONDS)
                    .coerceIn(MIN_PREFETCH_BYTES, MAX_PREFETCH_BYTES)
            }
        }
        return if (totalSize > 0L) minOf(target, totalSize) else target
    }
}

/**
 * Local loopback HTTP server translating ExoPlayer Range requests into bounded
 * TDLib chunk downloads. Binds to 127.0.0.1 on a dynamic port; URLs look like
 * http://127.0.0.1:PORT/tg/{chatId}/{messageId}/{fileId}. chatId/messageId are
 * carried for future stale-fileId re-resolution but are unused in v1.
 */
@Singleton
class TelegramStreamProxy @Inject constructor(
    @ApplicationContext private val context: Context,
    private val clientManager: TelegramClientManager
) {
    companion object {
        private const val TAG = "TelegramProxy"
        private const val READ_CHUNK_BYTES = 1024 * 1024 // 1 MB, 1KB-aligned
        private const val DOWNLOAD_TIMEOUT_MS = 30_000L
        private const val DOWNLOAD_PRIORITY = 32
        private const val POLL_INTERVAL_MS = 100L
        private const val CACHE_CLEANUP_GRACE_MS = 30_000L
        private const val FILE_DELETE_TIMEOUT_MS = 5_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var server: NanoHTTPD? = null

    val port: Int
        get() = server?.listeningPort ?: -1

    val isRunning: Boolean
        get() = server != null

    /** Idempotent; binds loopback only. */
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
        return "http://127.0.0.1:$port/tg/$chatId/$messageId/$fileId"
    }

    private fun serve(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val uri = session.uri ?: return notFound()
        val parts = uri.trim('/').split('/')
        if (parts.size != 4 || parts[0] != "tg") return notFound()
        val fileId = parts[3].toIntOrNull() ?: return notFound()

        val fileInfo = runCatching { blockingFileInfo(fileId) }.getOrNull()
            ?: return notFound()
        val (totalSize, mimeType) = fileInfo
        if (totalSize <= 0L) return notFound()

        val rangeHeader = session.headers["range"]
        val (start, endInclusive) = parseRange(rangeHeader, totalSize)

        Log.d(TAG, "stream fileId=$fileId range=$start-$endInclusive/$totalSize")

        val stream = TdlibChunkInputStream(fileId, start, endInclusive, totalSize)
        val status = if (start == 0L && endInclusive == totalSize - 1 && rangeHeader == null) {
            NanoHTTPD.Response.Status.OK
        } else {
            NanoHTTPD.Response.Status.PARTIAL_CONTENT
        }

        beginStreamRequest(fileId)
        val response = NanoHTTPD.newFixedLengthResponse(
            status, mimeType, stream, endInclusive - start + 1
        )
        response.addHeader("Accept-Ranges", "bytes")
        response.addHeader("Content-Range", "bytes $start-$endInclusive/$totalSize")
        return response
    }

    private fun notFound(): NanoHTTPD.Response =
        NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.NOT_FOUND, "text/plain", "not found"
        )

    /**
     * Parses "bytes=a-b", "bytes=a-", "bytes=-suffix".
     * Returns inclusive [start, end] clamped to file size; falls back to the
     * whole file on malformed input.
     */
    internal fun parseRange(header: String?, totalSize: Long): Pair<Long, Long> =
        TelegramRangeParser.parse(header, totalSize)

    private data class FileInfo(val size: Long, val mimeType: String)

    private fun blockingFileInfo(fileId: Int): FileInfo? {
        var result: FileInfo? = null
        val latch = java.util.concurrent.CountDownLatch(1)
        scope.launch {
            try {
                val file = clientManager.sendRequest(TdApi.GetFile().apply { this.fileId = fileId })
                    as? TdApi.File
                val size = file?.size?.takeIf { it > 0 } ?: file?.expectedSize ?: 0L
                if (size > 0L) result = FileInfo(size, "application/octet-stream")
            } catch (_: Exception) {
            } finally {
                latch.countDown()
            }
        }
        latch.await(DOWNLOAD_TIMEOUT_MS + 5_000L, java.util.concurrent.TimeUnit.MILLISECONDS)
        return result?.takeIf { it.size > 0 }
    }

    // ── Active-request bookkeeping: delete cached file shortly after use ────

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
                        // Request restarted meanwhile; don't delete its cache.
                        job.cancel()
                    }
                }
            }
        }
    }

    // ── Download window coordination ────────────────────────────────────────
    //
    // TDLib keeps ONE active download per fileId; a second downloadFile call
    // with different offset/limit cancels the previous one. Concurrent readers
    // (ExoPlayer probe + main transfer) would therefore starve each other in a
    // livelock. All window work is serialized behind a per-file mutex and we
    // track which byte interval is locally available ourselves.
    //
    // IMPORTANT: TDLib's file.local.downloadedPrefixSize is measured from the
    // CURRENT download request's offset, not from the start of the file.

    private class FileWindowState {
        var windowStart: Long = -1L      // offset of the active DownloadFile window
        var coveredUpTo: Long = -1L      // exclusive end of the local contiguous prefix
        var fullyDownloaded: Boolean = false
    }

    private val windowStates = java.util.concurrent.ConcurrentHashMap<Int, FileWindowState>()
    private val windowMutexes = java.util.concurrent.ConcurrentHashMap<Int, kotlinx.coroutines.sync.Mutex>()

    private fun windowMutexFor(fileId: Int): kotlinx.coroutines.sync.Mutex =
        windowMutexes.getOrPut(fileId) { kotlinx.coroutines.sync.Mutex() }

    /**
     * Makes [wantedEnd] bytes from [offset] available locally, reusing the
     * active window whenever it already covers the range. Must be called while
     * holding the file's window mutex.
     */
    private suspend fun ensureRangeAvailable(
        fileId: Int,
        offset: Long,
        wantedEnd: Long,
        prefetchBytes: Long
    ): Boolean {
        val state = windowStates.getOrPut(fileId) { FileWindowState() }
        if (!state.fullyDownloaded) refreshWindowLocked(state, fileId)

        if (isCovered(state, offset, wantedEnd)) return true

        // Re-issue the single active download window at our offset.
        issueDownloadWindow(fileId, offset, prefetchBytes)
        state.windowStart = offset

        val deadline = System.currentTimeMillis() + DOWNLOAD_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            delay(POLL_INTERVAL_MS)
            refreshWindowLocked(state, fileId)
            if (isCovered(state, offset, wantedEnd)) return true
        }
        Log.w(TAG, "Window wait timed out fileId=$fileId range=$offset-$wantedEnd")
        return false
    }

    private fun isCovered(state: FileWindowState, offset: Long, wantedEnd: Long): Boolean {
        if (state.fullyDownloaded) return true
        if (state.windowStart < 0 || state.coveredUpTo < 0) return false
        // Single contiguous interval: [windowStart, coveredUpTo)
        return offset >= state.windowStart && wantedEnd <= state.coveredUpTo
    }

    /** Reads current TDLib state and folds it into our coverage model. */
    private suspend fun refreshWindowLocked(state: FileWindowState, fileId: Int) {
        if (state.fullyDownloaded) return
        val getFile = TdApi.GetFile()
        getFile.fileId = fileId
        val file = runCatching { clientManager.sendRequest(getFile) }.getOrNull() as? TdApi.File
            ?: return
        val local = file.local ?: return
        if (local.isDownloadingCompleted) {
            state.fullyDownloaded = true
            state.coveredUpTo = Long.MAX_VALUE
            return
        }
        if (state.windowStart >= 0 && local.downloadedPrefixSize > 0) {
            // Prefix counts from the active window's start offset.
            val absoluteEnd = state.windowStart + local.downloadedPrefixSize
            if (absoluteEnd > state.coveredUpTo) state.coveredUpTo = absoluteEnd
        }
    }

    private suspend fun issueDownloadWindow(fileId: Int, offset: Long, limitBytes: Long) {
        val download = TdApi.DownloadFile()
        download.fileId = fileId
        download.priority = DOWNLOAD_PRIORITY
        download.offset = offset
        download.limit = limitBytes
        download.synchronous = false
        runCatching { clientManager.sendRequest(download) }
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
            windowStates.remove(fileId)
            windowMutexes.remove(fileId)
        } catch (e: CancellationException) {
            throw e
        }
    }

    /**
     * Lazily pulls the requested byte span from TDLib in bounded 1MB chunks.
     * Each chunk waits until TDLib reports the bytes locally cached, then reads
     * them via ReadFilePart — no full-file buffering ever.
     */
    inner class TdlibChunkInputStream(
        private val fileId: Int,
        private val rangeStart: Long,
        private val rangeEndInclusive: Long,
        private val totalSize: Long
    ) : InputStream() {

        private var position: Long = rangeStart
        private var closed = false

        private var buffer: ByteArray? = null
        private var bufferStart: Long = 0
        private var bufferLimit: Int = 0
        private var bufferPos: Int = 0

        override fun available(): Int {
            return bufferLimit - bufferPos
        }

        override fun read(): Int {
            val single = ByteArray(1)
            val n = read(single, 0, 1)
            return if (n == -1) -1 else single[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (closed) return -1
            if (position > rangeEndInclusive) return -1
            if (len == 0) return 0

            ensureBuffered()
            val buf = buffer ?: return -1
            if (bufferPos >= bufferLimit) return -1

            val toCopy = minOf(len, bufferLimit - bufferPos)
            System.arraycopy(buf, bufferPos, b, off, toCopy)
            bufferPos += toCopy
            position += toCopy
            return toCopy
        }

        private fun ensureBuffered() {
            if (buffer != null && bufferPos < bufferLimit) return
            if (position > rangeEndInclusive) return

            val remaining = rangeEndInclusive - position + 1
            val chunkLen = minOf(READ_CHUNK_BYTES.toLong(), remaining).toInt()

            val fetched = runCatching {
                kotlinx.coroutines.runBlocking { blockingDownloadChunk(position, chunkLen) }
            }.getOrNull()
            if (fetched == null || fetched.isEmpty()) {
                // Surface the failure instead of a silent EOF: ExoPlayer reacts to
                // IOException with a clean reopen/retry, mid-file EOF looks like a
                // truncated video.
                throw java.io.IOException("TDLib chunk unavailable at $position (fileId=$fileId)")
            }

            buffer = fetched
            bufferStart = position
            bufferPos = 0
            bufferLimit = fetched.size
        }

        /**
         * Coordinates TDLib's single-download-per-file model, then reads exactly
         * [chunkLen] bytes at [offset] from the local cache.
         */
        private suspend fun blockingDownloadChunk(offset: Long, chunkLen: Int): ByteArray? {
            val freeSpace = runCatching { context.filesDir.usableSpace }.getOrDefault(Long.MAX_VALUE)
            val prefetch = TelegramBufferPolicy.prefetchBytes(totalSize, freeSpace)
                .coerceAtLeast(chunkLen.toLong())

            val ready = windowMutexFor(fileId).withLock {
                ensureRangeAvailable(fileId, offset, offset + chunkLen, prefetch)
            }
            if (!ready) return null

            val readPart = TdApi.ReadFilePart()
            readPart.fileId = fileId
            readPart.offset = offset
            readPart.count = chunkLen.toLong()
            val data = clientManager.sendRequest(readPart) as? TdApi.Data
            return data?.data?.takeIf { it.isNotEmpty() }
        }

        override fun close() {
            if (closed) return
            closed = true
            buffer = null
            endStreamRequest(fileId)
        }
    }
}
