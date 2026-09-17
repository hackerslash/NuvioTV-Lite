package com.nuvio.tv.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LruCacheMapTest {

    @Test
    fun `evicts past max size`() {
        val cache = lruCacheMap<Int, String>(2)
        cache[1] = "a"
        cache[2] = "b"
        cache[3] = "c"

        assertEquals(2, cache.size)
        assertFalse(cache.containsKey(1))
        assertTrue(cache.containsKey(3))
    }

    @Test
    fun `a read makes an entry the most recently used`() {
        val cache = lruCacheMap<Int, String>(2)
        cache[1] = "a"
        cache[2] = "b"
        cache[1]
        cache[3] = "c"

        assertTrue(cache.containsKey(1))
        assertFalse(cache.containsKey(2))
    }
}
