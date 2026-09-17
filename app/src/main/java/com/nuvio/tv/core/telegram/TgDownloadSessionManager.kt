// TG-ONLY-FILE: Telegram module — keep whole file on upstream merge
package com.nuvio.tv.core.telegram

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.drinkless.tdlib.TdApi

/** Stall acotado: entra el retry del loader Exo + auto-rebuild con posición. */
class TgStallTimeoutException(message: String) : IOException(message)

/** Espacio insuficiente incluso tras evicción: error visible, no spinner. */
class TgLowStorageException(message: String) : IOException(message)

/** Sesión rota (fichero evicted, TDLib caído): reintento limpio. */
class TgSessionException(message: String) : IOException(message)

/**
 * Único dueño del cursor de descarga TDLib por fileId (ventana disciplinada).
 *
 * - Emite DownloadFile como máximo UNA vez por motivo (apertura, salida de
 *   ventana + histéresis, sonda de cola). Emisiones concurrentes se coalescen
 *   (single-flight con generation): el doble open del extractor (sniff+play)
 *   produce una sola emisión.
 * - Progreso dirigido por UpdateFile push (ver [onFileUpdate]); el polling
 *   GetFile es solo fallback throttled, nunca en el path caliente.
 * - La sesión sobrevive a close(): TTL 10 min con lectores a cero (re-entrada
 *   gratis) o cancelación inmediata si el disco está bajo el piso.
 */
@Singleton
class TgDownloadSessionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val clientManager: TelegramClientManager,
    private val storageManager: TelegramStorageManager
) {
    companion object {
        private const val TAG = "TgSession"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private data class Meta(val totalSize: Long, val path: String?, val ts: Long)
    private val metaCache = ConcurrentHashMap<Int, Meta>()

    private inner class Session(val fileId: Int) {
        val lock = Object()
        var totalSize: Long = 0L
        var localPath: String? = null
        var cursor: TgSeekPolicy.Window? = null
        var generation: Long = 0L
        var verifiedStart: Long = 0L
        var verifiedEnd: Long = 0L
        var downloadActive: Boolean = false
        var completed: Boolean = false
        var downloadedBytes: Long = 0L
        var lastIngressBytes: Long = 0L
        var lastIngressMs: Long = 0L
        var lastUpdateMs: Long = 0L
        var readers: Int = 0
        var lastUsedMs: Long = System.currentTimeMillis()
        var issueInFlight: Boolean = false
        var pendingWindow: TgSeekPolicy.Window? = null
        var pendingReason: String? = null
        var lastAvailCheckMs: Long = 0L
        var issueCount: Int = 0
        var lastIssueMs: Long = 0L
        var pinned: Boolean = false
        // Freno de espacio + rotación.
        var braked: Boolean = false
        var epoch: Long = 0L
        var lastPos: Long = 0L
        var lastSpaceCheckMs: Long = 0L
        var lastEvictMs: Long = 0L
        var rotateCount: Int = 0
    }

    private val sessions = ConcurrentHashMap<Int, Session>()

    init {
        clientManager.addFileUpdateListener(::onFileUpdate)
        scope.launch {
            while (true) {
                try {
                    kotlinx.coroutines.delay(TgWindowConfig.SESSION_SWEEP_INTERVAL_MS)
                    sweepIdleSessions()
                } catch (_: Exception) {
                }
            }
        }
    }

    // ── UpdateFile push ──────────────────────────────────────────────

    fun onFileUpdate(file: TdApi.File) {
        val s = sessions[file.id] ?: return
        val local = file.local ?: return
        synchronized(s.lock) {
            val path = local.path?.takeIf { it.isNotEmpty() }
            if (path != null) s.localPath = path
            s.verifiedStart = local.downloadOffset.coerceAtLeast(0)
            s.verifiedEnd = (local.downloadOffset + local.downloadedPrefixSize)
                .coerceAtLeast(s.verifiedStart)
            s.downloadActive = local.isDownloadingActive
            s.completed = local.isDownloadingCompleted
            val now = System.currentTimeMillis()
            if (local.downloadedSize > s.downloadedBytes) {
                s.lastIngressBytes = s.downloadedBytes
                s.lastIngressMs = s.lastUpdateMs
                s.downloadedBytes = local.downloadedSize
                s.lastUpdateMs = now
            }
            (s.lock as Object).notifyAll()
        }
    }

    // ── Apertura (path caliente del loader Exo) ──────────────────────

    data class OpenHandle(
        val fileId: Int,
        val totalSize: Long,
        val filePath: String,
        val freeBytes: Long,
        val totalStorageBytes: Long
    )

    /**
     * Registra un lector en [pos]. Emite como máximo UNA vez (si el cursor no
     * cubre). Bloquea de forma acotada hasta que el fichero existe en disco.
     */
    @Throws(IOException::class)
    fun openReader(fileId: Int, pos: Long): OpenHandle {
        val meta = blockingMeta(fileId)
            ?: throw TgSessionException("TDLib GetFile null for $fileId")
        if (meta.totalSize <= 0L) throw TgSessionException("Invalid size for $fileId")

        val free = context.filesDir.usableSpace
        val totalStorage = context.filesDir.totalSpace
        val s = sessions.getOrPut(fileId) { Session(fileId) }

        // F0 metrología: confirma en qué mundo de disco vivimos.
        Log.i(
            TAG,
            "OPEN fileId=$fileId size=${meta.totalSize / 1048576}MB pos=${pos / 1048576}MB " +
                "free=${free / 1048576}MB total=${totalStorage / 1048576}MB"
        )

        // Piso de espacio: evict + recheck, si no error visible.
        if (free in 1 until TgWindowConfig.MIN_FREE_BYTES_FLOOR) {
            storageManager.evictAllExcept(pinnedPaths())
            val freeAfter = context.filesDir.usableSpace
            Log.w(TAG, "LOWSPACE fileId=$fileId free=${free / 1048576}MB afterEvict=${freeAfter / 1048576}MB")
            if (freeAfter in 1 until TgWindowConfig.MIN_FREE_BYTES_FLOOR) {
                throw TgLowStorageException(
                    "Espacio insuficiente: ${freeAfter / 1048576}MB libres. Libera espacio o usa «Liberar caché TG»."
                )
            }
        }

        synchronized(s.lock) {
            s.totalSize = meta.totalSize
            if (meta.path != null) s.localPath = meta.path
            s.readers++
            s.lastUsedMs = System.currentTimeMillis()
            if (!s.pinned) {
                s.localPath?.let { storageManager.pin(it) }
                s.pinned = true
            }
        }

        ensureCursorFor(s, pos, reason = "open", isOpenPhase = true)

        val path = waitForFile(s, meta.path)
            ?: throw TgSessionException("File not on disk for $fileId")
        return OpenHandle(fileId, meta.totalSize, path, free, totalStorage)
    }

    /**
     * Espera acotada a que [pos, pos+length) sea legible. Devuelve bytes
     * disponibles (≥1) o -1 en EOF. Lanza TgStallTimeoutException si expira.
     */
    @Throws(IOException::class)
    fun awaitReadable(fileId: Int, pos: Long, length: Int, closed: () -> Boolean): Long {
        val s = sessions[fileId] ?: throw TgSessionException("No session for $fileId")
        val deadline = System.currentTimeMillis() + TgWindowConfig.READ_STALL_TIMEOUT_MS
        var waitedLog = false
        while (true) {
            if (closed()) return -1
            val total: Long
            synchronized(s.lock) {
                total = s.totalSize
                s.lastPos = pos
            }
            if (total > 0 && pos >= total) return -1

            // 0. Freno de espacio (throttled): nunca llenar el disco.
            spaceCheck(s)

            // 1. Vía rápida: prefijo verificado por push.
            synchronized(s.lock) {
                if (pos >= s.verifiedStart && pos < s.verifiedEnd) {
                    val avail = minOf(length.toLong(), s.verifiedEnd - pos, (total - pos).coerceAtLeast(0L))
                    if (avail > 0) return avail
                }
                if (s.completed && total > 0) {
                    val avail = minOf(length.toLong(), (total - pos).coerceAtLeast(0L))
                    if (avail > 0) return avail
                    return -1
                }
            }

            // 2. Sonda de disponibilidad (no mueve el cursor): sirve regiones
            // ya en disco (ventanas previas, sonda de cola) sin reemitir.
            val avail = probeAvailability(s, pos)
            if (avail > 0) {
                val cap = if (total > 0) (total - pos).coerceAtLeast(0L) else avail
                return minOf(length.toLong(), avail, cap)
            }

            // 3. Cursor productivo: solo el lector sostenido lo mueve, con
            // cooldown (los opens especulativos del extractor nunca emiten).
            ensureCursorFor(s, pos, reason = "wait", isOpenPhase = false)

            val now = System.currentTimeMillis()
            if (now >= deadline) {
                val snap = snapshotLocked(s)
                val brakedNow = synchronized(s.lock) { s.braked }
                if (brakedNow) {
                    throw TgLowStorageException(
                        "Espacio casi lleno: descarga en pausa en ${pos / 1048576}MB. " +
                            "Libera espacio o usa «Liberar caché TG» y reintenta."
                    )
                }
                throw TgStallTimeoutException(
                    "TG stall 30s fileId=$fileId pos=${pos / 1048576}MB " +
                        "range=${snap.verifiedStartMb}..${snap.verifiedEndMb}MB " +
                        "active=${snap.active} issues=${snap.issues}"
                )
            }
            if (!waitedLog && now - (deadline - TgWindowConfig.READ_STALL_TIMEOUT_MS) > 5_000) {
                waitedLog = true
                val snap = snapshotLocked(s)
                Log.i(
                    TAG,
                    "STALLWAIT fileId=$fileId pos=${pos / 1048576}MB " +
                        "range=${snap.verifiedStartMb}..${snap.verifiedEndMb}MB active=${snap.active}"
                )
            }
            synchronized(s.lock) {
                try {
                    (s.lock as Object).wait(TgWindowConfig.WAIT_QUANTUM_MS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw java.io.InterruptedIOException("awaitReadable interrupted fileId=$fileId")
                }
            }
        }
    }

    fun currentPath(fileId: Int): String? = sessions[fileId]?.let {
        synchronized(it.lock) { it.localPath }
    }

    fun releaseReader(fileId: Int) {
        val s = sessions[fileId] ?: return
        var dropNow = false
        synchronized(s.lock) {
            s.readers = (s.readers - 1).coerceAtLeast(0)
            s.lastUsedMs = System.currentTimeMillis()
            (s.lock as Object).notifyAll()
            if (s.readers == 0 && context.filesDir.usableSpace in 1 until TgWindowConfig.MIN_FREE_BYTES_FLOOR) {
                dropNow = true
            }
        }
        Log.i(TAG, "CLOSE fileId=$fileId readers=${s.readers} issues=${s.issueCount}")
        // Con poco disco no hay TTL: se cancela Y se borra el temp ahora mismo
        // para devolver espacio. Re-entrar re-descarga solo la ventana.
        if (dropNow) dropSessionFile(s, reason = "lowspace-release")
    }

    /** Cancela, borra el temp, des-pinea y olvida la sesión (disco crítico). */
    private fun dropSessionFile(s: Session, reason: String) {
        sessions.remove(s.fileId, s)
        val path = synchronized(s.lock) { s.localPath }
        cancelDownloadSync(s.fileId, onlyIfPending = false)
        path?.let { p ->
            runCatching { File(p).delete() }
            runCatching { storageManager.unpin(p) }
        }
        synchronized(s.lock) {
            s.pinned = false
            (s.lock as Object).notifyAll()
        }
        Log.i(TAG, "DROP fileId=${s.fileId} reason=$reason issues=${s.issueCount}")
    }

    /** Epoch del cursor/fichero: los lectores reabren el raf al cambiar. */
    fun sessionEpoch(fileId: Int): Long = sessions[fileId]?.let {
        synchronized(it.lock) { it.epoch }
    } ?: -1L

    // ── Freno de espacio + rotación ────────────────────────────────

    /**
     * Throttled (~1s). Frena la descarga con disco crítico, reanuda con
     * margen, rota el temp acumulado y evicta otras descargas. Nunca deja que
     * el temp TDLib llene el disco del dispositivo.
     */
    private fun spaceCheck(s: Session) {
        val now = System.currentTimeMillis()
        synchronized(s.lock) {
            if (now - s.lastSpaceCheckMs < 1_000L) return
            s.lastSpaceCheckMs = now
        }
        val free = context.filesDir.usableSpace

        // Freno / reanudación.
        val braked = synchronized(s.lock) { s.braked }
        if (!braked && TgSeekPolicy.shouldBrake(free)) {
            cancelDownloadSync(s.fileId, onlyIfPending = false)
            synchronized(s.lock) { s.braked = true }
            Log.w(TAG, "BRAKE fileId=${s.fileId} free=${free / 1048576}MB (descarga en pausa)")
        } else if (braked && TgSeekPolicy.shouldResume(free)) {
            synchronized(s.lock) {
                s.braked = false
                s.cursor = null // fuerza reemisión única en playhead
            }
            Log.i(TAG, "RESUME fileId=${s.fileId} free=${free / 1048576}MB")
        }

        // Rotación del temp acumulado (solo con lectores activos).
        val readers = synchronized(s.lock) { s.readers }
        if (readers > 0 && !synchronized(s.lock) { s.braked }) {
            val path = synchronized(s.lock) { s.localPath }
            val fileBytes = path?.let { runCatching { File(it).length() }.getOrDefault(0L) } ?: 0L
            if (TgSeekPolicy.shouldRotate(free, fileBytes)) {
                rotateSession(s, fileBytes, free)
            }
        }

        // Auto-evict de OTRAS descargas con poco libre (throttled 15s).
        val lastEvict = synchronized(s.lock) { s.lastEvictMs }
        if (free in 1 until TgWindowConfig.SPACE_EVICT_FREE_BYTES &&
            now - lastEvict > TgWindowConfig.SPACE_EVICT_MIN_INTERVAL_MS
        ) {
            synchronized(s.lock) { s.lastEvictMs = now }
            val freed = storageManager.evictAllExcept(pinnedPaths())
            if (freed > 0L) Log.i(TAG, "EVICT-OTHERS freed=${freed / 1048576}MB")
        }
    }

    /**
     * Acota el temp en disco: cancela, borra el temp y reemite la ventana en
     * el playhead. Los lectores reabren el raf por cambio de epoch.
     */
    private fun rotateSession(s: Session, fileBytes: Long, free: Long) {
        val pos = synchronized(s.lock) { s.lastPos }
        val path = synchronized(s.lock) { s.localPath }
        Log.w(
            TAG,
            "ROTATE fileId=${s.fileId} temp=${fileBytes / 1048576}MB free=${free / 1048576}MB pos=${pos / 1048576}MB"
        )
        cancelDownloadSync(s.fileId, onlyIfPending = false)
        path?.let { runCatching { File(it).delete() } }
        synchronized(s.lock) {
            s.cursor = null
            s.verifiedStart = 0L
            s.verifiedEnd = 0L
            s.downloadedBytes = 0L
            s.completed = false
            s.epoch++
            s.rotateCount++
            (s.lock as Object).notifyAll()
        }
        // Reemisión única en el playhead (nuevo temp acotado).
        ensureCursorFor(s, pos, reason = "rotate", isOpenPhase = false)
    }

    private fun cancelDownloadSync(fileId: Int, onlyIfPending: Boolean) {
        try {
            runBlocking {
                clientManager.sendRequest(
                    TdApi.CancelDownloadFile().apply {
                        this.fileId = fileId
                        this.onlyIfPending = onlyIfPending
                    },
                    timeoutMs = 5_000L
                )
            }
        } catch (_: Exception) {
        }
    }

    // ── Cursor (único emisor) ─────────────────────────────────────────

    private fun ensureCursorFor(s: Session, pos: Long, reason: String, isOpenPhase: Boolean) {
        val window: TgSeekPolicy.Window
        synchronized(s.lock) {
            val total = s.totalSize
            if (total <= 0L) return
            if (!TgSeekPolicy.needsReposition(pos, s.cursor)) return
            if (!TgSeekPolicy.mayReposition(
                    isOpenPhase = isOpenPhase,
                    hasCursor = s.cursor != null,
                    nowMs = System.currentTimeMillis(),
                    lastIssueMs = s.lastIssueMs
                )
            ) return
            val free = context.filesDir.usableSpace
            window = TgSeekPolicy.windowFor(pos, total, free)
        }
        issueWindow(s, window, reason)
    }

    private fun issueWindow(s: Session, window: TgSeekPolicy.Window, reason: String) {
        synchronized(s.lock) {
            if (s.issueInFlight) {
                // Single-flight: coalesce al último deseo, sin emitir de más.
                s.pendingWindow = window
                s.pendingReason = reason
                return
            }
            s.issueInFlight = true
            s.generation++
            s.cursor = window
            s.lastIssueMs = System.currentTimeMillis()
            s.lastUsedMs = s.lastIssueMs
        }
        val gen = s.generation
        val offset = window.start
        val limit = window.size.coerceAtLeast(1L)
        scope.launch {
            try {
                val dl = TdApi.DownloadFile()
                dl.fileId = s.fileId
                dl.priority = TgWindowConfig.DOWNLOAD_PRIORITY
                dl.offset = offset
                dl.limit = limit
                dl.synchronous = false
                clientManager.sendRequest(dl)
                synchronized(s.lock) {
                    s.issueCount++
                    s.lastUsedMs = System.currentTimeMillis()
                }
                Log.i(
                    TAG,
                    "ISSUE fileId=${s.fileId} gen=$gen reason=$reason " +
                        "offset=${offset / 1048576}MB limit=${limit / 1048576}MB issues=${s.issueCount}"
                )
            } catch (e: Exception) {
                Log.e(TAG, "ISSUE FAILED fileId=${s.fileId} gen=$gen reason=$reason", e)
            } finally {
                var next: TgSeekPolicy.Window? = null
                var nextReason: String? = null
                synchronized(s.lock) {
                    s.issueInFlight = false
                    // Solo el pendiente más reciente; el resto caducó.
                    next = s.pendingWindow
                    nextReason = s.pendingReason
                    s.pendingWindow = null
                    s.pendingReason = null
                    (s.lock as Object).notifyAll()
                }
                if (next != null) issueWindow(s, next!!, (nextReason ?: "coalesced") + "+coalesced")
            }
        }
    }

    private fun probeAvailability(s: Session, pos: Long): Long {
        val now = System.currentTimeMillis()
        synchronized(s.lock) {
            if (now - s.lastAvailCheckMs < TgWindowConfig.AVAIL_CHECK_MIN_INTERVAL_MS) return -1L
            s.lastAvailCheckMs = now
        }
        return try {
            val resp = runBlocking {
                clientManager.sendRequest(
                    TdApi.GetFileDownloadedPrefixSize(s.fileId, pos),
                    timeoutMs = 5_000L
                ) as? TdApi.FileDownloadedPrefixSize
            }
            (resp?.size ?: 0L).coerceAtLeast(0L)
        } catch (_: Exception) {
            -1L
        }
    }

    // ── Meta + fichero ───────────────────────────────────────────────

    private fun blockingMeta(fileId: Int): Meta? {
        val cached = metaCache[fileId]
        if (cached != null && System.currentTimeMillis() - cached.ts < TgWindowConfig.META_CACHE_TTL_MS) {
            return cached
        }
        var result: Meta? = null
        val latch = CountDownLatch(1)
        scope.launch {
            try {
                val file = clientManager.sendRequest(
                    TdApi.GetFile().apply { this.fileId = fileId },
                    timeoutMs = TgWindowConfig.META_TIMEOUT_MS
                ) as? TdApi.File
                if (file != null) {
                    val size = file.size.takeIf { it > 0 } ?: file.expectedSize
                    if (size > 0L) {
                        result = Meta(size, file.local?.path?.takeIf { it.isNotEmpty() }, System.currentTimeMillis())
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "GetFile FAILED fileId=$fileId", e)
            } finally {
                latch.countDown()
            }
        }
        latch.await(TgWindowConfig.META_TIMEOUT_MS + 2_000L, TimeUnit.MILLISECONDS)
        result?.let { metaCache[fileId] = it }
        return result
    }

    private fun waitForFile(s: Session, hintPath: String?): String? {
        hintPath?.let { p ->
            val f = File(p)
            if (f.exists() && f.canRead()) return p
        }
        val deadline = System.currentTimeMillis() + TgWindowConfig.FILE_APPEAR_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val path = synchronized(s.lock) { s.localPath }
            if (path != null) {
                val f = File(path)
                if (f.exists() && f.canRead()) return path
            }
            if (Thread.currentThread().isInterrupted) return null
            synchronized(s.lock) {
                try {
                    (s.lock as Object).wait(500L)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return null
                }
            }
        }
        Log.w(TAG, "waitForFile timeout fileId=${s.fileId}")
        return null
    }

    // ── TTL / cancelación / caché ─────────────────────────────────────

    private fun sweepIdleSessions() {
        val now = System.currentTimeMillis()
        sessions.values.toList().forEach { s ->
            val idle = synchronized(s.lock) {
                s.readers == 0 && now - s.lastUsedMs > TgWindowConfig.SESSION_IDLE_TTL_MS
            }
            if (idle) cancelSession(s, reason = "ttl-idle")
        }
    }

    private fun cancelSession(s: Session, reason: String) {
        sessions.remove(s.fileId, s)
        synchronized(s.lock) {
            s.localPath?.let { runCatching { storageManager.unpin(it) } }
            s.pinned = false
            (s.lock as Object).notifyAll()
        }
        Log.i(TAG, "EVICT fileId=${s.fileId} reason=$reason issues=${s.issueCount}")
        scope.launch {
            runCatching {
                clientManager.sendRequest(
                    TdApi.CancelDownloadFile().apply {
                        fileId = s.fileId
                        onlyIfPending = false
                    }
                )
            }
        }
    }

    private fun pinnedPaths(): Set<String> {
        val out = HashSet<String>()
        sessions.values.forEach { s ->
            synchronized(s.lock) { s.localPath?.let { out.add(it) } }
        }
        return out
    }

    /** "Liberar caché TG": borra todo menos sesiones vivas. Devuelve MB. */
    fun clearCacheExceptActive(): Long {
        val freedBytes = storageManager.evictAllExcept(pinnedPaths())
        metaCache.clear()
        Log.i(TAG, "CLEAR-CACHE freed=${freedBytes / 1048576}MB")
        return freedBytes / 1048576L
    }

    // ── Telemetría ────────────────────────────────────────────────────

    data class Snapshot(
        val fileId: Int,
        val totalMb: Long,
        val verifiedStartMb: Long,
        val verifiedEndMb: Long,
        val downloadedMb: Long,
        val active: Boolean,
        val completed: Boolean,
        val issues: Int,
        val readers: Int,
        val ingressKBs: Long,
        val braked: Boolean,
        val epoch: Long,
        val rotates: Int,
        val freeMb: Long
    )

    fun snapshot(fileId: Int): Snapshot? {
        val s = sessions[fileId] ?: return null
        return snapshotLocked(s)
    }

    private fun snapshotLocked(s: Session): Snapshot {
        synchronized(s.lock) {
            val now = System.currentTimeMillis()
            val dtSec = ((now - s.lastIngressMs) / 1000.0).coerceAtLeast(0.5)
            val ingress = if (s.lastUpdateMs > 0) {
                ((s.downloadedBytes - s.lastIngressBytes) / 1024.0 / dtSec).toLong().coerceAtLeast(0L)
            } else 0L
            return Snapshot(
                fileId = s.fileId,
                totalMb = s.totalSize / 1048576L,
                verifiedStartMb = s.verifiedStart / 1048576L,
                verifiedEndMb = s.verifiedEnd / 1048576L,
                downloadedMb = s.downloadedBytes / 1048576L,
                active = s.downloadActive,
                completed = s.completed,
                issues = s.issueCount,
                readers = s.readers,
                ingressKBs = ingress,
                braked = s.braked,
                epoch = s.epoch,
                rotates = s.rotateCount,
                freeMb = context.filesDir.usableSpace / 1048576L
            )
        }
    }
}
