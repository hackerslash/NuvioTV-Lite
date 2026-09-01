package com.nuvio.tv.ui.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LanguageNameAliasTest {
    @Test
    fun `exact and embedded language names resolve to their code`() {
        assertEquals("pt", resolveLanguageNameAlias("portuguese"))
        assertEquals("pt", resolveLanguageNameAlias("brazilian portuguese"))
        assertEquals("en", resolveLanguageNameAlias("english sdh"))
    }

    @Test
    fun `unknown names do not resolve`() {
        assertNull(resolveLanguageNameAlias("klingon"))
        assertNull(resolveLanguageNameAlias(""))
    }

    @Test
    fun `display names still come from the tokenized name`() {
        assertEquals("Portuguese", languageCodeToName("Portuguese"))
        assertEquals("Spanish", languageCodeToName("spanish"))
        assertEquals("French", languageCodeToName("fre"))
    }
}
