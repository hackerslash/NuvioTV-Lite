# NuvioTV-Lite-TG — Telegram Streaming: Plan, Análisis y Conclusiones

> **Fecha**: 26 agosto 2026
> **Estado**: Streaming bloqueado — priority=1000 rechazada por TDLib (rango válido: 1-32).
> **Último APK**: `app/build/outputs/apk/lite/debug/app-lite-armeabi-v7a-debug.apk` (67MB)
> **Bug conocido**: `DOWNLOAD_PRIORITY = 1000` y `PRE_START_PRIORITY = 1000` deben ser `32` (máximo válido en TDLib).

---

## 1. Objetivo

Permitir que NuvioTV-Lite-TG (fork de una app IPTV para Android TV) reproduzca contenido multimedian alojado en canales de Telegram directamente sobre un **proyector Magcubic HY310** con recursos muy limitados, usando TDLib nativo como motor de descarga y ExoPlayer/Media3 como reproductor.

---

## 2. Dispositivo objetivo

| Característica | Valor |
|---|---|
| Modelo | Magcubic HY30 |
| SoC | Allwinner H713 |
| RAM | 1GB DDR3 |
| Almacenamiento | 8GB eMMC (~2.2GB libres tras OS + app) |
| Android | 11 (SDK 30) |
| ABI | armeabi-v7a (32-bit) |
| adb | `192.168.5.171:5555` |
| Paquete | `com.nuviodebug.com` |
| Activity | `com.nuvio.tv.MainActivity` |

**Restricciones críticas**:
- 1GB RAM: ExoPlayer no puede manejar buffers grandes ni múltiples instancias.
- 2.2GB libres: Un archivo de 2.8GB NO cabe entero en disco. Streaming parcial obligatorio.
- CPU ARM Cortex-A53: Decodifica HEVC 1080p hardware, pero con poco margen.
- Solo `armeabi-v7a`: TDLib se compila solo para esa ABI.

---

## 3. Build

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
export NUVIO_RELEASE_KEY_ALIAS=nuvio \
       NUVIO_RELEASE_KEY_PASSWORD=nuvio-dev-2026 \
       NUVIO_RELEASE_STORE_PASSWORD=nuvio-dev-2026
./gradlew :app:assembleLiteDebug -x lintVitalLiteDebug
```

APK resultante: `app/build/outputs/apk/lite/debug/app-lite-armeabi-v7a-debug.apk`

---

## 4. Arquitectura general

### 4.1 Flujo completo

```
Usuario busca película
    → TelegramClientManager.sendRequest(SearchQuery)
    → Resultados contienen file IDs de Telegram
    → TelegramTitleMatcher fuzzy-match contra la query
    → Se construye URL: http://127.0.0.1:0/tg/{chatId}/{messageId}/{fileId}
    → TelegramStreamProxy.buildStreamUrl() → dispara preStartDownload(fileId)
    → PlayerMediaSourceFactory detecta host=127.0.0.1
    → Crea TelegramDataSource.Factory → ProgressiveMediaSource
    → TelegramDataSource.open(fileId) → GetFile + DownloadFile(limit=0)
    → TelegramDataSource.read() → RandomAccessFile sobre temp file de TDLib
    → ExoPlayer decodifica → pantalla del proyector
```

### 4.2 Archivos clave

| Archivo | Función |
|---|---|
| `core/telegram/TelegramClientManager.kt` | Singleton TDLib. Autenticación, `sendRequest()` suspend, DB en `tdlib/`, archivos en `tdlib_files/` |
| `core/telegram/TelegramDataSource.kt` | **EL DataSource principal**. Lee archivos TDLib via `RandomAccessFile`. Patrón Nagram. |
| `core/telegram/TelegramStreamProxy.kt` | Constructor de URLs + pre-start de descarga. Sin HTTP proxy. |
| `core/telegram/TelegramTitleMatcher.kt` | Fuzzy matching de títulos contra resultados de búsqueda |
| `core/telegram/TelegramRangeParser.kt` | Parseo de rangos "1-5", "S01E01-S01E03" para series |
| `core/telegram/TelegramMediaParser.kt` | Extrae archivos multimedia de mensajes de Telegram |
| `core/telegram/TelegramAuthState.kt` | Estados de autenticación TDLib |
| `ui/screens/player/PlayerMediaSourceFactory.kt` | Detecta URLs TG_DIRECT, crea MediaSource con TelegramDataSource |
| `ui/screens/player/PlayerRuntimeControllerInitialization.kt` | LoadControl FASE 6 para Telegram (32MB/90s) |

---

## 5. TelegramDataSource — Diseño detallado

### 5.1 Patrón Nagram

Tomado de la app Nagram (fork de Telegram con streaming). La idea:

1. **Una sola llamada** `DownloadFile(fileId, limit=0, priority=32)` — descarga el archivo entero (priority Máx=32)
2. TDLib escribe en un **temp file** en disco (`local.path`)
3. Un `RandomAccessFile` en modo `"r"` lee desde ese temp file
4. Si ExoPlayer pide leer más allá de lo descargado, `read()` **bloquea** (polling cada 100ms) hasta que TDLib escriba más datos
5. Nunca se cancela ni re-emite la descarga

### 5.2 Restricción TDLib crítica

**Solo UN `DownloadFile` activo por fileId.** Si se llama una segunda vez con el mismo fileId, **cancela** el primero. Por eso:
- `limit=0` descarga todo el archivo de una vez
- Nunca se re-emite con offsets parciales
- Se usa `ConcurrentHashMap<Int, AtomicBoolean>` para trackear descargas ya iniciadas

### 5.3 Timeout de lectura

```kotlin
READ_TIMEOUT_MS = 30_000L   // 30s máximo de espera por datos en disco
FILE_APPEAR_TIMEOUT_MS = 15_000L  // 15s para que el temp file aparezca
POLL_DATA_MS = 100L          // Polling cada 100ms
```

Cuando `read()` no encuentra datos disponibles, duerme 100ms y reintentar hasta que:
- Hay datos disponibles (`fileLen - position > 0`)
- La descarga está completa
- Se alcanza el timeout → retorna -1 (EOF)

### 5.4 Factory (Hilt EntryPoint)

```kotlin
class Factory(private val context: Context) : DataSource.Factory {
    override fun createDataSource(): DataSource {
        val entryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext, TelegramClientEntryPoint::class.java
        )
        return TelegramDataSource(entryPoint.telegramClientManager())
    }
}
```

Usa `EntryPointAccessors` porque `TelegramDataSource` no es una clase Hilt (es instanciada por ExoPlayer).

---

## 6. PlayerMediaSourceFactory — Routing TG_DIRECT

En `PlayerMediaSourceFactory.kt`, línea ~114:

```kotlin
val isTelegramLocalhost = try { Uri.parse(url).host == "127.0.0.1" } catch (_: Exception) { false }
if (isTelegramLocalhost) {
    val telegramFactory = TelegramDataSource.Factory(context)
    val extractorsFactory = customExtractorsFactory ?: DefaultExtractorsFactory()
    val tgMediaItem = mediaItem.buildUpon().setMimeType(MimeTypes.VIDEO_MP4).build()
    val progressiveFactory = ProgressiveMediaSource.Factory(telegramFactory, extractorsFactory)
        .setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
    val mediaSource = progressiveFactory.createMediaSource(tgMediaItem)
    return wrapAudioDelay(mediaSource, audioDelayUsProvider)
}
```

**Decisión de diseño**: Se usa `ProgressiveMediaSource.Factory` directamente en lugar de `DefaultMediaSourceFactory`. Razón: `DefaultMediaSourceFactory` envuelve el factory en `DefaultDataSource.Factory` que crea una segunda instancia de `TelegramDataSource` (con `fileId=0`) que nunca recibe `open()` → timeout de 60s desperdiciado.

**MIME type explícito**: Se fuerza `video/mp4` en el MediaItem para que ExoPlayer no desperdicie tiempo probando extractors MKV, WebM, etc.

---

## 7. LoadControl FASE 6

En `PlayerRuntimeControllerInitialization.kt`, línea ~539:

```kotlin
if (isTelegramLocalhost) {
    DefaultLoadControl.Builder()
        .setTargetBufferBytes(32 * 1024 * 1024)    // 32MB target
        .setBufferDurationsMs(15_000, 90_000, 2_000, 3_000)  // min=15s, max=90s, pos=2s, rebuf=3s
        .setPrioritizeTimeOverSizeThresholds(true)  // Priorizar tiempo sobre bytes
        .setBackBuffer(2_000, true)
        .build()
}
```

- `minBufferMs = 15_000`: Buffer mínimo 15 segundos
- `maxBufferMs = 90_000`: Buffer máximo 90 segundos
- `bufferForPlaybackMs = 2_000`: Empieza reprodución con 2s en buffer
- `bufferForPlaybackAfterRebufferMs = 3_000`: 3s tras rebuffer
- `targetBufferBytes = 32MB`: Target de bytes en buffer (importante: 1GB RAM)
- `prioritizeTimeOverSizeThresholds = true`: Permite pasar de 32MB si no se ha alcanzado minBufferMs

---

## 8. Análisis de trazas (logs)

### 8.1 Trace exitosa (video reproduce pero con buffering)

```
21:53:00.347 TG_DIRECT: using TelegramDataSource for fileId=1737
21:53:00.357 GetFile fileId=1737 ...
21:53:00.427 GetFile size=2679MB path=/data/.../temp/26 completed=false
21:53:00.440 DOWNLOAD START fileId=1737 (entire file, priority=32)
21:53:00.445 OPEN fileId=1737 size=2679MB pos=0
21:53:00.450 READ fileId=1737 pos=1MB disk=1MB ahead=0MB
21:53:01.100 READ fileId=1737 pos=5MB disk=18MB ahead=13MB speed=4000KB/s
21:53:03.000 READ fileId=1737 pos=10MB disk=30MB ahead=20MB speed=1500KB/s
21:53:05.000 CLOSE fileId=1737 pos=10MB/2679MB          ← ExoPlayer cierra tras sniffing
21:53:05.010 OPEN fileId=1737 pos=10572520              ← Re-abierto en posición de probe
21:53:06.500 CLOSE fileId=1737 pos=10MB/2679MB
21:53:06.510 OPEN fileId=1737 pos=165219625             ← Seek al moov atom (~157MB)
21:53:06.520 READ fileId=1737 pos=165MB disk=40MB       ← Solo 40MB en disco, busca en 165MB
   [Bloquea 5+ segundos mientras descarga crece]
21:53:12.000 READ fileId=1737 pos=165MB disk=55MB ahead=-110MB
   [... sigue bloqueado ...]
21:53:20.000 READ fileId=1737 pos=165MB disk=80MB
21:53:30.000 READ fileId=1737 pos=165MB disk=110MB       ← Descarga alcanza la posición
21:53:30.100 READ fileId=1737 pos=170MB disk=110MB       ← Lectura avanza
21:53:35.000 READ fileId=1737 pos=200MB disk=150MB speed=1500KB/s
   [... reproducción comienza ...]
```

**Observaciones**:
- La descarga a ~1500KB/s es marginal para HEVC 1080p (~1500KB/s necesario)
- El seek al moov atom en ~157MB fuerza una espera de ~30s antes de que la descarga alcance esa posición
- Una vez pasando el moov, la reproducción fluye

### 8.2 Trace del bug "fileId=0" (ANTES del fix)

```
21:59:07.828 TG_DIRECT: using TelegramDataSource for fileId=1737
21:59:07.828 OPEN fileId=1737 pos=0
21:59:07.828 READ fileId=1737 pos=0MB disk=1MB
   [... lecturas normales, descarga avanza ...]
21:59:08.041 OPEN fileId=1737 pos=165219625            ← Segundo open (moov atom)
21:59:08.944 CLOSE fileId=0 pos=157MB/2679MB           ← ¡fileId=0! Segunda instancia
21:59:08.944 READ TIMEOUT fileId=0 pos=157MB           ← Timeout 60s en instancia fantasma
22:00:14.367 READ TIMEOUT fileId=0 pos=157MB           ← Otro timeout
22:00:14.370 CLOSE fileId=0 pos=157MB/2679MB
22:00:14.374 TG_DIRECT: using TelegramDataSource for fileId=1737  ← ExoPlayer recrea todo
   [Ciclo se repite 2-3 veces]
```

**Causa raíz**: `DefaultMediaSourceFactory` envuelve `TelegramDataSource.Factory` en `DefaultDataSource.Factory`, que instancia un segundo `TelegramDataSource` (nunca recibe `open()` correcto). Esa instancia tiene `fileId=0` y bloquea en `read()` por 60 segundos.

**Fix aplicado**: Reemplazar `DefaultMediaSourceFactory` por `ProgressiveMediaSource.Factory` directamente.

### 8.3 Trace del bug "ensureDownload cancellation" (ANTES del fix anterior)

```
READ pos=10MB disk=30MB     ← read正常的
ensureDownload(offset=10MB, limit=32MB)
   → DownloadFile(offset=10MB, limit=32MB)  ← ¡Cancela la descarga anterior!
READ pos=10MB disk=10MB     ← Solo 10MB restante porque la descarga fue cancelada
   → Disco nunca crece más allá de ~32MB
   → ExoPlayer busca posiciones > 32MB → timeout → crash
```

**Causa raíz**: Cada llamada a `DownloadFile` con el mismo fileId **cancela** la anterior en TDLib. El código antiguo llamaba `ensureDownload` con offsets parciales.

**Fix aplicado**: Una sola llamada `DownloadFile(limit=0)` que nunca se re-emite.

### 8.4 Trace del bug "priority=1000 rechazada" (26 agosto 2026)

```
08:47:11.565 TelegramProxy: PRE-START download fileId=2724 (priority=1000)
08:47:11.570 TelegramProxy: PRE-START FAILED fileId=2724
08:47:11.570 TelegramProxy: com.nuvio.tv.core.telegram.TelegramApiException: Priority must be between 1 and 32
08:47:11.570 TelegramProxy:   at TelegramClientManager$sendRequest$2$1$1.onResult(TelegramClientManager.kt:256)
   [... idéntico para fileId=2721, fileId=2826 ...]

08:47:15.448 PlayerMediaSrc: TG_DIRECT: using TelegramDataSource for fileId=2826
08:47:15.512 TgDataSource: OPEN fileId=2826 size=2679MB pos=0
08:47:15.512 TgDataSource: DOWNLOAD START fileId=2826 (entire file, priority=1000)
08:47:15.515 TgDataSource: DOWNLOAD FAILED fileId=2826
08:47:15.515 TgDataSource: com.nuvio.tv.core.telegram.TelegramApiException: Priority must be between 1 and 32

08:47:30.586 TgDataSource: waitForFile timed out fileId=2826
08:47:30.587 TgDataSource: CLOSE fileId=2826 pos=0MB/2679MB
   [ExoPlayer reintenta → mismo ciclo → priority=1000 → rechazado → timeout → reintenta]
08:47:30.593 TgDataSource: OPEN fileId=2826 size=2679MB pos=0
08:47:30.593 TgDataSource: DOWNLOAD START fileId=2826 (entire file, priority=1000)
08:47:30.596 TgDataSource: DOWNLOAD FAILED fileId=2826  ← Mismo error
08:47:45.658 TgDataSource: waitForFile timed out fileId=2826
08:47:45.659 TgDataSource: CLOSE fileId=2826 pos=0MB/2679MB
   [Ciclo se repite indefinidamente]
```

**Causa raíz**: TDLib valida `priority` en rango **1–32**. El código usaba `priority=1000` (asumiendo rango Android estándar). TDLib rechaza con `TelegramApiException` → `startDownloadIfNeeded` falla → nunca se descarga nada → `waitForFile` timeout → ExoPlayer reintenta → mismo error.

**Efecto cascada**:
1. Pre-start en `TelegramStreamProxy` falla (3 fileIds simultáneos, todos rechazados)
2. `TelegramDataSource.open()` → `startDownloadIfNeeded()` falla (priority=1000)
3. `waitForFile()` → timeout 15s (no hay archivo en disco porque la descarga nunca empezó)
4. `open()` lanza `IOException("File not available on disk")`
5. ExoPlayer reintenta → `open()` otra vez → `startDownloadIfNeeded` otra vez (el `AtomicBoolean` se resetea en el catch) → falla de nuevo
6. Ciclo infinito: OPEN → FAIL → TIMEOUT → CLOSE → OPEN → FAIL → TIMEOUT → CLOSE → ...

**Fix necesario**: Cambiar `DOWNLOAD_PRIORITY = 1000` → `DOWNLOAD_PRIORITY = 32` y `PRE_START_PRIORITY = 1000` → `PRE_START_PRIORITY = 32`. Also resetear el `AtomicBoolean` en el catch de `preStartDownload` para que reintente con priority válida (ya está hecho).

**Archivo afectado**:
- `TelegramDataSource.kt:44` → `DOWNLOAD_PRIORITY = 1000` debe ser `32`
- `TelegramStreamProxy.kt:32` → `PRE_START_PRIORITY = 1000` debe ser `32`

## 9. Bugs corregidos (historial)

### Bug 1: Disk-full SIGABRT (commit 426065310)
- **Problema**: En dispositivo de 8GB eMMC, una descarga de archivo grande llenaba el disco → SIGABRT del sistema
- **Fix**: Prevención de disco lleno, límites de cache

### Bug 2: NAL length inválido + crash de MediaCodecAudioRenderer
- **Problema**: `ensureDownload(offset, limit=32MB)` cancelaba la descarga anterior. Solo 32MB en disco. MP4 extractor leía basura → `Invalid NAL length` + crash de audio → loop infinito de reintentos
- **Fix**: Single `DownloadFile(limit=0)`, nunca re-emitido. Patrón Nagram exacto.

### Bug 3: Duplicación de companion object (esta sesión)
- **Problema**: PlayerMediaSourceFactory tenía dos `companion object` (error de Kotlin)
- **Fix**: Merge en uno solo

### Bug 4: Falta `addTransferListener` (esta sesión)
- **Problema**: `TelegramDataSource` no implementaba `addTransferListener()` de la interfaz `DataSource`
- **Fix**: Implementación con campo `transferListener`

### Bug 5: Flag `closed` no se resetea en `open()` (esta sesión)
- **Problema**: ExoPlayer reabre el mismo DataSource después de cerrar. El flag `closed = true` persistía → `read()` retornaba -1 inmediatamente
- **Fix**: `closed = false` al inicio de `open()`

### Bug 6: `downloadStarted` se reseteaba al cerrar (esta sesión)
- **Problema**: Si se cerraba el DataSource y se reabría, `downloadStarted` se reseteaba → segunda llamada a `DownloadFile` cancelaba la primera
- **Fix**: `downloadStarted` es `ConcurrentHashMap<Int, AtomicBoolean>` estático en el companion object, nunca se resetea por fileId

### Bug 7: Dual-instance fileId=0 timeout (esta sesión)
- **Problema**: `DefaultMediaSourceFactory` envolvía nuestro factory creando una segunda instancia que nunca recibía `open()` → 60s timeout
- **Fix**: Usar `ProgressiveMediaSource.Factory` directamente + MIME type explícito

### Bug 8: `raf` null después de close (esta sesión)
- **Problema**: Después de `close()`, `raf = null`. ExoPlayer reabría la misma instancia → `raf?.read()` retornaba null → -1 → EOF prematuro
- **Fix**: En `open()`, si `currentFile` coincide pero `raf == null`, recrear el RandomAccessFile

### Bug 9: priority=1000 rechazada por TDLib (26 agosto 2026) — **ACTUAL**
- **Problema**: `DOWNLOAD_PRIORITY = 1000` y `PRE_START_PRIORITY = 1000` están fuera del rango válido de TDLib (1-32). `TelegramApiException: Priority must be between 1 and 32` → descarga NUNCA inicia → `waitForFile` timeout → loop infinito de reintentos
- **Fix pendiente**: Cambiar ambos valores a `32` (máximo válido en TDLib)
- **Archivos**: `TelegramDataSource.kt:44`, `TelegramStreamProxy.kt:32`

---

## 10. Estado actual y problemas pendientes

### 10.1 Lo que funciona (estado confirmado)
- Autenticación TDLib
- Búsqueda de contenido en canales de Telegram
- Fuzzy matching de títulos
- Integración de rutas TG_DIRECT en el player
- Decodificación HEVC 1080p hardware (cuando hay datos válidos)

> Nota: la reproducción directa TG estuvo funcional en pruebas previas, pero ese estado debe
> considerarse **no vigente** hasta revalidar después del fix de prioridad TDLib (Bug 9).

### 10.2 Problemas abiertos

#### A. **BLOQUEANTE: priority=1000 rechazada** (Bug 9)
- **Estado**: La descarga NUNCA inicia porque TDLib rechaza `priority=1000`
- **Fix**: Cambiar `DOWNLOAD_PRIORITY` y `PRE_START_PRIORITY` a `32` (máximo válido)
- **Archivos**: `TelegramDataSource.kt:44`, `TelegramStreamProxy.kt:32`
- **Señal en logs**: `TelegramApiException: Priority must be between 1 and 32`

#### B. Velocidad de descarga vs bitrate del video
- **Descarga**: ~1500KB/s (prioridad 32, máxima válida en TDLib)
- **Necesario para HEVC 1080p**: ~1500KB/s
- **Resultado**: Marginal. El buffer `ahead` (descargado - leído) se reduce gradualmente → buffering a los ~2:30 min
- **Posible solución**: Investigar por qué TDLib no descarga más rápido (¿limitación de red? ¿prioridad? ¿conexión Telegram?)

#### C. Delay inicial por moov atom
- **Problema**: ExoPlayer (Mp4Extractor) hace seek al moov atom que en archivos non-faststart está al final (~157MB para un archivo de 2.6GB). La descarga tarda ~30s en alcanzar esa posición.
- **Posible solución**: Detectar faststart vs non-faststart y manejar differently. O hacer prefetch más agresivo antes de crear el DataSource.

#### D. Download speed inconsistente
- **Observación**: La velocidad de descarga fluctúa entre 500KB/s y 3000KB/s
- **Posible causa**: Throttling de Telegram, latencia de red, o prioridad del sistema de archivos

---

## 11. Constraints de TDLib (resumen)

| Regla | Detalle |
|---|---|
| Un solo DownloadFile por fileId | La segunda llamada **cancela** la primera. Siempre. |
| `limit=0` = archivo completo | No hay forma de descargar parcial sin cancelar |
| `priority` va de 1 a **32** | **32=Highest**, 1=Lowest. Rango verificado: `Priority must be between 1 and 32` |
| Temp file en `local.path` | Creado por TDLib, puede no existir al inicio |
| `isDownloadingCompleted` | True cuando el archivo está completo en disco |
| `GetFile` retorna tamaño | `file.size` o `file.expectedSize` |
| Una sola instancia TDLib | `TelegramClientManager` es `@Singleton` |

---

## 12. Número de FASE

| FASE | Contenido | Commit |
|---|---|---|
| 0 | Auth TDLib, UI de login | En el repo |
| 1 | Búsqueda de contenido en canales | En el repo |
| 2 | Fuzzy matching de títulos | En el repo |
| 3 | Parser de rangos para series | En el repo |
| 4 | NanoHTTPD proxy (ELIMINADO) | Histórico |
| 5 | StreamRepository | 426065310 |
| 6 | LoadControl exo-telegram (32MB/90s) | En `PlayerRuntimeControllerInitialization.kt` |
| 7 | TelegramDataSource patrón Nagram | **ACTUAL** |

---

## 13. Dependency: Media3 version

- **Media3**: 1.8.0
- **Local module**: `:nuvio-exoplayer-engine` (stock media3 excluded)
- **Imports relevantes**:
  - `androidx.media3.datasource.DataSource`
  - `androidx.media3.datasource.DataSpec`
  - `androidx.media3.datasource.TransferListener`
  - `androidx.media3.exoplayer.source.ProgressiveMediaSource`
  - `androidx.media3.exoplayer.source.DefaultMediaSourceFactory` (ya NO se usa para Telegram)
  - `androidx.media3.exoplayer.DefaultLoadControl`

---

## 14. Instrucciones para testing

### 14.1 Deploy APK al proyector
```bash
# Construir
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
export NUVIO_RELEASE_KEY_ALIAS=nuvio NUVIO_RELEASE_KEY_PASSWORD=nuvio-dev-2026 NUVIO_RELEASE_STORE_PASSWORD=nuvio-dev-2026
./gradlew :app:assembleLiteDebug -x lintVitalLiteDebug

# Instalar
$HOME/Library/Android/sdk/platform-tools/adb -s 192.168.5.171:5555 install -r app/build/outputs/apk/lite/debug/app-lite-armeabi-v7a-debug.apk

# Lanzar
$HOME/Library/Android/sdk/platform-tools/adb -s 192.168.5.171:5555 shell am start -n com.nuviodebug.com/com.nuvio.tv.MainActivity
```

### 14.2 Monitorear logs
```bash
# Telegram streaming específico
$HOME/Library/Android/sdk/platform-tools/adb -s 192.168.5.171:5555 logcat -s TgDataSource,TelegramProxy,PlayerMediaSrc,TelegramClient

# Errores generales del player
$HOME/Library/Android/sdk/platform-tools/adb -s 192.168.5.171:5555 logcat | grep -iE "Invalid NAL|MediaCodec.*Error|BUFFER_GATE|TG_DIRECT|Playback error"
```

### 14.3 Señales de éxito
- `TG_DIRECT: using TelegramDataSource for fileId=XXXX` (una sola vez)
- `PRE-START download fileId=XXXX` (inmediatamente)
- `OPEN fileId=XXXX pos=0` (sin fileId=0)
- READ con `speed` > 1500KB/s sostenido
- Video aparece en <30 segundos
- Sin `READ TIMEOUT`
- Sin `Invalid NAL length`
- Sin `MediaCodecAudioRenderer` crash
- Sin loop de reintentos (`TG_DIRECT` aparece solo una vez)

### 14.4 Señales de problema
- `CLOSE fileId=0` → bug del dual-instance no está fixeado
- `READ TIMEOUT fileId=0` → la instancia fantasma está bloqueando
- `TG_DIRECT` aparece múltiples veces → ExoPlayer está recreando el source
- `Invalid NAL length` → problema con el extracto de datos (descarga insuficiente)
- `speed=0KB/s` → descarga estancada
- `READ EOF` muy temprano (<10MB) → descarga incompleta o URI malformada

---

## 15. Decisiones de diseño y rationale

| Decisión | Alternativa descartada | Razón |
|---|---|---|
| `DownloadFile(limit=0)` single-call | `ensureDownload(offset, limit=32MB)` | TDLib cancela la descarga anterior en cada llamada. Parcial = destruir progreso |
| `ProgressiveMediaSource.Factory` directo | `DefaultMediaSourceFactory` | `DefaultMediaSourceFactory` crea segunda instancia via `DefaultDataSource.Factory` → timeout 60s |
| `RandomAccessFile` en temp file | NanoHTTPD HTTP proxy | NanoHTTPD añadía latencia, overhead de HTTP loopback, más puntos de fallo |
| `VIDEO_MP4` explícito | Sin MIME type (auto-detect) | ExoPlayer probaba MKV, WebM, etc. → waste de CPU en dispositivo lento |
| Pre-start download en `buildStreamUrl()` | Solo en `open()` | Ahorra ~5-10s de setup de ExoPlayer mientras ya está descargando |
| Priority 32 (Highest TDLib) | Priority 1000 (causó rechazo TDLib) | **TDLib solo acepta 1-32**. Priority 1000 genera `TelegramApiException: Priority must be between 1 and 32` → descarga NUNCA inicia |
| `FILE_APPEAR_TIMEOUT = 15s` | 60s | Con pre-start, el archivo debería existir rápido. 60s es demasiado |
| `READ_TIMEOUT = 30s` | 60s | Reduce el peor caso. Con 32MB buffer de MoovParser, 30s es suficiente |
| `targetBufferBytes = 32MB` | Mayor | 1GB RAM, no se puede dedicar más. `prioritizeTimeOverSizeThresholds = true` permite exceder si es necesario |

---

## 16. Próximos pasos

1. **Fix Bug 9** — Cambiar `DOWNLOAD_PRIORITY = 32` y `PRE_START_PRIORITY = 32` en `TelegramDataSource.kt` y `TelegramStreamProxy.kt`
2. **Probar el APK con priority=32** — Verificar que la descarga inicia correctamente
3. **Medir tiempo-to-first-frame** — Debería ser <30s con pre-start
4. **Monitorear buffer ahead** — Debería mantenerse positivo durante la película
5. **Si hay buffering**: Investigar limitación de red (TDLib vs velocidad real del servidor de Telegram)
6. **Commit** cuando esté estable

---

## 17. Matriz de decisión por trazas (runbook)

Usar esta matriz para decidir el siguiente paso sin ambigüedad. Prioridad de arriba hacia abajo.

| Patrón en log | Diagnóstico | Acción inmediata |
|---|---|---|
| `Priority must be between 1 and 32` | Prioridad TDLib inválida, descarga no inicia | Verificar `DOWNLOAD_PRIORITY` y `PRE_START_PRIORITY` en `32`, rebuild y redeploy |
| `DOWNLOAD START ...` seguido de `DOWNLOAD FAILED` | `DownloadFile` rechazado o error TDLib | Revisar excepción exacta (`TelegramApiException`) y corregir antes de seguir |
| `waitForFile timed out` con `DOWNLOAD START` ausente | Nunca se inició descarga | Volver a validar Fase 0 (prioridad/ruta de llamada a `DownloadFile`) |
| `waitForFile timed out` con `DOWNLOAD START` presente | Descarga arrancó pero no materializa `local.path` a tiempo | Aumentar timeout solo tras confirmar progreso con `GetFile`; no tocar extractor aún |
| `TG_DIRECT` repetido en bucle + `OPEN/CLOSE` cíclico | ExoPlayer reintenta por fallo de open/read | Arreglar causa raíz del `open()`/`read()`; no optimizar buffering todavía |
| `CLOSE fileId=0` o `READ TIMEOUT fileId=0` | Sospecha de instancia fantasma/probing/retry | Añadir `instanceId` en logs de `TelegramDataSource` y confirmar flujo real |
| `READ ... disk=...` con `disk` creciendo | Pipeline funcional (TDLib -> disco -> DataSource) | Pasar a fase de estabilidad/TTFF |
| `READ ... speed` bajo y `ahead` decreciente | Throughput marginal para bitrate actual | Clasificar como problema de rendimiento, no de arquitectura base |
| `Invalid NAL length` / crash codec | Datos corruptos/incompletos o seek inválido | Verificar integridad de lectura y secuencia de descargas por fileId |

---

## 18. Protocolo de ejecución para continuar con éxito

### 18.1 Orden fijo de trabajo (obligatorio)
1. **Restaurar funcionalidad base** (Bug 9) antes de cualquier optimización.
2. **Probar un único archivo controlado** y recoger trazas completas.
3. **Clasificar resultado** con la matriz de la sección 17.
4. **Aplicar un solo cambio por iteración**.
5. Repetir build/deploy/test hasta pasar criterios de fase.

### 18.2 Criterios de salida por fase
- **Fase 0 (bloqueante)**: no aparece `Priority must be between 1 and 32`.
- **Fase 1 (pipeline)**: existe secuencia `OPEN -> READ` con `disk` creciendo.
- **Fase 2 (retry loop)**: no hay bucle infinito de `TG_DIRECT`/`OPEN`/`CLOSE`.
- **Fase 3 (inicio)**: TTFF estable en condiciones repetibles.
- **Fase 4 (rendimiento)**: reproducción sostenida sin rebuffer severo en muestra de 10 minutos.

### 18.3 Reglas para evitar falsos positivos
- No declarar "fix" sin traza comparativa antes/después.
- No mezclar cambios de arquitectura y tuning en la misma iteración.
- No modificar timeouts para tapar errores de estado (open/download).
- Si falla Fase 0, detener el resto de análisis: todo resultado posterior queda invalidado.

### 18.4 Plantilla de reporte por iteración
```
Iteración: <N>
Cambio único aplicado: <archivo:línea + descripción>
Archivo de prueba: <chatId/messageId/fileId>
Resultado:
- ¿Aparece Priority error?: sí/no
- ¿Hay DOWNLOAD START?: sí/no
- ¿Hay waitForFile timeout?: sí/no
- ¿Hay OPEN->READ con disk creciendo?: sí/no
- ¿Hay loop TG_DIRECT/OPEN/CLOSE?: sí/no
Conclusión de la iteración: <pasó fase X / bloqueado en fase Y>
Siguiente acción (única): <acción>
```

---

## 19. Memoria de continuidad (reinicio PC/proyector)

### 19.1 Estado actual consolidado

- **TG_DIRECT estable** en reproducción y seeks largos (fixes previos aplicados).
- **Continue Watching (TG)** validado por usuario:
  - Reanuda desde el punto guardado.
  - Detecta una fuente TG unívoca para "Reanudar".
  - Muestra esa fuente al inicio de la lista.
- **Nueva mejora de búsqueda Telegram** implementada en esta sesión:
  1. Filtro más estricto para evitar documentos no-video (comics/ebooks)
     cuando llegan como `MessageDocument`.
  2. Búsqueda con más títulos candidatos (hasta 4) para mejorar cobertura multiidioma.
  3. Ranking de resultados por:
     - `matchScore` (título),
     - prioridad de idioma según locale UI,
     - calidad,
     - tamaño.

### 19.2 Archivos tocados en esta fase

- `app/src/main/java/com/nuvio/tv/data/repository/TelegramRepositoryImpl.kt`
  - Añadido filtro `isPlayableVideoDocument(...)` por extensión/mime.
  - Ajuste `MAX_TITLES_QUERIED: 2 -> 4`.
  - Ordenado de resultados por score/idioma/calidad/tamaño.
  - Heurística de idioma de preferencia basada en locale de interfaz.
- `app/src/main/java/com/nuvio/tv/ui/screens/player/PlayerRuntimeControllerObservers.kt`
  - Mantiene pending resume si el media item todavía no es seekable.
- `app/src/main/java/com/nuvio/tv/ui/screens/stream/StreamScreen.kt`
  - Marcado visual de fuente priorizada (`Reanudar • ...`).
- `app/src/main/java/com/nuvio/tv/ui/screens/stream/StreamScreenUiState.kt`
  - Estado de URL preferida de resume.
- `app/src/main/java/com/nuvio/tv/ui/screens/stream/StreamScreenViewModel.kt`
  - Priorización consistente de la fuente preferida en todas las rutas de recomposición/carga.

### 19.3 Build actual

- Comando usado:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
export NUVIO_RELEASE_KEY_ALIAS=nuvio \
       NUVIO_RELEASE_KEY_PASSWORD=nuvio-dev-2026 \
       NUVIO_RELEASE_STORE_PASSWORD=nuvio-dev-2026
./gradlew :app:assembleLiteDebug -x lintVitalLiteDebug
```

- Resultado: **BUILD SUCCESSFUL** (última ejecución de esta sesión).

### 19.4 Lo pendiente al retomar

Como el usuario no puede probar ahora, queda una sola fase de validación funcional:

1. Probar búsqueda problemática (ej. "Master of the universe").
2. Verificar que ya no aparezcan comics/documentos no-video.
3. Verificar que aparezcan resultados válidos multiidioma.
4. Verificar orden: idioma preferido arriba cuando aplique.

### 19.5 Trazas recomendadas al retomar (para diagnóstico rápido)

```bash
$HOME/Library/Android/sdk/platform-tools/adb -s 192.168.5.171:5555 logcat -c
$HOME/Library/Android/sdk/platform-tools/adb -s 192.168.5.171:5555 logcat -s TelegramRepo,StreamRepositoryImpl,TgDataSource,PlayerMediaSrc,TelegramProxy
```

Buscar específicamente en logs:
- `Telegram search ... found=... accepted=...`
- `Preferred resume source ...`
- coincidencia de idioma/ranking y exclusiones por filtro de documento no-video.

### 19.6 Estado de git al cerrar esta sesión

- Ya existe commit previo de CW/TG:
  - `bb2a52e37` — `fix(cw): restore tg resume source and ordering`
- Los cambios de filtro/ranking Telegram de esta sección quedan listos para validar y luego commitear.

---

## 20. Memoria persistente — estado real al apagar (26 ago 2026, cierre de sesión)

### 20.1 Qué SI está hecho

- Streaming TG_DIRECT estable (sin NanoHTTPD) y fixes de seek/retry ya aplicados.
- Continue Watching TG funcional y priorización de fuente de reanudación validada.
- Búsqueda Telegram migrada a enfoque profesional sin hardcodes de títulos manuales:
  - semillas multi-idioma desde TMDB (`locale UI`, `en-US`, fallback original),
  - títulos alternativos oficiales TMDB,
  - resolución de `imdbId` (`ttxxxxxxx`) por `external_ids`,
  - queries expandida con `"titulo tt..."`, `"titulo"` y `"tt..."`.
- Validación técnica local: compilación y assemble OK, APK instalada en el proyector.

### 20.2 Qué NO está resuelto aún

- Persisten casos donde el usuario reporta "no encuentra resultados" en búsqueda TG:
  - ejemplo: `El diablo viste de padra 2`
  - ejemplo: `El dia de la revelacion`
- Sin trazas no se puede confirmar si el fallo es:
  - baja recuperación (query no encuentra),
  - rechazo por matcher (`title/year`),
  - o falta real de archivo en los canales.

### 20.3 Decisión operativa para retomar

- El usuario no puede exportar logs manualmente.
- La continuación será en modo acompañamiento en vivo:
  1. reconectar `adb`,
  2. limpiar `logcat`,
  3. usuario lanza búsquedas desde la app,
  4. el agente monitoriza logs en tiempo real y ajusta algoritmo por evidencia.

### 20.4 Comandos exactos para el arranque de la próxima sesión

```bash
$HOME/Library/Android/sdk/platform-tools/adb connect 192.168.5.171:5555
$HOME/Library/Android/sdk/platform-tools/adb -s 192.168.5.171:5555 logcat -c
$HOME/Library/Android/sdk/platform-tools/adb -s 192.168.5.171:5555 logcat -s StreamRepositoryImpl TelegramRepo TgDataSource PlayerMediaSrc TelegramProxy
```

### 20.5 Próxima acción única

- No tocar más heurísticas "a ciegas".
- Primero capturar una ejecución real de 3-5 títulos fallidos y, con eso, aplicar un ajuste único al matcher/queries.
