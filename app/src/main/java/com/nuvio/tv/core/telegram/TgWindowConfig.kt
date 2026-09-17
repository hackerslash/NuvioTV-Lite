// TG-ONLY-FILE: Telegram module — keep whole file on upstream merge
package com.nuvio.tv.core.telegram

/**
 * Ventana disciplinada: UN solo cursor de descarga por fileId que sigue al
 * playhead perezosamente. Sustituye al modelo lineal+seek con reemisiones.
 *
 * Reglas inviolables:
 * - Una sola emisión de DownloadFile por motivo (apertura, salida de ventana
 *   + histéresis, sonda moov). Los open() de sniff/seek/re-entry NUNCA emiten.
 * - Solo es legible el prefijo verificado [downloadOffset, +prefix) o los
 *   bytes confirmados por GetFileDownloadedPrefixSize (fuera hay basura).
 * - Waits acotados: el stall real se convierte en IOException para que entren
 *   los reintentos de ExoPlayer, nunca spinner infinito.
 */
object TgWindowConfig {
    /** Prioridad máxima TDLib para la descarga de reproducción. */
    const val DOWNLOAD_PRIORITY = 32

    /** Bytes traseros que la ventana conserva (seeks atrás pequeños gratis). */
    const val WINDOW_BACK_KEPT_BYTES = 16L * 1024L * 1024L

    /** Ventana adaptativa = clamp(free/4, MIN..MAX). */
    const val WINDOW_MIN_AHEAD_BYTES = 48L * 1024L * 1024L
    const val WINDOW_MAX_AHEAD_BYTES = 192L * 1024L * 1024L

    /** Histéresis: el cursor solo se mueve si pos sale de [start-back, end+hyst]. */
    const val WINDOW_HYSTERESIS_BYTES = 32L * 1024L * 1024L

    /**
     * Cooldown de reposición: el sondeo del extractor abre DataSources
     * especulativos (head/tail/mid) en ráfaga; solo el lector sostenido mueve
     * el cursor, y como mucho una vez cada N ms.
     */
    const val REPOSITION_COOLDOWN_MS = 3_000L

    /** Sonda de cola para moov-at-end (una emisión, solo si hace falta). */
    const val TAIL_PROBE_BYTES = 4L * 1024L * 1024L

    /** Lecturas con pos en los últimos N bytes se tratan como sonda de cola. */
    const val TAIL_REGION_BYTES = 8L * 1024L * 1024L

    /** Meta GetFile: timeout y TTL de caché. */
    const val META_TIMEOUT_MS = 10_000L
    const val META_CACHE_TTL_MS = 120_000L

    /** Espera a que el fichero aparezca en disco tras la primera emisión. */
    const val FILE_APPEAR_TIMEOUT_MS = 15_000L

    /** Stall de lectura antes de lanzar TgStallTimeoutException (→ retry Exo). */
    const val READ_STALL_TIMEOUT_MS = 30_000L

    /** Throttle del fallback GetFileDownloadedPrefixSize por sesión. */
    const val AVAIL_CHECK_MIN_INTERVAL_MS = 1_000L

    /** Quantum de espera del bucle read (notify-driven + resync). */
    const val WAIT_QUANTUM_MS = 250L

    /** Piso de espacio libre para intentar reproducir. */
    const val MIN_FREE_BYTES_FLOOR = 96L * 1024L * 1024L

    // ── Freno de espacio (el temp TDLib crece sin cota: la ventana acota cada
    // emisión, no el acumulado en disco) ──────────────────────────────
    /** Con menos de esto se pausa la descarga (nunca llenar el disco). */
    const val SPACE_BRAKE_BYTES = 64L * 1024L * 1024L

    /** Con esto o más se reanuda tras el freno. */
    const val SPACE_RESUME_BYTES = 150L * 1024L * 1024L

    /** Rotación: con menos de esto Y temp mayor que ROTATE_FILE, se rota. */
    const val SPACE_ROTATE_FREE_BYTES = 120L * 1024L * 1024L
    const val SPACE_ROTATE_FILE_BYTES = 300L * 1024L * 1024L

    /** Auto-evict de otras descargas durante reproducción bajo este libre. */
    const val SPACE_EVICT_FREE_BYTES = 120L * 1024L * 1024L
    const val SPACE_EVICT_MIN_INTERVAL_MS = 15_000L

    /** Sesión sin lectores: TTL antes de cancelar la descarga. */
    const val SESSION_IDLE_TTL_MS = 10L * 60L * 1000L

    /** Barrido de sesiones ociosas. */
    const val SESSION_SWEEP_INTERVAL_MS = 60_000L

    /** Intervalo de telemetría READ (ingress vs consume). */
    const val TELEMETRY_INTERVAL_MS = 3_000L
}
