package com.copilotodriverpro.recognition

/**
 * Localiza o CARD da oferta da 99 dentro do texto da tela, ignorando o que está em volta.
 *
 * A tela da 99 tem mapa, selos de ganhos, barras e outros textos ao fundo. Antes, a oferta era
 * julgada pela tela inteira: qualquer palavra do fundo ("ganhos", "saldo", "procurando"...) a
 * descartava, e um valor solto do topo (ganhos do dia) podia ser lido como o valor da corrida.
 *
 * Aqui o card é achado pela ESTRUTURA que a 99 sempre mostra:
 *   R$11,60            <- valor da corrida (linha só com o valor)
 *   R$1,82/km          <- R$/km que a própria 99 exibe
 *   ...
 *   5 min (978 m)      <- embarque
 *   Rua A, 150
 *   10 min (5,4 km)    <- viagem
 *   Rua B, 493
 */
internal object NineNineCard {

    private val PURE_FARE = Regex(
        """^R\$\s*([0-9]{1,3}(?:\.[0-9]{3})*[.,][0-9]{2}|[0-9]+[.,][0-9]{2})$"""
    )
    private val PER_KM = Regex("""R\$\s*[0-9]+[.,][0-9]{1,2}\s*/\s*km""", RegexOption.IGNORE_CASE)

    // "5 min (978 m)", "13 minutos (9.4 km)", "(9 min 2,9 km) Rua..."
    private val LEG = Regex(
        """^\(?\s*[0-9]+\s*(?:min(?:utos?)?|h)\.?\s*[(\s]\s*[0-9]+(?:[.,][0-9]+)?\s*(?:km|m)(?![A-Za-zÀ-ú])""",
        RegexOption.IGNORE_CASE
    )

    // Só o que é de corrida em andamento/encerrada. As palavras do fundo da tela (ganhos, saldo,
    // procurando...) NÃO entram aqui de propósito: não valem dentro de um card bem formado.
    private val NOT_AN_OFFER = listOf(
        "em andamento", "finalizar", "chegou ao", "iniciar corrida", "avalie", "avaliar"
    )

    private fun canon(raw: String): List<String> {
        var t = OfferTextExtractor.normalize(raw)
        // Nós quebrados pelo app: "R$" / "11,60", "5 min" / "(978 m)", "(978" / "m)".
        t = t.replace(Regex("""R\$[ \t]*\n[ \t]*(?=[0-9])""")) { "R\$" }
        t = t.replace(Regex("""(\b[0-9]+[ \t]*(?:min(?:utos?)?|h))[ \t]*\n[ \t]*(?=\()""", RegexOption.IGNORE_CASE)) {
            it.groupValues[1] + " "
        }
        t = t.replace(Regex("""(\([ \t]*[0-9]+(?:[.,][0-9]+)?)[ \t]*\n[ \t]*(?=(?:km|m)\b)""", RegexOption.IGNORE_CASE)) {
            it.groupValues[1] + " "
        }
        return t.lines().map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun fareValue(line: String): Double =
        PURE_FARE.find(line)?.groupValues?.get(1)?.let { OfferTextExtractor.parsePtNumber(it) } ?: 0.0

    /**
     * Devolve só o texto do card (do valor da corrida até o último endereço) ou null se a tela
     * não tem um card da 99 completo (valor + R$/km ou "corridas" + dois trechos "N min (X km)").
     */
    fun extract(raw: String): String? {
        val lines = canon(raw)
        val legIdx = lines.indices.filter { LEG.containsMatchIn(lines[it]) }
        if (legIdx.size < 2) return null
        val first = legIdx.first()

        // Valor da corrida: a linha só-com-valor que vem logo antes do "R$x,xx/km"; sem ela, o
        // maior valor solto acima do 1º trecho (a corrida sempre vale mais que taxas e extras).
        val fares = (maxOf(0, first - 14) until first).filter { PURE_FARE.matches(lines[it]) }
        val fareIdx = fares.firstOrNull { i ->
            (i + 1..minOf(i + 2, lines.lastIndex)).any { PER_KM.containsMatchIn(lines[it]) }
        } ?: fares.maxByOrNull { fareValue(lines[it]) } ?: return null

        // Fim do card: antes do próximo valor solto (lista com vários cards) ou 2 linhas depois
        // do último trecho (o endereço), sem arrastar o rodapé da tela.
        val nextFare = (first + 1 until lines.size).firstOrNull { PURE_FARE.matches(lines[it]) }
        val end = nextFare ?: minOf(lines.size, legIdx.last() + 3)

        val card = lines.subList(fareIdx, end).joinToString("\n")
        val low = card.lowercase()
        val looksLikeCard = PER_KM.containsMatchIn(card) || low.contains("corridas") || low.contains("pgto")
        if (!looksLikeCard) return null
        if (NOT_AN_OFFER.any { low.contains(it) }) return null
        return card
    }
}
