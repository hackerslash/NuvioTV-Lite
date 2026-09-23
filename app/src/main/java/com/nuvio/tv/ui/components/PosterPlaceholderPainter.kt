package com.nuvio.tv.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.roundToInt

/**
 * One breathing clock for every loading poster. It runs only while some placeholder is actually
 * being drawn and stops on its own shortly after the last one is replaced by its image, so a
 * settled screen schedules no frames. The level is quantized, which caps redraws at about 30 a
 * second, and it is read only in the draw phase, so it never recomposes anything.
 */
private object PlaceholderBreath {
    private const val PERIOD_NANOS = 1_600_000_000L
    private const val IDLE_NANOS = 250_000_000L
    private const val STEPS = 24f

    private val level = mutableFloatStateOf(0f)
    private val scope = CoroutineScope(AndroidUiDispatcher.Main)
    private var lastReadNanos = 0L
    private var running = false

    // Draw runs on the main thread, as does the clock, so the two fields need no locking.
    fun read(): Float {
        lastReadNanos = System.nanoTime()
        if (!running) {
            running = true
            scope.launch {
                try {
                    while (System.nanoTime() - lastReadNanos < IDLE_NANOS) {
                        withFrameNanos { frameNanos ->
                            val phase = (frameNanos % PERIOD_NANOS).toFloat() / PERIOD_NANOS
                            val eased = 0.5f - 0.5f * cos(phase * 2f * Math.PI.toFloat())
                            level.floatValue = (eased * STEPS).roundToInt() / STEPS
                        }
                    }
                } finally {
                    running = false
                }
            }
        }
        return level.floatValue
    }
}

/**
 * What a poster shows until its image arrives: the card fill plus a faint outline in the card's
 * shape. With pure black surfaces the fill is the page colour, so without the outline an unloaded
 * card is invisible. The loading variant breathes; the error variant stays still, so a poster that
 * failed does not animate for as long as it is on screen.
 */
private class PosterPlaceholderPainter(
    private val fill: Color,
    private val outlineColor: Color,
    private val shape: Shape,
    private val strokePx: Float,
    private val breathing: Boolean
) : Painter() {
    override val intrinsicSize: Size = Size.Unspecified

    private var cachedSize = Size.Unspecified
    private var cachedOutline: Outline? = null

    override fun DrawScope.onDraw() {
        drawRect(fill)
        val outline = cachedOutline?.takeIf { cachedSize == size }
            ?: shape.createOutline(size, layoutDirection, this).also {
                cachedOutline = it
                cachedSize = size
            }
        val alpha = if (breathing) MIN_ALPHA + (MAX_ALPHA - MIN_ALPHA) * PlaceholderBreath.read() else STATIC_ALPHA
        // The card clips to the same shape, so half the stroke is cut away and the rest sits inside.
        drawOutline(outline, outlineColor, alpha = alpha, style = Stroke(strokePx))
    }

    private companion object {
        // White over the card fill, so it reads on the grey cards and on pure black alike.
        const val MIN_ALPHA = 0.06f
        const val MAX_ALPHA = 0.28f
        const val STATIC_ALPHA = 0.14f
    }
}

@Composable
fun rememberPosterPlaceholderPainter(
    shape: Shape,
    fill: Color = NuvioTheme.colors.BackgroundCard,
    breathing: Boolean = false
): Painter {
    val outlineColor = Color.White
    val strokePx = with(LocalDensity.current) { 2.dp.toPx() }
    return remember(fill, outlineColor, shape, strokePx, breathing) {
        PosterPlaceholderPainter(fill, outlineColor, shape, strokePx, breathing)
    }
}
