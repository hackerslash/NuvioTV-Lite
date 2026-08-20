package com.nuvio.tv.domain.repository

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.domain.model.Addon
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

interface AddonRepository {
    fun getInstalledAddons(): Flow<List<Addon>>

    // Settled snapshot for one-shot reads; avoids the partial emission of getInstalledAddons.
    suspend fun getResolvedInstalledAddons(): List<Addon> = getInstalledAddons().first()

    suspend fun fetchAddon(baseUrl: String): NetworkResult<Addon>
    suspend fun addAddon(url: String)
    suspend fun removeAddon(url: String)
    suspend fun setAddonOrder(urls: List<String>)
    suspend fun setAddonEnabled(url: String, enabled: Boolean)
}
