package com.nuvio.tv.core.telegram

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Minimal helper for building Telegram streaming URLs and pre-starting downloads.
 *
 * The actual file reading is handled by [TelegramDataSource] (Nagram pattern),
 * which reads directly from TDLib's temp files via RandomAccessFile.
 * No HTTP proxy (NanoHTTPD) is used.
 *
 * Download ownership now belongs exclusively to [TelegramDataSource] so storage
 * policy (trim/cap) stays centralized and predictable.
 */
@Singleton
class TelegramStreamProxy @Inject constructor() {

    val port: Int get() = -1
    val isRunning: Boolean get() = false

    fun buildStreamUrl(chatId: Long, messageId: Long, fileId: Int): String {
        return "http://127.0.0.1:0/tg/$chatId/$messageId/$fileId"
    }

    fun start() { }
    fun stop() { }
}
