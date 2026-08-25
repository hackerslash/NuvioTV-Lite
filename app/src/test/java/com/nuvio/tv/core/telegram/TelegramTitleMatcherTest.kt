package com.nuvio.tv.core.telegram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramTitleMatcherTest {

    @Test
    fun `exact title scores perfect`() {
        val score = TelegramTitleMatcher.score("La Casa de Papel", "La Casa de Papel")

        assertTrue(score >= 0.99)
    }

    @Test
    fun `diacritics and case are ignored`() {
        val score = TelegramTitleMatcher.score(
            "El laberinto del fauno",
            "el laberinto del fauno 1080p"
        )

        assertTrue(score >= 0.9)
    }

    @Test
    fun `candidate with one extra word still passes threshold`() {
        val score = TelegramTitleMatcher.score(
            "Dune",
            "Dune Parte Dos"
        )

        assertTrue("score=$score", score in 0.55..1.0)
    }

    @Test
    fun `saga subentry with many extra tokens is penalized below threshold`() {
        val score = TelegramTitleMatcher.score(
            "El Señor de los Anillos",
            "El Señor de los Anillos La Comunidad del Anillo Extendida"
        )

        assertTrue("score=$score", score < 0.75)
    }

    @Test
    fun `unrelated titles score low`() {
        val score = TelegramTitleMatcher.score("Coco", "Mujeres al borde de un ataque de nervios")

        assertTrue("score=$score", score < 0.4)
    }

    @Test
    fun `bestScore picks the strongest of several accepted titles`() {
        val score = TelegramTitleMatcher.bestScore(
            expectedTitles = listOf("Money Heist", "La Casa de Papel"),
            candidateCleanTitle = "La Casa de Papel"
        )

        assertTrue(score >= 0.99)
    }

    @Test
    fun `empty inputs score zero`() {
        assertEquals(0.0, TelegramTitleMatcher.score("", "algo"), 0.001)
        assertEquals(0.0, TelegramTitleMatcher.score("algo", ""), 0.001)
    }
}
