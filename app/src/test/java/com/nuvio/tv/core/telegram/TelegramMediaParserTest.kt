package com.nuvio.tv.core.telegram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TelegramMediaParserTest {

    @Test
    fun `parses international movie release with tags`() {
        val parsed = TelegramMediaParser.parse("Dune.2021.1080p.WEB-DL.Dual.Latino.Castellano.mkv")

        assertEquals("Dune", parsed.cleanTitle)
        assertEquals(2021, parsed.year)
        assertEquals("1080p", parsed.quality)
        assertNull(parsed.season)
        assertNull(parsed.episode)
    }

    @Test
    fun `parses S01E01 series release`() {
        val parsed = TelegramMediaParser.parse(
            "La Casa de Papel S03E05 1080p NF WEB-DL x264.mkv"
        )

        assertEquals("La Casa de Papel", parsed.cleanTitle)
        assertEquals(3, parsed.season)
        assertEquals(5, parsed.episode)
    }

    @Test
    fun `parses spanish 12x09 style episode`() {
        val parsed = TelegramMediaParser.parse("La que se avecina 12x09.avi")

        assertEquals("La que se avecina", parsed.cleanTitle)
        assertEquals(12, parsed.season)
        assertEquals(9, parsed.episode)
    }

    @Test
    fun `parses zero padded 01X08 style`() {
        val parsed = TelegramMediaParser.parse("Perdidos 01X08 WEBRip Castellano")

        assertEquals("Perdidos", parsed.cleanTitle)
        assertEquals(1, parsed.season)
        assertEquals(8, parsed.episode)
    }

    @Test
    fun `parses Temporada-Capitulo descriptive style`() {
        val parsed = TelegramMediaParser.parse(
            "Cuéntame cómo pasó Temporada 24 Capítulo 3.mp4"
        )

        assertEquals("Cuéntame cómo pasó", parsed.cleanTitle)
        assertEquals(24, parsed.season)
        assertEquals(3, parsed.episode)
    }

    @Test
    fun `parses T1 E05 compact style`() {
        val parsed = TelegramMediaParser.parse("Mujeres.T1.E05.mp4")

        assertEquals("Mujeres", parsed.cleanTitle)
        assertEquals(1, parsed.season)
        assertEquals(5, parsed.episode)
    }

    @Test
    fun `maps 2160p to 4K`() {
        val parsed = TelegramMediaParser.parse("El Hoyo 2019 2160p HEVC HDR.mkv")

        assertEquals("4K", parsed.quality)
        assertEquals("El Hoyo", parsed.cleanTitle)
    }

    @Test
    fun `keeps movie without season or year`() {
        val parsed = TelegramMediaParser.parse("Tesis 1080p BluRay Castellano.mkv")

        assertEquals("Tesis", parsed.cleanTitle)
        assertNull(parsed.year)
        assertNull(parsed.season)
        assertNull(parsed.episode)
        assertEquals("1080p", parsed.quality)
    }

    @Test
    fun `does not confuse resolution with episode marker`() {
        val parsed = TelegramMediaParser.parse("Movie 1920x1080 test.mkv")

        assertNull(parsed.season)
        assertNull(parsed.episode)
    }
}
