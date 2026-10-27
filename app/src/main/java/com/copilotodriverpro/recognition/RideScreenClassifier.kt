package com.copilotodriverpro.recognition

import com.copilotodriverpro.models.SupportedPlatform

/**
 * Classifica a tela ativa para diferenciar ofertas reais da Uber/99 de telas
 * normais de mapa, navegação, histórico ou configurações.
 */
object RideScreenClassifier {

    /** Rótulos do botão principal da oferta (a Uber alterna entre "Aceitar" e "Selecionar" e "Viagem"). */
    private val UBER_ACCEPT_LABELS = listOf("aceitar", "selecionar", "viagem")

    /** 99: "Aceitar", "Selecionar", "Solicitar", "Confirmar" (e variações curtas, ex.: "Aceitar corrida"). */
    private val NINE_NINE_ACCEPT_LABELS = listOf("aceitar", "selecionar", "solicitar", "confirmar", "escolher")

    fun isAcceptLabel(raw: String?, platform: SupportedPlatform): Boolean {
        val l = raw?.trim()?.lowercase().orEmpty()
        if (l.isEmpty()) return false
        if (platform == SupportedPlatform.UBER) {
            if (l.startsWith("viagem") && (l.contains("longa") || l.contains("curta") || l.contains(" de ") || l.length > 15)) {
                return false
            }
            return l == "viagem" || l == "aceitar" || l == "selecionar" || l.startsWith("viagem,") || l.startsWith("aceitar,") || l.startsWith("selecionar,")
        }
        val labels = acceptLabels(platform)
        return labels.any { l == it || l.startsWith("$it,") || l.startsWith("$it ") }
    }

    /**
     * O texto/descrição de um nó é o botão de aceitar? Uber: igual ao rótulo (ou rótulo + até 18
     * caracteres). 99: também aceita o rótulo como palavra dentro de um texto curto, porque o
     * botão da 99 costuma trazer mais coisa junto ("Aceitar corrida", "Aceitar por R$ 12,40").
     */
    fun isAcceptText(raw: String?, platform: SupportedPlatform): Boolean {
        return isAcceptLabel(raw, platform) || run {
            val l = raw?.trim()?.lowercase().orEmpty()
            val labels = acceptLabels(platform)
            if (labels.any { label -> l.startsWith(label) && l.length <= 18 }) return@run true
            if (platform == SupportedPlatform.NINE_NINE && l.length <= 40) {
                return@run labels.any { label -> Regex("""(^|[^a-zà-ú])${Regex.escape(label)}([^a-zà-ú]|$)""").containsMatchIn(l) }
            }
            false
        }
    }

    /**
     * 99: a oferta é reconhecida pelo CONTEÚDO do card, com ou sem o botão "Aceitar" na árvore
     * de acessibilidade. Vale quando há um valor em R$ e pelo menos um trecho com km e um com
     * minutos, e a tela não é de ganhos, restrição, corrida em andamento etc.
     */
    fun looks99OfferWithoutButton(text: String): Boolean {
        // Card completo da 99 (valor + R$/km + dois trechos): vale mesmo com mapa, ganhos e outros
        // textos ao fundo, e mesmo sem o botão Aceitar na árvore de acessibilidade.
        if (NineNineCard.extract(text) != null) return true
        val t = text.lowercase()
        if (!Regex("""r\$\s*[0-9]+[.,][0-9]{2}""").containsMatchIn(t)) return false
        // km OU metros ("948 m"): a 99 mostra distâncias curtas em metros.
        if (!Regex("""[0-9]+(?:[.,][0-9]+)?\s*(?:km|m)(?![a-zà-ú])""").containsMatchIn(t)) return false
        if (!Regex("""[0-9]+\s*min""").containsMatchIn(t)) return false
        val notOffer = listOf(
            "em andamento", "finalizar", "chegou ao", "iniciar corrida", "avalie", "avaliar",
            "resumo", "ganhos", "procurando", "você está online", "voce esta online",
            "restritas", "carteira", "extrato", "saldo"
        )
        return notOffer.none { t.contains(it) }
    }

    /** Rótulos de botão de aceite válidos para a plataforma. */
    fun acceptLabels(platform: SupportedPlatform): List<String> =
        if (platform == SupportedPlatform.NINE_NINE) NINE_NINE_ACCEPT_LABELS else UBER_ACCEPT_LABELS

    // SOMENTE os apps de motorista da Uber e da 99 são lidos.
    private val UBER_PACKAGES = setOf("com.ubercab.driver", "com.ubercab")
    private val NINE_NINE_PACKAGES = setOf("com.app99.driver", "com.taxis99", "br.com.taxis99")

    fun isTransportPackage(packageName: String?): Boolean =
        packageName != null && (packageName in UBER_PACKAGES || packageName in NINE_NINE_PACKAGES ||
            looksLike99Package(packageName))

    /**
     * A 99 já trocou o pacote do app de motorista mais de uma vez. Além da lista fixa, qualquer
     * pacote com "99" no nome (com.app99.driver, com.taxis99, br.com.taxis99...) é tratado como 99.
     * Não depende do PackageManager (que no Android 11+ esconde pacotes fora do <queries>).
     */
    fun looksLike99Package(packageName: String?): Boolean =
        packageName != null && !packageName.startsWith("com.ubercab") && packageName.contains("99")

    /** Identifica a plataforma com base no pacote Android ativo. */
    fun identifyPlatform(packageName: String?): SupportedPlatform = when {
        packageName == null -> SupportedPlatform.UNKNOWN
        packageName in UBER_PACKAGES -> SupportedPlatform.UBER
        packageName in NINE_NINE_PACKAGES -> SupportedPlatform.NINE_NINE
        looksLike99Package(packageName) -> SupportedPlatform.NINE_NINE
        else -> SupportedPlatform.UNKNOWN
    }

    /**
     * A 99 já mudou o pacote do app de motorista (com.taxis99 → com.app99.driver → …). Além da
     * lista fixa, reconhece a 99 pelo NOME do app instalado, cobrindo pacotes novos sem atualizar
     * o Copiloto: "99 Motorista", "99 - Motorista", "Táxi 99", "99Pop Motorista", "99 Driver".
     */
    fun looksLike99AppLabel(label: String?): Boolean {
        if (label.isNullOrBlank()) return false
        val l = normalizeLabel(label)
        val has99 = l.contains("99")
        val isDriverish = listOf("motorista", "driver", "taxi", "taxista", "99pop", "ninenine")
            .any { l.contains(it) }
        return has99 && isDriverish
    }

    private fun normalizeLabel(label: String): String =
        label.lowercase()
            .replace("nove e nove", "99")
            .map { ch ->
                when (ch) {
                    'á', 'ã', 'à', 'â', 'ä' -> 'a'
                    'é', 'ê', 'ë' -> 'e'
                    'í' -> 'i'
                    'ó', 'ô', 'õ', 'ö' -> 'o'
                    'ú', 'ü' -> 'u'
                    'ç' -> 'c'
                    else -> ch
                }
            }
            .filterNot { it.isWhitespace() || it == '-' || it == '_' || it == '.' }
            .joinToString("")

    /**
     * Verifica se o texto extraído da tela parece uma oferta real de corrida.
     */
    fun isOfferScreen(text: String, platform: SupportedPlatform): Boolean {
        if (text.isBlank()) return false

        val containsFare = text.contains("R$", ignoreCase = true)
        val containsKm = text.contains("km", ignoreCase = true) || text.contains("quilômetro", ignoreCase = true)
        val containsMin = text.contains("min", ignoreCase = true) || text.contains("h", ignoreCase = true)

        // Telas de navegação/histórico não costumam apresentar o botão de Aceitar/Recusar ou valores soltos de oferta
        val hasAcceptOrRejectAction = text.contains("aceitar", ignoreCase = true) ||
            text.contains("recusar", ignoreCase = true) ||
            text.contains("solicita", ignoreCase = true) ||
            text.contains("chegada", ignoreCase = true) ||
            text.contains("oferta", ignoreCase = true)

        // Se contiver R$ e (km ou min), já tem fortes indícios de ser uma oferta
        val looksLikeOffer = containsFare && (containsKm || containsMin)

        // Uber/99: a oferta só existe enquanto o botão "Aceitar" está na tela. Depois de
        // aceitar (tela de navegação/corrida em andamento) ou quando a oferta expira, o
        // botão some e o card deve sumir junto.
        if (platform != SupportedPlatform.UNKNOWN) {
            // Linha própria "Aceitar" (o botão). Não basta a palavra aparecer em qualquer texto
            // da tela (ex.: "aceitar viagens" na tela "Você está online").
            val hasAcceptButton = text.lineSequence().any { isAcceptText(it, platform) } ||
                (platform == SupportedPlatform.NINE_NINE && looks99OfferWithoutButton(text))
            return looksLikeOffer && hasAcceptButton
        }
        return looksLikeOffer
    }
}
