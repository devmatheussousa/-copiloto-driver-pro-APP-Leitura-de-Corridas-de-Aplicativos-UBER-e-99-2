package com.copilotodriverpro.state

import com.copilotodriverpro.CopilotApp
import com.copilotodriverpro.analyzer.OfferEvaluation
import com.copilotodriverpro.analyzer.Recommendation
import com.copilotodriverpro.analyzer.RecommendationState
import com.copilotodriverpro.analyzer.RideAnalyzer
import com.copilotodriverpro.history.HistoryItem
import com.copilotodriverpro.models.LatLang
import com.copilotodriverpro.models.RegionCheck
import com.copilotodriverpro.models.RideOffer
import com.copilotodriverpro.settings.DriverSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** O que o painel flutuante e a Activity exibem, sempre derivado do estado atual. */
data class CopilotUiState(
    val analyzing: Boolean = false,             // amarelo: processando
    val alert: Boolean = false,                 // vermelho: alerta importante
    val offer: RideOffer? = null,
    val evaluation: OfferEvaluation? = null,
    val regionCheck: RegionCheck? = null,
    val hasRegions: Boolean = true,
    val lastMessage: String? = null,
    // Diagnóstico do leitor: o que o AccessibilityService capturou da tela.
    val capturedText: String? = null,
    val capturedPackage: String? = null,
    val captureCount: Int = 0,
    // Motivo pelo qual a última tela da 99 foi aceita ou descartada (só diagnóstico).
    val captureNote: String? = null,
    // Último texto da 99 que tinha valor em R$ (não é apagado pelas telas seguintes).
    val offerCandidateText: String? = null
)

/**
 * Estado compartilhado do processo: AccessibilityService e OCR alimentam,
 * OverlayService e UI consomem. Thread-safe via StateFlow.
 */
class RideStateStore {

    private val scope = CoroutineScope(Dispatchers.Default)
    private val _ui = MutableStateFlow(CopilotUiState())
    val ui: StateFlow<CopilotUiState> = _ui.asStateFlow()

    /**
     * "Licença" de exibição do card: só vale enquanto o botão da oferta foi visto na
     * tela há pouco tempo. Quem renova é o leitor (eventos + vigia). Quando vence, o card
     * some mesmo que o estado ainda guarde a oferta (leitura atrasada, corrida de threads).
     */
    @Volatile var offerLeaseUntil: Long = 0L
        private set

    fun renewOfferLease(durationMs: Long = LEASE_MS) {
        offerLeaseUntil = System.currentTimeMillis() + durationMs
    }

    fun expireOfferLease() {
        offerLeaseUntil = 0L
    }

    fun setAnalyzing(value: Boolean) {
        _ui.update { it.copy(analyzing = value) }
    }

    /**
     * Diagnóstico do leitor: registra o texto bruto capturado da tela para que
     * o usuário possa ver, no app, exatamente o que o AccessibilityService leu.
     * É o principal meio de descobrir por que o reconhecimento não achou a oferta.
     */
    fun publishCapture(text: String, packageName: String?) {
        _ui.update {
            it.copy(
                capturedText = text.take(4000),
                capturedPackage = packageName,
                captureCount = it.captureCount + 1
            )
        }
    }

    /** Diagnóstico: guarda o último texto da 99 com valor em R$, mesmo que a tela mude depois. */
    fun publishOfferCandidate(text: String) {
        _ui.update { it.copy(offerCandidateText = text.take(1500)) }
    }

    /** Diagnóstico: por que a última tela lida (99) foi aceita ou descartada. */
    fun publishCaptureNote(note: String) {
        _ui.update { it.copy(captureNote = note) }
    }

    /**
     * Publica uma oferta já avaliada. A avaliação é recalculada aqui para que
     * qualquer fonte (acessibilidade, OCR de imagem, entrada manual) passe pelo
     * mesmo caminho de cálculo.
     */
    fun publish(
        offer: RideOffer,
        settings: DriverSettings,
        regions: List<com.copilotodriverpro.models.Region>,
        pickupCoord: LatLang? = null,
        destinationCoord: LatLang? = null,
        /** true = os endereços ainda estão sendo localizados; [applyRegionCheck] completa depois. */
        resolvingPlaces: Boolean = false
    ) {
        val check = com.copilotodriverpro.regions.GeoUtils
            .checkOffer(regions, pickupCoord, destinationCoord)
            .copy(resolving = resolvingPlaces)
        val evaluation = RideAnalyzer.evaluateOffer(offer, settings, check)

        val alert = evaluation.recommendation == Recommendation.REJECT ||
            evaluation.recommendationResult?.state == RecommendationState.RECUSAR

        // Atualização atômica que PRESERVA o diagnóstico (texto capturado etc.):
        // antes, a atribuição direta apagava esses campos e podia corrida com outras leituras.
        _ui.update {
            it.copy(
                analyzing = false,
                alert = alert,
                offer = offer,
                evaluation = evaluation,
                regionCheck = check,
                hasRegions = regions.any { r -> r.active },
                lastMessage = null
            )
        }

        // Salva no histórico de ofertas
        scope.launch {
            runCatching {
                CopilotApp.instance.historyRepository.add(HistoryItem(offer, evaluation))
            }
        }

        // Turno automático: primeira corrida detectada com o recurso ligado
        // inicia o turno (o repositório bloqueia duplicados e turnos relâmpago).
        if (settings.copilotEnabled && settings.autoShiftEnabled) {
            scope.launch {
                runCatching { CopilotApp.instance.shiftRepository.autoStart() }
            }
        }
    }

    /**
     * Completa a oferta ATUAL (ex.: dados vindos do OCR) somente se ela ainda for a mesma.
     * Verificação e troca são atômicas: se a oferta sumiu no meio do caminho, nada é
     * republicado (impede que uma leitura atrasada ressuscite uma oferta antiga).
     */
    fun replaceOfferIfCurrent(expectedId: String, merged: RideOffer, settings: DriverSettings): Boolean {
        var applied = false
        _ui.update { cur ->
            if (cur.offer?.id != expectedId) {
                applied = false
                cur
            } else {
                val evaluation = RideAnalyzer.evaluateOffer(merged, settings, cur.regionCheck)
                val alert = evaluation.recommendation == Recommendation.REJECT ||
                    evaluation.recommendationResult?.state == RecommendationState.RECUSAR
                applied = true
                cur.copy(offer = merged, evaluation = evaluation, alert = alert)
            }
        }
        return applied
    }

    /**
     * Completa o resultado de REGIÃO de uma oferta que já está no card (os endereços demoraram a
     * ser localizados). Só vale se a oferta ainda for a mesma: nunca ressuscita oferta antiga.
     * Reavalia com o mesmo motor, então a decisão (aceitar/recusar) já considera a região.
     */
    fun applyRegionCheck(
        offerId: String,
        settings: DriverSettings,
        regions: List<com.copilotodriverpro.models.Region>,
        pickupCoord: LatLang?,
        destinationCoord: LatLang?
    ): Boolean {
        var applied = false
        _ui.update { cur ->
            val offer = cur.offer
            if (offer == null || offer.id != offerId) {
                applied = false
                cur
            } else {
                val check = com.copilotodriverpro.regions.GeoUtils
                    .checkOffer(regions, pickupCoord, destinationCoord)
                val evaluation = RideAnalyzer.evaluateOffer(offer, settings, check)
                val alert = evaluation.recommendation == Recommendation.REJECT ||
                    evaluation.recommendationResult?.state == RecommendationState.RECUSAR
                applied = true
                cur.copy(evaluation = evaluation, regionCheck = check, alert = alert)
            }
        }
        return applied
    }

    fun clear() {
        expireOfferLease()
        _ui.update {
            it.copy(
                analyzing = false,
                alert = false,
                offer = null,
                evaluation = null,
                regionCheck = null
            )
        }
    }

    companion object {
        /**
         * Rede de segurança (dead-man switch): o vigia renova a licença a cada ~150 ms enquanto a
         * oferta está na tela. 200 ms era menor que uma varredura lenta da árvore de acessibilidade,
         * e a licença vencia no meio da oferta: o card sumia e voltava (piscava).
         */
        const val LEASE_MS = 1_500L
    }
}
