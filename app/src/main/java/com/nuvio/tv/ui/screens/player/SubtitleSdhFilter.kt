package com.nuvio.tv.ui.screens.player

import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.exoplayer.text.TextOutput

internal object SubtitleSdhFilter {
    private val squareBrackets = Regex("\\[[^]\\r\\n]*][ \\t]*")
    private val uppercaseParentheses = Regex(
        "(?:\\((?=[A-Zl0-9 '#.,\\\"\\\\-]*\\))(?=[^)]*[A-Zl])[^)]*\\)|" +
            "\uFF08(?=[A-Zl0-9 '#.,\\\"\\\\-]*\uFF09)(?=[^\uFF09]*[A-Zl])[^\uFF09]*\uFF09)[ \\t]*"
    )
    private val speakerLabel = Regex(
        "(?m)^([ \\t]*-[ \\t]*)?(?:[A-Zl0-9 '#.,]+|\\[[^]\\r\\n]*]):(?=\\s|$)[ \\t]*"
    )

    fun filterCues(cues: List<Cue>): List<Cue> = cues.mapNotNull { cue ->
        val text = cue.text?.toString() ?: return@mapNotNull cue
        val filtered = filterPlainText(text) ?: return@mapNotNull null
        if (filtered == text) cue else cue.buildUpon().setText(filtered).build()
    }

    internal fun filterPlainText(text: String): String? {
        var filtered = speakerLabel.replace(text) { match -> match.groups[1]?.value.orEmpty() }
        filtered = squareBrackets.replace(filtered, "")
        filtered = uppercaseParentheses.replace(filtered, "")
        return filtered.lines()
            .filter { line -> line.any { !it.isWhitespace() && it != '-' } }
            .joinToString("\n")
            .takeIf(String::isNotBlank)
    }
}

internal class SdhFilteringTextOutput(
    private val delegate: TextOutput,
    private val enabled: () -> Boolean
) : TextOutput {
    override fun onCues(cueGroup: CueGroup) {
        if (!enabled()) {
            delegate.onCues(cueGroup)
            return
        }
        delegate.onCues(
            CueGroup(SubtitleSdhFilter.filterCues(cueGroup.cues), cueGroup.presentationTimeUs)
        )
    }

    @Suppress("DEPRECATION")
    @Deprecated("Uses the deprecated Media3 callback for text outputs.")
    override fun onCues(cues: List<Cue>) {
        delegate.onCues(if (enabled()) SubtitleSdhFilter.filterCues(cues) else cues)
    }
}
