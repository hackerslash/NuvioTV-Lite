package com.nuvio.tv.core.util

import java.util.Collections

/**
 * Bounded, thread-safe LRU map: access-order LinkedHashMap that evicts the
 * least-recently-used entry past [maxSize], wrapped in a synchronized map.
 */
fun <K, V> lruCacheMap(maxSize: Int): MutableMap<K, V> =
    Collections.synchronizedMap(object : LinkedHashMap<K, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > maxSize
    })
