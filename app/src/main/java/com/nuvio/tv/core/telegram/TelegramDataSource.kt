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
import java.io.RandomAccessFile
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.drinkless.tdlib.TdApi

/**
 * Custom ExoPlayer [DataSource] that reads Telegram files directly from TDLib's
 * temp files via [RandomAccessFile]. No HTTP proxy involved.
 *
 * Pattern inspired by Nagram's [FileStreamLoadOperation]:
 * - Blocking wait in read() when data isn't on disk yet
 * - Seek-aware DownloadFile reissue (linear + seek window)
 * - Single reader per stream
 * - TDLib manages download progress; we just read from disk
 */
@UnstableApi
class TelegramDataSource private constructor(
    private val clientManager: TelegramClientManager,
    private val storageManager: TelegramStorageManager
) : DataSource {

    companion object {
        private const val TAG = "TgDataSource"
        private const val DOWNLOAD_PRIORITY = 32
        private const val POLL_DATA_MS = 100L
        private const val READ_TIMEOUT_MS = 240_000L
        private const val FILE_APPEAR_TIMEOUT_MS = 15_000L
        private const val INFO_CACHE_TTL_MS = 120_000L
        private const val SEEK_GAP_TRIGGER_BYTES = 64L * 1024L * 1024L
        private const val LINEAR_WINDOW_BYTES = 192L * 1024L * 1024L
        private const val SEEK_WINDOW_BYTES = 96L * 1024L * 1024L
        private const val DOWNLOAD_REISSUE_COOLDOWN_MS = 2_500L
        private const val DOWNLOAD_REISSUE_MIN_DELTA_BYTES = 8L * 1024L * 1024L
        private const val SEEK_EXIT_HYSTERESIS_BYTES = 32L * 1024L * 1024L
        private const val SEEK_LOCK_MS = 3_000L
        private const val DOWNLOAD_STATE_TTL_MS = 20L * 60L * 1000L
        private const val COMPLETION_CHECK_INTERVAL_MS = 1_000L
        private const val PATH_REFRESH_INTERVAL_MS = 2_000L
        private const val RANGE_CHECK_INTERVAL_MS = 250L
        private const val MIN_FREE_BYTES_SOFT = 700L * 1024L * 1024L
        private const val CANCEL_COOLDOWN_MS = 10_000L

        private data class FileInfo(val totalSize: Long, val localPath: String?, val ts: Long)
        private val fileInfoCache = ConcurrentHashMap<Int, FileInfo>()

        private enum class DownloadMode { LINEAR, SEEK }

        private data class DownloadState(
            var mode: DownloadMode,
            var offset: Long,
            var limit: Long,
            var lastIssueMs: Long,
            var reissueCount: Int,
            var active: Boolean,
            var updatedAtMs: Long
        )

        private val downloadStateByFileId = ConcurrentHashMap<Int, DownloadState>()
        private val streamSeq = AtomicLong(1L)
    }

    private var fileId: Int = 0
    private var totalSize: Long = 0
    private var position: Long = 0
    private var bytesRemaining: Long = 0
    private var closed = false
    private var raf: RandomAccessFile? = null
    private var currentFile: File? = null

    private var lastLogMs: Long = 0
    private var lastLogBytes: Long = 0
    private var lastCompletionCheckMs: Long = 0
    private var downloadCompletedCache: Boolean = false
    private var lastPathRefreshMs: Long = 0
    private var lastRangeCheckMs: Long = 0
    private var downloadedRangeStart: Long = 0
    private var downloadedRangeEndExclusive: Long = 0
    private var downloadActiveCache: Boolean = false
    private val streamSessionId: Long = streamSeq.getAndIncrement()
    private var lastCancelMs: Long = 0
    private var seekLockUntilMs: Long = 0

    private var transferListener: TransferListener? = null

    override fun addTransferListener(transferListener: TransferListener) {
        this.transferListener = transferListener
    }

    override fun getUri(): android.net.Uri? = null

    @Throws(IOException::class)
    override fun open(dataSpec: DataSpec): Long {
        closed = false
        downloadCompletedCache = false
        lastCompletionCheckMs = 0
        lastPathRefreshMs = 0
        lastRangeCheckMs = 0
        downloadedRangeStart = 0
        downloadedRangeEndExclusive = 0
        downloadActiveCache = false

        val uri = dataSpec.uri
        val pathParts = uri.path?.trim('/')?.split('/')
            ?: throw IOException("Invalid URI: $uri")
        if (pathParts.size != 4 || pathParts[0] != "tg")
            throw IOException("Invalid Telegram URI: $uri")
        fileId = pathParts[3].toIntOrNull()
            ?: throw IOException("Invalid fileId in URI: $uri")

        val fileInfo = blockingGetFileInfo(fileId)
            ?: throw IOException("TDLib GetFile returned null for $fileId")
        totalSize = fileInfo.totalSize
        if (totalSize <= 0L) throw IOException("Invalid file size: $totalSize")

        Log.i(
            TAG,
            "OPEN sid=$streamSessionId fileId=$fileId size=${totalSize / 1048576}MB pos=${dataSpec.position}"
        )

        storageManager.maybeTrim(
            reason = "open:$fileId",
            protectedPath = fileInfo.localPath
        )

        requestLinearDownload(force = true)

        val filePath = waitForFile(fileId, fileInfo.localPath)
            ?: throw IOException("File not available on disk for fileId=$fileId")

        if (currentFile?.absolutePath != filePath) {
            raf?.close()
            currentFile = File(filePath)
            raf = RandomAccessFile(currentFile, "r")
        } else if (raf == null) {
            raf = RandomAccessFile(currentFile, "r")
        }

        position = dataSpec.position
        bytesRemaining = totalSize - dataSpec.position
        lastLogMs = System.currentTimeMillis()
        lastLogBytes = position
        refreshDownloadRange(force = true)

        if (shouldUseSeekMode(position)) {
            requestSeekDownload(position)
        } else {
            requestLinearDownload(force = false)
        }

        return bytesRemaining
    }

    @Throws(IOException::class)
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (closed || bytesRemaining <= 0) return -1

        var deadline = System.currentTimeMillis() + READ_TIMEOUT_MS

        while (true) {
            if (closed) return -1

            maybeRefreshFilePath()
            refreshDownloadRange(force = false)

            val fileLen = currentFile?.length() ?: 0L
            val inContiguousWindow = position >= downloadedRangeStart && position < downloadedRangeEndExclusive
            val contiguousAvailable = if (inContiguousWindow) {
                (downloadedRangeEndExclusive - position).coerceAtLeast(0L)
            } else {
                0L
            }
            val fileBoundedAvailable = (fileLen - position).coerceAtLeast(0L)
            val available = minOf(contiguousAvailable, fileBoundedAvailable)

            if (available <= 0) {
                if (shouldUseSeekMode(position) || shouldStayInSeekMode(position)) {
                    requestSeekDownload(position)
                } else {
                    requestLinearDownload(force = false)
                }
            }

            if (available > 0) {
                val toRead = minOf(length.toLong(), available, bytesRemaining).toInt()
                raf?.seek(position)
                val bytesRead = raf?.read(buffer, offset, toRead) ?: -1
                if (bytesRead > 0) {
                    position += bytesRead
                    bytesRemaining -= bytesRead
                    logProgress()
                    return bytesRead
                }
            }

            if (bytesRemaining <= 0) {
                Log.i(TAG, "READ EOF sid=$streamSessionId fileId=$fileId")
                return -1
            }

            if (isDownloadCompleteCached()) {
                Log.i(TAG, "READ EOF sid=$streamSessionId fileId=$fileId (download complete)")
                return -1
            }

            if (System.currentTimeMillis() >= deadline) {
                val completed = isDownloadCompleteCached(forceRefresh = true)
                if (completed) {
                    Log.i(TAG, "READ EOF sid=$streamSessionId fileId=$fileId (download complete after wait)")
                    return -1
                }
                Log.w(
                    TAG,
                    "READ WAIT sid=$streamSessionId fileId=$fileId pos=${position / 1048576}MB disk=${fileLen / 1048576}MB range=${downloadedRangeStart / 1048576}MB..${downloadedRangeEndExclusive / 1048576}MB mode=${currentModeName()} reissues=${currentReissueCount()}"
                )
                deadline = System.currentTimeMillis() + READ_TIMEOUT_MS
            }

            Thread.sleep(POLL_DATA_MS)
        }
    }

    @Throws(IOException::class)
    override fun close() {
        if (closed) return
        closed = true
        runCatching { raf?.close() }
        raf = null
        Log.i(
            TAG,
            "CLOSE sid=$streamSessionId fileId=$fileId pos=${position / 1048576}MB/${totalSize / 1048576}MB mode=${currentModeName()} reissues=${currentReissueCount()}"
        )
        cleanupExpiredDownloadState()
    }

    // ── Download management ───────────────────────────────────────────────

    private fun shouldUseSeekMode(targetPos: Long, knownContiguousEnd: Long = downloadedRangeEndExclusive): Boolean {
        if (targetPos <= 0L) return false
        val gap = targetPos - knownContiguousEnd
        return gap > SEEK_GAP_TRIGGER_BYTES
    }

    private fun shouldStayInSeekMode(targetPos: Long): Boolean {
        val state = downloadStateByFileId[fileId] ?: return false
        if (state.mode != DownloadMode.SEEK) return false
        if (System.currentTimeMillis() < seekLockUntilMs) return true
        return downloadedRangeEndExclusive < (targetPos + SEEK_EXIT_HYSTERESIS_BYTES)
    }

    private fun requestLinearDownload(force: Boolean) {
        val free = getUsableSpaceBytes()
        if (free > 0 && free < MIN_FREE_BYTES_SOFT) {
            val offset = position.coerceIn(0L, (totalSize - 1).coerceAtLeast(0L))
            val limit = minOf(LINEAR_WINDOW_BYTES, (totalSize - offset).coerceAtLeast(1L))
            Log.w(
                TAG,
                "LOW SPACE sid=$streamSessionId fileId=$fileId free=${free / 1048576}MB -> LINEAR WINDOW offset=${offset / 1048576}MB limit=${limit / 1048576}MB"
            )
            issueDownload(mode = DownloadMode.LINEAR, offset = offset, limit = limit, force = true)
            maybeCancelStalePendingDownload()
            return
        }
        issueDownload(mode = DownloadMode.LINEAR, offset = 0L, limit = 0L, force = force)
    }

    private fun requestSeekDownload(targetPos: Long) {
        val safeOffset = targetPos.coerceIn(0L, (totalSize - 1).coerceAtLeast(0L))
        val now = System.currentTimeMillis()
        if (now < seekLockUntilMs) {
            return
        }
        seekLockUntilMs = now + SEEK_LOCK_MS
        val free = getUsableSpaceBytes()
        val adjustedWindow = when {
            free <= 0L -> SEEK_WINDOW_BYTES
            free < MIN_FREE_BYTES_SOFT / 2L -> 32L * 1024L * 1024L
            free < MIN_FREE_BYTES_SOFT -> 64L * 1024L * 1024L
            else -> SEEK_WINDOW_BYTES
        }
        val seekLimit = minOf(adjustedWindow, (totalSize - safeOffset).coerceAtLeast(1L))
        issueDownload(
            mode = DownloadMode.SEEK,
            offset = safeOffset,
            limit = seekLimit,
            force = false
        )
        if (free > 0 && free < MIN_FREE_BYTES_SOFT) {
            maybeCancelStalePendingDownload()
        }
    }

    private fun issueDownload(mode: DownloadMode, offset: Long, limit: Long, force: Boolean) {
        if (fileId <= 0) return
        val now = System.currentTimeMillis()
        val state = downloadStateByFileId[fileId]
        val sameRequest = state != null && state.mode == mode && state.offset == offset && state.limit == limit
        val cooldownActive = state != null && (now - state.lastIssueMs) < DOWNLOAD_REISSUE_COOLDOWN_MS
        val closeOffset = state != null && kotlin.math.abs(state.offset - offset) < DOWNLOAD_REISSUE_MIN_DELTA_BYTES

        if (!force && state != null) {
            if (sameRequest && state.active) return
            if (cooldownActive && (sameRequest || (mode == DownloadMode.SEEK && closeOffset))) return
        }

        val newState = DownloadState(
            mode = mode,
            offset = offset,
            limit = limit,
            lastIssueMs = now,
            reissueCount = (state?.reissueCount ?: 0) + 1,
            active = true,
            updatedAtMs = now
        )
        downloadStateByFileId[fileId] = newState

        Log.i(
            TAG,
            "DOWNLOAD ISSUE sid=$streamSessionId fileId=$fileId mode=$mode offset=${offset / 1048576}MB limit=${if (limit == 0L) 0 else limit / 1048576}MB reissues=${newState.reissueCount}"
        )

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dl = TdApi.DownloadFile()
                dl.fileId = fileId
                dl.priority = DOWNLOAD_PRIORITY
                dl.offset = offset
                dl.limit = limit
                dl.synchronous = false
                clientManager.sendRequest(dl)

                val after = downloadStateByFileId[fileId]
                if (after != null && after.offset == offset && after.limit == limit && after.mode == mode) {
                    after.active = true
                    after.updatedAtMs = System.currentTimeMillis()
                }
            } catch (e: Exception) {
                Log.e(TAG, "DOWNLOAD FAILED sid=$streamSessionId fileId=$fileId mode=$mode", e)
                val after = downloadStateByFileId[fileId]
                if (after != null && after.offset == offset && after.limit == limit && after.mode == mode) {
                    after.active = false
                    after.updatedAtMs = System.currentTimeMillis()
                }
            }
        }
    }

    private fun cleanupExpiredDownloadState() {
        val now = System.currentTimeMillis()
        val iter = downloadStateByFileId.entries.iterator()
        while (iter.hasNext()) {
            val (_, state) = iter.next()
            if (now - state.updatedAtMs > DOWNLOAD_STATE_TTL_MS) {
                iter.remove()
            }
        }
    }

    private fun getUsableSpaceBytes(): Long {
        val f = currentFile ?: return -1L
        return runCatching { f.parentFile?.usableSpace ?: -1L }.getOrDefault(-1L)
    }

    private fun maybeCancelStalePendingDownload() {
        val now = System.currentTimeMillis()
        if (now - lastCancelMs < CANCEL_COOLDOWN_MS) return
        val state = downloadStateByFileId[fileId] ?: return
        if (state.mode != DownloadMode.LINEAR || state.limit != 0L) return
        lastCancelMs = now
        val localFileId = fileId
        Log.w(TAG, "LOW SPACE sid=$streamSessionId fileId=$localFileId cancel pending linear download to protect td.binlog")
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                val req = TdApi.CancelDownloadFile()
                req.fileId = localFileId
                req.onlyIfPending = true
                clientManager.sendRequest(req)
            }
        }
    }

    // ── Blocking helpers ──────────────────────────────────────────────────

    private fun blockingGetFileInfo(fileId: Int): FileInfo? {
        val cached = fileInfoCache[fileId]
        if (cached != null && System.currentTimeMillis() - cached.ts < INFO_CACHE_TTL_MS) {
            Log.i(TAG, "GetFile HIT CACHE fileId=$fileId size=${cached.totalSize / 1048576}MB")
            return cached
        }

        Log.i(TAG, "GetFile fileId=$fileId ...")
        var result: FileInfo? = null
        val latch = CountDownLatch(1)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val req = TdApi.GetFile()
                req.fileId = fileId
                val resp = clientManager.sendRequest(req)
                val file = resp as? TdApi.File
                if (file != null) {
                    val size = file.size.takeIf { it > 0 } ?: file.expectedSize
                    Log.i(TAG, "GetFile size=${size / 1048576}MB path=${file.local?.path} completed=${file.local?.isDownloadingCompleted}")
                    if (size > 0L) {
                        result = FileInfo(
                            totalSize = size,
                            localPath = file.local?.path?.takeIf { it.isNotEmpty() },
                            ts = System.currentTimeMillis()
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "GetFile FAILED fileId=$fileId", e)
            } finally {
                latch.countDown()
            }
        }
        latch.await(10_000, TimeUnit.MILLISECONDS)
        result?.let { fileInfoCache[fileId] = it }
        return result
    }

    private fun waitForFile(fileId: Int, localPath: String?): String? {
        if (localPath != null) {
            val f = File(localPath)
            if (f.exists() && f.canRead()) return localPath
        }

        val deadline = System.currentTimeMillis() + FILE_APPEAR_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val path = runBlocking {
                try {
                    val file = clientManager.sendRequest(
                        TdApi.GetFile(fileId)
                    ) as? TdApi.File
                    file?.local?.path?.takeIf { it.isNotEmpty() }
                } catch (_: Exception) { null }
            }
            if (path != null) {
                val f = File(path)
                if (f.exists() && f.canRead()) return path
            }
            Thread.sleep(500L)
        }
        Log.w(TAG, "waitForFile timed out fileId=$fileId")
        return null
    }

    private fun maybeRefreshFilePath() {
        if (fileId <= 0 || closed) return
        val now = System.currentTimeMillis()
        if (now - lastPathRefreshMs < PATH_REFRESH_INTERVAL_MS) return
        lastPathRefreshMs = now
        runCatching {
            val file = runBlocking { clientManager.sendRequest(TdApi.GetFile(fileId)) as? TdApi.File }
            val newPath = file?.local?.path?.takeIf { it.isNotEmpty() } ?: return
            if (currentFile?.absolutePath == newPath && raf != null) return
            val newFile = File(newPath)
            if (!newFile.exists() || !newFile.canRead()) return
            runCatching { raf?.close() }
            currentFile = newFile
            raf = RandomAccessFile(newFile, "r")
            runCatching { raf?.seek(position) }
            Log.i(TAG, "PATH REFRESH sid=$streamSessionId fileId=$fileId path=$newPath")
        }
    }

    private fun refreshDownloadRange(force: Boolean) {
        if (fileId <= 0 || closed) return
        val now = System.currentTimeMillis()
        if (!force && now - lastRangeCheckMs < RANGE_CHECK_INTERVAL_MS) return
        lastRangeCheckMs = now
        runCatching {
            val file = runBlocking { clientManager.sendRequest(TdApi.GetFile(fileId)) as? TdApi.File } ?: return
            val local = file.local ?: return
            val start = local.downloadOffset.coerceAtLeast(0L)
            val end = (start + local.downloadedPrefixSize).coerceAtLeast(start)
            downloadedRangeStart = start
            downloadedRangeEndExclusive = end
            downloadCompletedCache = local.isDownloadingCompleted
            downloadActiveCache = local.isDownloadingActive
        }
    }

    private fun isDownloadCompleteCached(forceRefresh: Boolean = false): Boolean {
        val now = System.currentTimeMillis()
        if (!forceRefresh && now - lastCompletionCheckMs < COMPLETION_CHECK_INTERVAL_MS) {
            return downloadCompletedCache
        }
        lastCompletionCheckMs = now
        downloadCompletedCache = isDownloadComplete()
        return downloadCompletedCache
    }

    private fun isDownloadComplete(): Boolean {
        var result = false
        val latch = CountDownLatch(1)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val file = clientManager.sendRequest(
                    TdApi.GetFile(fileId)
                ) as? TdApi.File
                result = file?.local?.isDownloadingCompleted == true
            } catch (_: Exception) {
            } finally {
                latch.countDown()
            }
        }
        latch.await(3_000, TimeUnit.MILLISECONDS)
        return result
    }

    // ── Logging ───────────────────────────────────────────────────────────

    private fun logProgress() {
        val now = System.currentTimeMillis()
        if (now - lastLogMs < 3_000) return
        val deltaBytes = position - lastLogBytes
        val deltaSec = (now - lastLogMs) / 1000.0
        val speed = if (deltaSec > 0) deltaBytes / 1024.0 / deltaSec else 0.0
        val fileLen = currentFile?.length() ?: 0L
        val ahead = fileLen - position
        Log.i(
            TAG,
            "READ sid=$streamSessionId fileId=$fileId mode=${currentModeName()} pos=${position / 1048576}MB disk=${fileLen / 1048576}MB range=${downloadedRangeStart / 1048576}MB..${downloadedRangeEndExclusive / 1048576}MB active=$downloadActiveCache ahead=${ahead / 1048576}MB speed=${String.format(Locale.US, "%.0f", speed)}KB/s reissues=${currentReissueCount()}"
        )
        lastLogMs = now
        lastLogBytes = position
    }

    private fun currentModeName(): String = downloadStateByFileId[fileId]?.mode?.name ?: "NA"

    private fun currentReissueCount(): Int = downloadStateByFileId[fileId]?.reissueCount ?: 0

    // ── Factory ───────────────────────────────────────────────────────────

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface TelegramClientEntryPoint {
        fun telegramClientManager(): TelegramClientManager
        fun telegramStorageManager(): TelegramStorageManager
    }

    class Factory(private val context: Context) : DataSource.Factory {
        override fun createDataSource(): DataSource {
            val appContext = context.applicationContext
            val entryPoint = EntryPointAccessors.fromApplication(
                appContext, TelegramClientEntryPoint::class.java
            )
            return TelegramDataSource(
                clientManager = entryPoint.telegramClientManager(),
                storageManager = entryPoint.telegramStorageManager()
            )
        }
    }
}
