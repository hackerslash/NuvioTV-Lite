package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubtitleSdhFilterTest {

    @Test
    fun removesBracketedSoundDescriptions() {
        assertNull(SubtitleSdhFilter.filterPlainText("[suspenseful music playing]"))
        assertNull(SubtitleSdhFilter.filterPlainText("[phone buzzes, then chimes]"))
        assertEquals("Come in.", SubtitleSdhFilter.filterPlainText("[door opens] Come in."))
    }

    @Test
    fun removesSpeakerLabelsButKeepsDialogue() {
        assertEquals("Where are you?", SubtitleSdhFilter.filterPlainText("JOHN: Where are you?"))
        assertEquals("- Wait!", SubtitleSdhFilter.filterPlainText("- WOMAN: Wait!"))
        assertEquals("Hello.", SubtitleSdhFilter.filterPlainText("[MAN]: Hello."))
        assertEquals("John: Where are you?", SubtitleSdhFilter.filterPlainText("John: Where are you?"))
        assertEquals("MAN:Wait!", SubtitleSdhFilter.filterPlainText("MAN:Wait!"))
    }

    @Test
    fun defaultModeOnlyRemovesUppercaseParentheticalDescriptions() {
        assertNull(SubtitleSdhFilter.filterPlainText("(LOUD BANG)"))
        assertEquals("(softly) Hello.", SubtitleSdhFilter.filterPlainText("(softly) Hello."))
        assertEquals("(1984)", SubtitleSdhFilter.filterPlainText("(1984)"))
    }

    @Test
    fun removesEmptySdhLinesButKeepsOtherText() {
        assertEquals(
            "\u266A suspenseful music \u266A\nWe should go.",
            SubtitleSdhFilter.filterPlainText("[door closes]\n\u266A suspenseful music \u266A\nWe should go.")
        )
        assertEquals("Hello.", SubtitleSdhFilter.filterPlainText("- [door closes]\nHello."))
    }
}
