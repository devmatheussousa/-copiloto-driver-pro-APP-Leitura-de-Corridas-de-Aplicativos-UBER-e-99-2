package com.copilotodriverpro.recognition

import com.copilotodriverpro.analyzer.OfferEvaluation
import com.copilotodriverpro.models.RideOffer

/**
 * Textos que indicam o que aconteceu DEPOIS que o card da oferta saiu da tela.
 * O app só LÊ a tela (não clica em nada): a aceitação é deduzida do toque no botão Aceitar
 * e/ou da tela de corrida em andamento que aparece em seguida.
 */
object TripScreenMarkers {

    /** Tela de corrida em andamento (indo buscar / passageiro a bordo), Uber e 99. */
    private val IN_PROGRESS = listOf(
        "cheguei", "informar chegada", "avisar chegada", "iniciar viagem", "iniciar corrida",
        "a caminho do passageiro", "indo buscar", "buscar passageiro", "ir até o passageiro",
        "ir ate o passageiro", "ligar para o passageiro", "cancelar viagem", "cancelar corrida",
        "passageiro a bordo", "finalizar viagem", "finalizar corrida", "chegou ao embarque"
    )

    /** A oferta não foi concretizada (outro motorista pegou, expirou, passageiro cancelou). */
    private val FAILED = listOf(
        "não está mais disponível", "nao esta mais disponivel", "não está disponível",
        "outro motorista", "oferta expirou", "viagem expirou", "expirou", "perdeu a corrida",
        "foi cancelad", "cancelada pelo passageiro", "passageiro cancelou", "indisponível",
        "indisponivel"
    )

    fun tripInProgress(text: String): Boolean {
        val t = text.lowercase()
        return IN_PROGRESS.any { t.contains(it) }
    }

    fun failed(text: String): Boolean {
        val t = text.lowercase()
        return FAILED.any { t.contains(it) }
    }
}

/**
 * Sinal de aceite que NÃO depende do texto dos botões: depois que a oferta sumiu, o endereço de
 * embarque/destino dela aparece na tela do app (a tela da corrida aceita mostra esses endereços;
 * uma oferta recusada ou expirada volta ao mapa, sem eles). Útil na 99, cujo botão/tela de corrida
 * muda de texto e muitas vezes não gera evento de toque. Lógica pura (sem Android), testável.
 */
internal object TripAddressMatch {
    private const val MIN_KEY = 10
    private const val MAX_KEY = 24
    private val ACCENTS = Regex("\\p{Mn}+")

    /** minúsculas, sem acento, só letras/dígitos separados por 1 espaço ("R. Flores, 150" -> "r flores 150"). */
    fun normalize(s: String): String {
        val d = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD).replace(ACCENTS, "")
        val sb = StringBuilder(d.length)
        var lastSpace = true
        for (ch in d) {
            if (ch.isLetterOrDigit()) { sb.append(ch); lastSpace = false }
            else if (!lastSpace) { sb.append(' '); lastSpace = true }
        }
        return sb.toString().trim()
    }

    /** Começo do endereço (o app às vezes corta o fim com "..."); null se for curto demais para ser confiável. */
    fun keyOf(address: String?): String? =
        address?.let { normalize(it) }?.takeIf { it.length >= MIN_KEY }?.take(MAX_KEY)?.trim()

    /** A tela [screenText] é a da corrida desta [offer] (endereço visível e já não é o card da oferta)? */
    fun appears(screenText: String, offer: RideOffer): Boolean {
        val keys = listOfNotNull(keyOf(offer.pickupText), keyOf(offer.destinationText))
        if (keys.isEmpty()) return false
        val t = normalize(screenText)
        if (keys.none { t.contains(it) }) return false
        // Ainda é o card da oferta? (linha exata "Aceitar"/"Selecionar"… ou o card completo da 99)
        val labels = RideScreenClassifier.acceptLabels(offer.platform)
        val acceptLine = screenText.lineSequence().any { it.trim().lowercase() in labels }
        val card99 = offer.platform == com.copilotodriverpro.models.SupportedPlatform.NINE_NINE &&
            NineNineCard.extract(screenText) != null
        return !acceptLine && !card99
    }
}

/**
 * Decide quando uma oferta virou corrida ACEITA. Lógica pura (sem Android), testável.
 *
 * Caminhos de confirmação:
 *  1. Toque em Aceitar -> oferta some -> aparece a tela de corrida em andamento (confirma na hora)
 *     ou passam [CLICK_SETTLE_MS] sem aviso de falha (a Uber às vezes só fecha o card).
 *  2. Sem evento de toque (card da 99 por cima de outro app): a oferta some e, em até
 *     [WINDOW_MS], aparece a tela de corrida em andamento — desde que NÃO houvesse corrida em
 *     andamento antes da oferta (senão uma oferta expirada seria confundida com aceite).
 *  3. Sem toque e sem texto conhecido: depois que a oferta some, o ENDEREÇO dela aparece na tela do
 *     app (tela da corrida aceita) — ver [TripAddressMatch].
 *  Recusar/Fechar/Dispensar ou aviso de falha cancelam.
 */
class RideAcceptTracker(
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val onAccepted: (RideOffer, OfferEvaluation?) -> Unit
) {

    private class Pending(
        val offer: RideOffer,
        val evaluation: OfferEvaluation?,
        val clicked: Boolean,
        val tripActiveBefore: Boolean,
        var goneAt: Long?,
        val since: Long
    )

    private var pending: Pending? = null
    private var lastTripSeenAt = 0L
    private var tripActiveSnapshot = false
    private val confirmed = HashSet<String>()
    private val declined = HashSet<String>()

    /** Chamado quando uma oferta nova entra no card: guarda se já havia corrida em andamento. */
    @Synchronized
    fun onOfferShown(offer: RideOffer) {
        tripActiveSnapshot = clock() - lastTripSeenAt < TRIP_RECENT_MS
    }

    /** Motorista tocou em Aceitar/Selecionar. */
    @Synchronized
    fun onAcceptClick(offer: RideOffer, evaluation: OfferEvaluation?) {
        if (offer.id in confirmed) return
        pending = Pending(offer, evaluation, clicked = true, tripActiveBefore = tripActiveSnapshot,
            goneAt = null, since = clock())
    }

    /** Motorista tocou em Recusar/Fechar/Dispensar. */
    @Synchronized
    fun onDeclineClick(offer: RideOffer?) {
        if (offer == null) { pending = null; return }
        if (declined.size > 200) declined.clear()
        declined.add(offer.id)
        if (pending?.offer?.id == offer.id) pending = null
    }

    /** O card da oferta saiu da tela (por toque, tempo esgotado ou troca de app). */
    @Synchronized
    fun onOfferGone(offer: RideOffer, evaluation: OfferEvaluation?) {
        if (offer.id in confirmed || offer.id in declined) return
        val cur = pending
        if (cur != null && cur.offer.id == offer.id) {
            if (cur.goneAt == null) cur.goneAt = clock()
            return
        }
        pending = Pending(offer, evaluation, clicked = false, tripActiveBefore = tripActiveSnapshot,
            goneAt = clock(), since = clock())
    }

    /** Texto lido do Uber/99 (qualquer tela). */
    @Synchronized
    fun onScreenText(text: String) {
        val now = clock()
        val inProgress = TripScreenMarkers.tripInProgress(text)
        val p = pending
        if (p == null) {
            if (inProgress) lastTripSeenAt = now
            return
        }
        if (now - p.since > WINDOW_MS + SETTLE_GRACE_MS) { pending = null; return }
        if (TripScreenMarkers.failed(text)) { pending = null; return }
        val gone = p.goneAt
        if (gone != null && now - gone >= ADDRESS_SETTLE_MS && TripAddressMatch.appears(text, p.offer)) {
            lastTripSeenAt = now
            confirm(p)
            return
        }
        if (inProgress) {
            lastTripSeenAt = now
            if (p.goneAt != null && (p.clicked || !p.tripActiveBefore)) confirm(p)
        }
    }

    /** Chamado periodicamente (vigia): confirma toque sem aviso de falha e limpa pendências velhas. */
    @Synchronized
    fun tick() {
        val p = pending ?: return
        val now = clock()
        val gone = p.goneAt
        if (p.clicked && gone != null && now - gone >= CLICK_SETTLE_MS) { confirm(p); return }
        if (now - p.since > WINDOW_MS + SETTLE_GRACE_MS) pending = null
    }

    private fun confirm(p: Pending) {
        pending = null
        if (confirmed.size > 200) confirmed.clear()
        if (!confirmed.add(p.offer.id)) return
        onAccepted(p.offer, p.evaluation)
    }

    companion object {
        /** Depois do toque em Aceitar sem aviso de falha, a corrida vale. */
        const val CLICK_SETTLE_MS = 5_000L
        /** Janela para a tela de corrida em andamento aparecer depois que a oferta sumiu. */
        const val WINDOW_MS = 25_000L
        const val SETTLE_GRACE_MS = 2_000L
        /** O endereço da oferta só vale como aceite se continuar na tela depois desse tempo (descarta o card se apagando). */
        const val ADDRESS_SETTLE_MS = 1_200L
        /** Corrida em andamento vista há pouco = já havia viagem antes da oferta. */
        const val TRIP_RECENT_MS = 8_000L
    }
}
