package com.nuvio.tv.data.repository

import android.content.Context
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.remote.api.AddonApi
import com.nuvio.tv.data.remote.dto.CatalogResponseDto
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.test.runTest
import io.mockk.coVerify
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.Response

class CatalogRepositoryMalformedEntryTest {
    @Test
    fun `catalog skips entries without id or name and preserves pagination count`() = runTest {
        val payload =
            """
            {
              "metas": [
                { "id": "tt1", "type": "series", "name": "First" },
                { "id": "tt2", "type": "series" },
                null,
                { "id": " ", "type": "series", "name": "Missing ID" },
                { "id": "tt3", "type": "series", "name": "  Third  " }
              ]
            }
            """.trimIndent()
        val moshi = Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()
        val response = moshi.adapter(CatalogResponseDto::class.java).fromJson(payload)!!
        val api = mockk<AddonApi>()
        coEvery { api.getCatalog(any()) } returns Response.success(response)
        val layoutPrefs = mockk<LayoutPreferenceDataStore> {
            every { customPosterUrlPattern } returns flowOf("")
        }
        val repository = CatalogRepositoryImpl(
            context = mockk<Context>(relaxed = true),
            api = api,
            layoutPreferenceDataStore = layoutPrefs
        )

        val result = repository.getCatalog(
            addonBaseUrl = "https://addon.example",
            addonId = "addon",
            addonName = "Addon",
            catalogId = "catalog",
            catalogName = "Catalog",
            type = "series",
            skip = 10,
            skipStep = 100,
            extraArgs = emptyMap(),
            supportsSkip = true
        ).last() as NetworkResult.Success

        assertEquals(listOf("tt1", "tt3"), result.data.items.map { it.id })
        assertEquals(listOf("First", "  Third  "), result.data.items.map { it.name })
        assertEquals(15, result.data.nextSkip)
    }

    @Test
    fun `cached catalog picks up a changed custom poster pattern without refetching`() = runTest {
        val payload = """{ "metas": [ { "id": "tt1", "type": "movie", "name": "First", "poster": "https://addon/p.jpg" } ] }"""
        val response = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
            .adapter(CatalogResponseDto::class.java).fromJson(payload)!!
        val api = mockk<AddonApi>()
        coEvery { api.getCatalog(any()) } returns Response.success(response)
        val pattern = MutableStateFlow("")
        val repository = CatalogRepositoryImpl(
            context = mockk<Context>(relaxed = true),
            api = api,
            layoutPreferenceDataStore = mockk { every { customPosterUrlPattern } returns pattern }
        )
        suspend fun poster() = (repository.getCatalog(
            addonBaseUrl = "https://addon.example",
            addonId = "addon",
            addonName = "Addon",
            catalogId = "catalog",
            catalogName = "Catalog",
            type = "movie",
            skip = 0,
            skipStep = 100,
            extraArgs = emptyMap(),
            supportsSkip = false
        ).last() as NetworkResult.Success).data.items.single().poster

        assertEquals("https://addon/p.jpg", poster())
        pattern.value = "https://posters.example/{imdb_id}.jpg"
        assertEquals("https://posters.example/tt1.jpg", poster())
        coVerify(exactly = 1) { api.getCatalog(any()) }
    }
}
