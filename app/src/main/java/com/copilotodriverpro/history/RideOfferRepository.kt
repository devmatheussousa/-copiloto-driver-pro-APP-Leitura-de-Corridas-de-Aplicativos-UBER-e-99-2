package com.copilotodriverpro.history

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.copilotodriverpro.analyzer.OfferEvaluation
import com.copilotodriverpro.analyzer.RecommendationState
import com.copilotodriverpro.models.RideOffer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.historyStore by preferencesDataStore(name = "copiloto_offer_history")

data class HistoryItem(
    val offer: RideOffer,
    val evaluation: OfferEvaluation?
)

data class DailySummary(
    val totalAnalyzed: Int,
    val totalAccepted: Int,
    val totalRejected: Int,
    val estimatedNetEarnings: Double
)

/**
 * Repositório para o Histórico de Ofertas analisadas e Resumo Financeiro Diário.
 */
class RideOfferRepository(private val context: Context) {

    val history: Flow<List<HistoryItem>> = context.historyStore.data.map { p ->
        decode(p[KEY_HISTORY] ?: "[]")
    }

    suspend fun add(item: HistoryItem) {
        val current = decodeFirst()
        val updated = (listOf(item) + current).take(100) // mantém últimas 100
        context.historyStore.edit { it[KEY_HISTORY] = encode(updated) }
    }

    suspend fun clearHistory() {
        context.historyStore.edit { it[KEY_HISTORY] = "[]" }
    }

    suspend fun getDailySummary(): DailySummary {
        val items = decodeFirst()
        val totalAnalyzed = items.size
        val totalAccepted = items.count { it.evaluation?.recommendationResult?.state == RecommendationState.ACEITAR }
        val totalRejected = items.count { it.evaluation?.recommendationResult?.state == RecommendationState.RECUSAR }
        val estimatedNet = items.sumOf { it.evaluation?.financialMetrics?.netEarnings ?: 0.0 }

        return DailySummary(
            totalAnalyzed = totalAnalyzed,
            totalAccepted = totalAccepted,
            totalRejected = totalRejected,
            estimatedNetEarnings = estimatedNet
        )
    }

    private suspend fun decodeFirst(): List<HistoryItem> {
        val json = context.historyStore.data.map { p -> p[KEY_HISTORY] ?: "[]" }.first()
        return decode(json)
    }

    companion object {
        private val KEY_HISTORY = stringPreferencesKey("history_json")

        fun encode(items: List<HistoryItem>): String {
            val arr = JSONArray()
            items.forEach { item ->
                val o = JSONObject()
                o.put("fare", item.offer.fare ?: 0.0)
                o.put("tripKm", item.offer.tripDistanceKm ?: 0.0)
                o.put("durationMin", item.offer.durationMin ?: 0.0)
                o.put("platform", item.offer.platform.name)
                o.put("timestamp", item.offer.timestamp)
                item.evaluation?.financialMetrics?.netEarnings?.let {
                    o.put("netEarnings", it)
                }
                item.evaluation?.recommendationResult?.state?.let {
                    o.put("recommendationState", it.name)
                }
                arr.put(o)
            }
            return arr.toString()
        }

        fun decode(json: String): List<HistoryItem> = runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val fare = o.optDouble("fare", 0.0).takeIf { it > 0 }
                val tripKm = o.optDouble("tripKm", 0.0).takeIf { it > 0 }
                val durationMin = o.optDouble("durationMin", 0.0).takeIf { it > 0 }

                val offer = RideOffer(
                    fare = fare,
                    tripDistanceKm = tripKm,
                    durationMin = durationMin,
                    timestamp = o.optLong("timestamp", System.currentTimeMillis())
                )
                HistoryItem(offer, null)
            }
        }.getOrDefault(emptyList())
    }
}
