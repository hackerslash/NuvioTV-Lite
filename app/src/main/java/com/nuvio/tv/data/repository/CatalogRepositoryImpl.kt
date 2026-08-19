package com.nuvio.tv.data.repository

import android.content.Context
import android.util.Log
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.network.safeApiCall
import com.nuvio.tv.data.mapper.toDomainOrNull
import com.nuvio.tv.data.remote.api.AddonApi
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.repository.CatalogRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CatalogRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: AddonApi
) : CatalogRepository {
    companion object {
        private const val TAG = "CatalogRepository"
        // Short in-memory TTL: skip refetching the same row on re-collect (back-nav, config change).
        private const val CATALOG_TTL_MS = 5L * 60 * 1000
        private const val MAX_CATALOG_CACHE_ENTRIES = 48
    }

    private data class CachedRow(val row: CatalogRow, val expiresAtMs: Long) {
        fun isExpired(): Boolean = System.currentTimeMillis() >= expiresAtMs
    }

    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Both keyed by the fully-resolved catalog URL (captures all request params).
    private val catalogCache = java.util.Collections.synchronizedMap(
        object : LinkedHashMap<String, CachedRow>(MAX_CATALOG_CACHE_ENTRIES, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedRow>?): Boolean =
                size > MAX_CATALOG_CACHE_ENTRIES
        }
    )
    private val inFlight = ConcurrentHashMap<String, Deferred<NetworkResult<CatalogRow>>>()

    override fun getCatalog(
        addonBaseUrl: String,
        addonId: String,
        addonName: String,
        catalogId: String,
        catalogName: String,
        type: String,
        skip: Int,
        skipStep: Int,
        extraArgs: Map<String, String>,
        supportsSkip: Boolean
    ): Flow<NetworkResult<CatalogRow>> = flow {
        val url = buildCatalogUrl(addonBaseUrl, type, catalogId, skip, extraArgs)

        catalogCache[url]?.let { cached ->
            if (!cached.isExpired()) {
                emit(NetworkResult.Success(cached.row))
                return@flow
            }
            catalogCache.remove(url)
        }

        emit(NetworkResult.Loading)

        Log.d(
            TAG,
            "Fetching catalog addonId=$addonId addonName=$addonName type=$type catalogId=$catalogId skip=$skip skipStep=$skipStep supportsSkip=$supportsSkip url=$url"
        )

        // Dedup concurrent identical requests; mapping runs off-main via repositoryScope.
        val deferred = inFlight.getOrPut(url) {
            repositoryScope.async {
                try {
                    when (val result = safeApiCall(context) { api.getCatalog(url) }) {
                        is NetworkResult.Success -> {
                            val rawItemCount = result.data.metas.size
                            val items = result.data.metas
                                .mapNotNull { it?.toDomainOrNull(type, addonBaseUrl) }
                                .distinctBy { it.id }
                            Log.d(
                                TAG,
                                "Catalog fetch success addonId=$addonId type=$type catalogId=$catalogId items=${items.size}"
                            )

                            val catalogRow = CatalogRow(
                                addonId = addonId,
                                addonName = addonName,
                                addonBaseUrl = addonBaseUrl,
                                catalogId = catalogId,
                                catalogName = catalogName,
                                type = ContentType.fromString(type),
                                rawType = type,
                                items = items,
                                isLoading = false,
                                hasMore = supportsSkip && rawItemCount > 0,
                                currentPage = if (skipStep > 0) skip / skipStep else 0,
                                supportsSkip = supportsSkip,
                                skipStep = skipStep,
                                nextSkip = if (supportsSkip && rawItemCount > 0) skip + rawItemCount else skip,
                                extraArgs = extraArgs
                            )
                            catalogCache[url] = CachedRow(catalogRow, System.currentTimeMillis() + CATALOG_TTL_MS)
                            NetworkResult.Success(catalogRow)
                        }
                        is NetworkResult.Error -> {
                            Log.w(
                                TAG,
                                "Catalog fetch failed addonId=$addonId type=$type catalogId=$catalogId code=${result.code} message=${result.message} url=$url"
                            )
                            result
                        }
                        NetworkResult.Loading -> NetworkResult.Loading
                    }
                } finally {
                    inFlight.remove(url)
                }
            }
        }
        emit(deferred.await())
    }

    private fun buildCatalogUrl(
        baseUrl: String,
        type: String,
        catalogId: String,
        skip: Int,
        extraArgs: Map<String, String>
    ): String {
        val trimmedBase = baseUrl.trimEnd('/')
        val queryStart = trimmedBase.indexOf('?')
        val basePath = if (queryStart >= 0) trimmedBase.substring(0, queryStart).trimEnd('/') else trimmedBase
        val baseQuery = if (queryStart >= 0) trimmedBase.substring(queryStart) else ""

        val catalogPath = if (extraArgs.isEmpty()) {
            if (skip > 0) {
                "$basePath/catalog/$type/$catalogId/skip=$skip.json"
            } else {
                "$basePath/catalog/$type/$catalogId.json"
            }
        } else {
            val allArgs = LinkedHashMap<String, String>()
            allArgs.putAll(extraArgs)

            if (!allArgs.containsKey("skip") && skip > 0) {
                allArgs["skip"] = skip.toString()
            }

            val encodedArgs = allArgs.entries.joinToString("&") { (key, value) ->
                "${encodeArg(key)}=${encodeArg(value)}"
            }

            "$basePath/catalog/$type/$catalogId/$encodedArgs.json"
        }

        return catalogPath + baseQuery
    }

    private fun encodeArg(value: String): String {
        return URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    }
}
