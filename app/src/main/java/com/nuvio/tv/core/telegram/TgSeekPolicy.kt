// TG-ONLY-FILE: Telegram module — keep whole file on upstream merge
package com.nuvio.tv.core.telegram

/**
 * Política pura (sin Android, sin TDLib, testeable en JVM) de la ventana
 * disciplinada: dónde va el cursor y cuándo se permite moverlo.
 */
object TgSeekPolicy {

    data class Window(val start: Long, val endExclusive: Long) {
        val size: Long get() = (endExclusive - start).coerceAtLeast(0L)
    }

    /**
     * Ventana deseada para [pos]: conserva cola trasera y pide `ahead`
     * adaptativo al espacio libre. Si el fichero cabe en la ventana, la
     * ventana ES el fichero (el "lineal" emerge solo, sin código especial).
     */
    fun windowFor(
        pos: Long,
        totalSize: Long,
        freeBytes: Long,
        backKept: Long = TgWindowConfig.WINDOW_BACK_KEPT_BYTES,
        minAhead: Long = TgWindowConfig.WINDOW_MIN_AHEAD_BYTES,
        maxAhead: Long = TgWindowConfig.WINDOW_MAX_AHEAD_BYTES
    ): Window {
        if (totalSize <= 0L) return Window(0L, 0L)
        val safePos = pos.coerceIn(0L, (totalSize - 1).coerceAtLeast(0L))
        val ahead = if (freeBytes <= 0L) {
            maxAhead
        } else {
            (freeBytes / 4).coerceIn(minAhead, maxAhead)
        }
        // El fichero cabe en la ventana: cubrirlo entero (una sola emisión).
        if (totalSize <= backKept + ahead) return Window(0L, totalSize)
        val start = (safePos - backKept).coerceAtLeast(0L)
        val end = (safePos + ahead).coerceAtMost(totalSize)
        return Window(start, end)
    }

    /**
     * ¿Hay que mover el cursor? Solo si [pos] sale de
     * [cursor.start - backKept, cursor.end + hysteresis]. Cursor null (sin
     * emisión previa) siempre requiere emisión.
     */
    fun needsReposition(
        pos: Long,
        cursor: Window?,
        backKept: Long = TgWindowConfig.WINDOW_BACK_KEPT_BYTES,
        hysteresis: Long = TgWindowConfig.WINDOW_HYSTERESIS_BYTES
    ): Boolean {
        if (cursor == null) return true
        if (pos < cursor.start - backKept) return true
        if (pos > cursor.endExclusive + hysteresis) return true
        return false
    }

    /** Lectura en la región de cola: candidata a sonda moov-at-end. */
    fun isTailRegion(
        pos: Long,
        totalSize: Long,
        tailRegion: Long = TgWindowConfig.TAIL_REGION_BYTES
    ): Boolean {
        if (totalSize <= 0L) return false
        return pos >= (totalSize - tailRegion).coerceAtLeast(0L)
    }

    /** Ventana de sonda de cola: últimos [probeBytes] del fichero. */
    fun tailProbeWindow(
        totalSize: Long,
        probeBytes: Long = TgWindowConfig.TAIL_PROBE_BYTES
    ): Window {
        if (totalSize <= 0L) return Window(0L, 0L)
        val start = (totalSize - probeBytes).coerceAtLeast(0L)
        return Window(start, totalSize)
    }

    /**
     * ¿Puede este motivo mover el cursor ahora?
     * - open especulativo: solo si aún no hay cursor (primera emisión).
     * - lector sostenido: si no hay cursor, o fuera de cooldown.
     * Así la ráfaga de sniff (head/tail/mid en segundos) colapsa a ≤2
     * emisiones y el lector estable gobierna el cursor.
     */
    fun mayReposition(
        isOpenPhase: Boolean,
        hasCursor: Boolean,
        nowMs: Long,
        lastIssueMs: Long,
        cooldownMs: Long = TgWindowConfig.REPOSITION_COOLDOWN_MS
    ): Boolean {
        if (!hasCursor) return true
        if (isOpenPhase) return false
        return nowMs - lastIssueMs >= cooldownMs
    }

    // ── Decisiones de espacio (puras, testeables) ─────────────────────
    /** Freno: pausar la descarga antes de llenar el disco del dispositivo. */
    fun shouldBrake(
        freeBytes: Long,
        brakeBytes: Long = TgWindowConfig.SPACE_BRAKE_BYTES
    ): Boolean = freeBytes in 1 until brakeBytes

    /** Reanudar tras freno solo con margen de sobra (histéresis anti-flapping). */
    fun shouldResume(
        freeBytes: Long,
        resumeBytes: Long = TgWindowConfig.SPACE_RESUME_BYTES
    ): Boolean = freeBytes >= resumeBytes

    /**
     * Rotación: el temp acumulado supera el presupuesto con poco libre.
     * Rota (cancel + borrar temp + reemitir en playhead) para acotar disco.
     */
    fun shouldRotate(
        freeBytes: Long,
        sessionFileBytes: Long,
        rotateFreeBytes: Long = TgWindowConfig.SPACE_ROTATE_FREE_BYTES,
        rotateFileBytes: Long = TgWindowConfig.SPACE_ROTATE_FILE_BYTES
    ): Boolean = freeBytes in 1 until rotateFreeBytes && sessionFileBytes >= rotateFileBytes
}
