package com.copilotodriverpro.regions

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.copilotodriverpro.models.LatLang
import com.copilotodriverpro.models.Region
import com.copilotodriverpro.models.RegionKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

private val Context.regionStore by androidx.datastore.preferences.preferencesDataStore(name = "copiloto_regions")

/**
 * Regiões de atuação persistidas como JSON no DataStore.
 * Sem servidor: tudo fica no aparelho.
 */
class RegionRepository(private val context: Context) {

    // Com muitos bairros marcados o JSON passa de vários MB: decodificar e comparar na thread
    // principal travava a tela. flowOn move map + distinctUntilChanged para Default.
    val regions: Flow<List<Region>> = context.regionStore.data.map { p ->
        decode(p[KEY_REGIONS] ?: "[]")
    }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun saveAll(regions: List<Region>) {
        context.regionStore.edit { it[KEY_REGIONS] = encode(regions) }
    }

    suspend fun upsert(region: Region) {
        val current = decodeFirst()
        val idx = current.indexOfFirst { it.id == region.id }
        val next = if (idx >= 0) current.toMutableList().apply { set(idx, region) } else current + region
        saveAll(next)
    }

    suspend fun delete(id: String) {
        saveAll(decodeFirst().filterNot { it.id == id })
    }

    suspend fun toggle(id: String, active: Boolean) {
        val current = decodeFirst()
        val idx = current.indexOfFirst { it.id == id }
        if (idx >= 0) {
            saveAll(current.toMutableList().apply { set(idx, current[idx].copy(active = active)) })
        }
    }

    private suspend fun decodeFirst(): List<Region> {
        val json = context.regionStore.data.map { p -> p[KEY_REGIONS] ?: "[]" }.first()
        return decode(json)
    }

    fun newId(): String = UUID.randomUUID().toString()

    companion object {
        private val KEY_REGIONS = stringPreferencesKey("regions_json")

        fun encode(regions: List<Region>): String {
            val arr = JSONArray()
            regions.forEach { r ->
                val o = JSONObject()
                o.put("id", r.id)
                o.put("name", r.name)
                o.put("active", r.active)
                o.put("kind", r.kind.name)
                r.center?.let {
                    o.put("centerLat", it.lat)
                    o.put("centerLng", it.lng)
                }
                o.put("radiusM", r.radiusM)
                val pts = JSONArray()
                r.points.forEach { p ->
                    val po = JSONObject()
                    po.put("lat", p.lat)
                    po.put("lng", p.lng)
                    pts.put(po)
                }
                o.put("points", pts)
                arr.put(o)
            }
            return arr.toString()
        }

        fun decode(json: String): List<Region> = runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val centerLat = o.optDouble("centerLat", Double.NaN)
                Region(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    active = o.optBoolean("active", true),
                    kind = runCatching { RegionKind.valueOf(o.getString("kind")) }.getOrDefault(RegionKind.CIRCLE),
                    center = if (!centerLat.isNaN()) LatLang(centerLat, o.optDouble("centerLng", 0.0)) else null,
                    radiusM = o.optDouble("radiusM", 0.0),
                    points = runCatching {
                        val pts = o.getJSONArray("points")
                        (0 until pts.length()).map { j ->
                            val po = pts.getJSONObject(j)
                            LatLang(po.getDouble("lat"), po.getDouble("lng"))
                        }
                    }.getOrDefault(emptyList())
                )
            }
        }.getOrDefault(emptyList())
    }
}
