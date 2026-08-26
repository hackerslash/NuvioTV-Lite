package com.nuvio.tv.core.telegram

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Minimal helper for building Telegram streaming URLs and pre-starting downloads.
 *
 * The actual file reading is handled by [TelegramDataSource] (Nagram pattern),
 * which reads directly from TDLib's temp files via RandomAccessFile.
 * No HTTP proxy (NanoHTTPD) is used.
 *
 * When a stream URL is built, we immediately fire a DownloadFile(limit=0) so data
 * is already on disk by the time ExoPlayer calls TelegramDataSource.open().
 */
@Singleton
class TelegramStreamProxy @Inject constructor(
    @ApplicationContext private val context: Context,
    private val clientManager: TelegramClientManager
) {
    companion object {
        private const val TAG = "TelegramProxy"
        private const val PRE_START_PRIORITY = 32
        private val preStarted = ConcurrentHashMap<Int, AtomicBoolean>()
    }

    val port: Int get() = -1
    val isRunning: Boolean get() = false

    fun buildStreamUrl(chatId: Long, messageId: Long, fileId: Int): String {
        preStartDownload(fileId)
        return "http://127.0.0.1:0/tg/$chatId/$messageId/$fileId"
    }

    /**
     * Fire a single DownloadFile(limit=0, priority=1000) for the given fileId.
     * This runs in background so the download starts immediately while ExoPlayer
     * is still setting up. Subsequent calls for the same fileId are no-ops.
     */
    private fun preStartDownload(fileId: Int) {
        val already = preStarted.getOrPut(fileId) { AtomicBoolean(false) }
        if (!already.compareAndSet(false, true)) return
        Log.i(TAG, "PRE-START download fileId=$fileId (priority=$PRE_START_PRIORITY)")
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dl = TdApi.DownloadFile()
                dl.fileId = fileId
                dl.priority = PRE_START_PRIORITY
                dl.offset = 0
                dl.limit = 0
                dl.synchronous = false
                clientManager.sendRequest(dl)
            } catch (e: Exception) {
                Log.e(TAG, "PRE-START FAILED fileId=$fileId", e)
                already.set(false)
            }
        }
    }

    fun start() { }
    fun stop() { }
}
