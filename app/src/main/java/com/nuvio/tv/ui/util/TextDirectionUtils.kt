package com.nuvio.tv.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDirection

/**
 * Detects a string's own reading direction from its first strong-directional character
 * (Unicode bidi rules P2/P3), independent of the app's ambient UI locale/LayoutDirection.
 */
fun String.contentTextDirection(): TextDirection {
    var index = 0
    while (index < length) {
        val codePoint = codePointAt(index)
        val directionality = Character.getDirectionality(codePoint)
        if (directionality == Character.DIRECTIONALITY_RIGHT_TO_LEFT ||
            directionality == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC) {
            return TextDirection.Rtl
        }
        if (directionality == Character.DIRECTIONALITY_LEFT_TO_RIGHT) {
            return TextDirection.Ltr
        }
        index += Character.charCount(codePoint)
    }
    return TextDirection.Ltr
}

/** True if the string's own content direction (see [contentTextDirection]) is RTL. */
fun String.isContentRtl(): Boolean = contentTextDirection() == TextDirection.Rtl

/**
 * Converts a TextDirection to an absolute horizontal alignment.
 * RTL text directions map to Right, LTR to Left.
 */
fun TextDirection.toAbsoluteAlignment(): Alignment.Horizontal =
    if (this == TextDirection.Rtl) AbsoluteAlignment.Right else AbsoluteAlignment.Left

/**
 * [contentTextDirection] applied to this style. Remembered because a `copy` allocates a new
 * `SpanStyle` and `ParagraphStyle`, and list cards recompose on every focus move.
 */
@Composable
fun TextStyle.directedFor(text: String): TextStyle =
    remember(this, text) { copy(textDirection = text.contentTextDirection()) }

/**
 * [contentTextDirection] remembered per string, for callers that need the direction itself
 * (alignment) rather than a style, on the same recompose-heavy list paths.
 */
@Composable
fun String.rememberContentTextDirection(): TextDirection =
    remember(this) { contentTextDirection() }
