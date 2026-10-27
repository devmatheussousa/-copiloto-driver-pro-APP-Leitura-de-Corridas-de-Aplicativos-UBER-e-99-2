package com.copilotodriverpro.recognition

import com.copilotodriverpro.models.DataProvenance
import com.copilotodriverpro.models.OfferIssue
import com.copilotodriverpro.models.RideOffer
import com.copilotodriverpro.models.RideSource
import com.copilotodriverpro.models.StopVerdict
import com.copilotodriverpro.models.SupportedPlatform

/**
 * Extrator único de campos de oferta (Uber, 99 e texto genérico/OCR).
 *
 * Diferenças importantes em relação à versão anterior:
 *  - separa EMBARQUE (km + minutos até o passageiro) de VIAGEM (km + minutos da corrida),
 *    pareando cada distância com o tempo que está colado nela ("6 min (2,3 km)", "1,8 km • 6 min");
 *  - o tempo total para o R$/hora passa a ser embarque + viagem (como o GigU faz);
 *  - ignora valores "R$ 2,38 por km", "R$/h" e bônus "+R$ 3,00" ao escolher o valor da corrida;
 *  - aceita decimal com ponto ("1.9 km", "9.50") sem transformar em 19 / 950.
 */
internal object OfferTextExtractor {

    private val FARE_REGEX = Regex(
        """(?<![+0-9])(?:R\$|\$)\s*([0-9]{1,3}(?:\.[0-9]{3})*[.,][0-9]{2}|[0-9]+[.,][0-9]{2})(?!\s*(?:/|por\s)\s*(?:km|h|hora|min|mi))""",
        RegexOption.IGNORE_CASE
    )
    private val FARE_DOLLAR_REGEX = Regex(
        """(?<![+0-9])\$\s*([0-9]{1,3}(?:\.[0-9]{3})*[.,][0-9]{2}|[0-9]+[.,][0-9]{2})(?!\s*(?:/|por\s)\s*(?:km|h|hora|min|mi))""",
        RegexOption.IGNORE_CASE
    )
    private val BONUS_REGEX = Regex(
        """\+\s*(?:R\$|\$)?\s*([0-9]+[.,][0-9]{2})""",
        RegexOption.IGNORE_CASE
    )
    private val KM_REGEX = Regex("([0-9]+(?:[.,][0-9]{1,2})?)\\s*(?:km|quil[oô]metros?|mi(?:les)?|milhas?)\\b", RegexOption.IGNORE_CASE)

    // A 99 mostra distâncias curtas em METROS: "5 min (948 m)". "min" nunca casa (o "m" não pode
    // ser seguido de letra).
    private val METERS_REGEX = Regex("""(?<![0-9.,])([0-9]{2,4})\s*m(?![A-Za-zÀ-ú])""")

    // 99: "4,83 • 160 corridas" (nota + quantidade de corridas do passageiro).
    private val RATING_RIDES = Regex(
        """(?<![0-9.,])([3-5][.,][0-9]{1,2})(?![0-9])\s*[•·|\-]?\s*([0-9]{1,3}(?:\.[0-9]{3})*)\s*corridas""",
        RegexOption.IGNORE_CASE
    )

    // grupo 1/2 = "1 h 20", "2h", "1 hora e 5 min"; grupo 3 = "18 min", "6 minutos", "8 mins"
    // ATENÇÃO: o 1º ramo NÃO pode ser opcional ("(...)?|..."): um ramo opcional casa vazio e o
    // ramo de minutos ("5 min", "13 minutos") nunca era tentado, então toda duração em minutos sumia.
    private val DURATION_REGEX = Regex(
        """(?:([0-9]{1,2})[ \t]*(?:h(?:oras?)?|hrs?|hours?)(?![A-Za-z])[ \t]*(?:e[ \t]*)?([0-9]{1,2})?[ \t]*(?:min(?:ut[ao]s?)?|mins?)?\b)|([0-9]{1,3})[ \t]*(?:min(?:ut[ao]s?)?|mins?)\b""",
        RegexOption.IGNORE_CASE
    )

    private val RATING_REGEX = Regex(
        """(?:nota|passageiro|★)\s*([0-5][.,][0-9]{1,2})(?![0-9]|\s*(?:km|quil|min))|(?<![0-9.,])([0-5][.,][0-9]{1,2})(?![0-9])\s*★|(?<![0-9.,])([0-5][.,][0-9]{2})(?![0-9])[ \t]*\([ \t]*[0-9]+[ \t]*\)""",
        RegexOption.IGNORE_CASE
    )
    private val RATING_LINE_REGEX = Regex("""^\s*([3-5][.,][0-9]{1,2})(?:\s*\([0-9.,]+\))?\s*$""", RegexOption.MULTILINE)

    private val STOP_CONFIRMED = Regex(
        """(corrida\s+com\s+parada|parada\s+adicional|m[uú]ltiplas?\s+paradas?|[0-9]+[ \t]+paradas?)""",
        RegexOption.IGNORE_CASE
    )
    private val STOP_POSSIBLE = Regex("""\bparadas?\b""", RegexOption.IGNORE_CASE)

    private val PICKUP_LABEL = Regex(
        """embarque|passageiro|buscar|busca|de\s+dist[âa]ncia|retirada""",
        RegexOption.IGNORE_CASE
    )
    private val TRIP_LABEL = Regex("""viagem|corrida|trajeto|destino|percurso""", RegexOption.IGNORE_CASE)

    private const val PAIR_MAX_GAP = 30

    // "+R$ 1,00 incluído": o bônus JÁ está dentro do valor da corrida (não pode ser somado de novo).
    private val BONUS_INCLUDED = Regex("""inclu[íi]d[oa]|incluso|included""", RegexOption.IGNORE_CASE)

    // "R$1,16/km aprox." — indicador que a própria plataforma exibe no card.
    private val PLATFORM_PER_KM = Regex(
        """(?:R\$|\$)\s*([0-9]+[.,][0-9]{1,2})\s*/\s*km""",
        RegexOption.IGNORE_CASE
    )

    // "4,96 (326)" -> nota + quantidade de avaliações.
    private val RATING_COUNT = Regex(
        """(?<![0-9.,])[0-5][.,][0-9]{2}(?![0-9])[ \t]*\([ \t]*([0-9]{1,3}(?:\.[0-9]{3})+|[0-9]+)[ \t]*\)"""
    )

    private val RIDE_TYPE = Regex(
        """\b(UberX|Uber\s*X|Comfort|Black|Flash|Moto|Priority|Planet|Pet|Juntos|Green|Electric)\b""",
        RegexOption.IGNORE_CASE
    )

    // Selos/limiares do card que contêm "NN min"/"NN km" mas NÃO são trechos da corrida:
    // "Viagem longa (mais de 30 min)" era lido como um trecho e trocava embarque e viagem.
    private val THRESHOLD_PHRASE = Regex(
        """\(?\s*(?:mais|menos)\s+de\s+[0-9]+(?:[.,][0-9]+)?\s*(?:km|min\w*|h(?:oras?)?)\b\s*\)?""",
        RegexOption.IGNORE_CASE
    )
    private val BADGE_LINE = Regex(
        """viagem\s+(?:longa|curta)|corrida\s+(?:longa|curta)|pre[çc]o\s+din[âa]mico|hor[áa]rio\s+de\s+pico""",
        RegexOption.IGNORE_CASE
    )

    // O OCR da captura de tela enxerga o PRÓPRIO painel do app (R$/km, Total: 36 min...) por cima do
    // card da Uber. Essas linhas nunca podem alimentar a leitura da oferta.
    private val OWN_OVERLAY_LABEL = Regex(
        """\$?\s*R\$\s*/\s*(?:km|h|hora|min)\b|\b(?:viagem|total|até|ate)\s*:\s*[0-9]|(?<![0-9.,])[0-5][.,][0-9](?![0-9])\s*★|\b(?:R\$/km|R\$/hora|R\$/min|\$\s*R\$/km)\b""",
        RegexOption.IGNORE_CASE
    )
    private val OWN_OVERLAY_STATUS = Regex("""\b(?:REPROVADA|APROVADA|ANALISAR|SEM DADOS|PENDENTE)\b""")
    private val PURE_MONEY_LINE = Regex("""^\s*(?:R\$|\$)?\s*[0-9]+[.,][0-9]{1,2}\s*$""")

    data class Extracted(
        val fare: Double?,
        val bonus: Double?,
        val pickupKm: Double?,
        val pickupMin: Double?,
        val tripKm: Double?,
        val tripMin: Double?,
        val rating: Double?,
        val stop: StopVerdict,
        val bonusIncluded: Boolean = false,
        val rideType: String? = null,
        val ratingCount: Int? = null,
        val platformPerKmHint: Double? = null,
        val readIssues: List<OfferIssue> = emptyList()
    )

    private class Token(val start: Int, val end: Int, val isKm: Boolean, val value: Double)

    private class Segment(val km: Double?, val min: Double?, val label: Char?)

    /** "1.234,56", "12,50", "12.50", "1.9" -> Double (nunca transforma 1.9 em 19). */
    fun parsePtNumber(raw: String): Double {
        val s = raw.trim()
        val hasDot = s.contains('.')
        val hasComma = s.contains(',')
        val normalized = when {
            hasDot && hasComma -> s.replace(".", "").replace(",", ".")
            hasComma -> s.replace(",", ".")
            hasDot -> if (Regex("""[0-9]{1,3}(\.[0-9]{3})+""").matches(s)) s.replace(".", "") else s
            else -> s
        }
        return normalized.toDoubleOrNull() ?: 0.0
    }

    /**
     * Apps de transporte costumam usar espaço "inquebrável" (U+00A0, U+202F...) em "R$ 6,07" e
     * "1.1 km". O \s do Java NÃO casa esses espaços, então a oferta inteira passava batida.
     */
    fun normalize(raw: String): String = raw
        .replace('\u00A0', ' ')
        .replace('\u202F', ' ')
        .replace('\u2007', ' ')
        .replace('\u2009', ' ')
        .replace("\u200B", "")
        .replace("\u200E", "")
        .replace("\u200F", "")

    /**
     * Remove do texto o que NÃO é a oferta: o painel do próprio app lido pelo OCR (R$/km, R$/hora,
     * "Total: 36 min", "REPROVADA"...) e o valor solto logo abaixo de um rótulo desse painel.
     */
    fun cleanScreenText(text: String): String {
        val out = ArrayList<String>()
        var dropMoneyCount = 0
        for (line in text.lines()) {
            val isOwnLabel = OWN_OVERLAY_LABEL.containsMatchIn(line)
            val isOwnStatus = OWN_OVERLAY_STATUS.containsMatchIn(line)
            when {
                isOwnLabel || isOwnStatus -> {
                    if (isOwnLabel) dropMoneyCount += 1
                }
                dropMoneyCount > 0 && PURE_MONEY_LINE.matches(line) -> {
                    dropMoneyCount -= 1
                }
                else -> {
                    dropMoneyCount = 0
                    out.add(line)
                }
            }
        }
        return out.joinToString("\n")
    }

    /** Tira selos e limiares ("Viagem longa (mais de 30 min)") antes de procurar trechos km/min. */
    private fun stripBadges(text: String): String =
        text.lines()
            .filterNot { BADGE_LINE.containsMatchIn(it) }
            .joinToString("\n") { THRESHOLD_PHRASE.replace(it, "") }

    /**
     * @param platform quando é a Uber, um trecho único sem rótulo é AMBÍGUO (o card sempre traz
     * embarque + viagem): não é atribuído a nenhum dos dois, em vez de virar "viagem" por palpite.
     */
    fun extract(
        raw: String,
        platform: SupportedPlatform = SupportedPlatform.UNKNOWN,
        fromOcr: Boolean = false
    ): Extracted {
        // Só o OCR de tela inteira enxerga o painel do próprio app; acessibilidade nunca lê esse pacote.
        val normalized = normalize(raw)
        val text = if (fromOcr) cleanScreenText(normalized) else normalized
        val readIssues = ArrayList<OfferIssue>()
        val lines = text.lines()
        val validLines = lines.filter { line ->
            val trimmed = line.trim()
            !trimmed.startsWith("+") &&
            !trimmed.contains("por km", ignoreCase = true) &&
            !trimmed.contains("per km", ignoreCase = true)
        }
        val targetText = validLines.joinToString("\n")

        val rawFare = FARE_REGEX.findAll(targetText)
            .map { parsePtNumber(it.groupValues[1]) }
            .firstOrNull { it > 1.0 }
            ?: FARE_DOLLAR_REGEX.findAll(targetText)
                .map { parsePtNumber(it.groupValues[1]) }
                .firstOrNull { it > 1.0 }
            ?: FARE_REGEX.findAll(text)
                .map { parsePtNumber(it.groupValues[1]) }
                .firstOrNull { it > 1.0 }
            ?: FARE_DOLLAR_REGEX.findAll(text)
                .map { parsePtNumber(it.groupValues[1]) }
                .firstOrNull { it > 1.0 }

        val fare = rawFare?.takeIf { it in OfferConsistency.MIN_FARE..OfferConsistency.MAX_FARE }
        if (rawFare != null && fare == null) {
            readIssues.add(
                OfferIssue("READ_INVALID_FARE", "Valor da corrida lido (R$ %.2f) é inválido.".format(rawFare), blocking = true)
            )
        }

        val bonusMatch = BONUS_REGEX.find(text)
        val bonus = bonusMatch?.groupValues?.get(1)?.let { parsePtNumber(it) }
        val bonusIncluded = bonusMatch != null &&
            BONUS_INCLUDED.containsMatchIn(text.substring(bonusMatch.range.last + 1, minOf(text.length, bonusMatch.range.last + 1 + 25)))

        val perKmHint = PLATFORM_PER_KM.find(text)?.groupValues?.get(1)?.let { parsePtNumber(it) }?.takeIf { it > 0.0 }

        // Selos como "Viagem longa (mais de 30 min)" não são trechos da corrida.
        val segments = buildSegments(stripBadges(text), readIssues)

        // Trechos COMPLETOS (km + min colados, "6 min (3.0 km)") têm prioridade: um "NN min" solto
        // nunca pode tomar o lugar do embarque ou da viagem quando existem dois trechos completos.
        val complete = segments.filter { it.km != null && it.min != null }
        val pool = if (complete.size >= 2) complete else segments

        var pickup: Segment? = null
        var trip: Segment? = null
        when {
            pool.size == 1 -> {
                val only = pool[0]
                when {
                    only.label == 'P' -> pickup = only
                    only.label == 'T' -> trip = only
                    platform == SupportedPlatform.UBER || platform == SupportedPlatform.NINE_NINE -> readIssues.add(
                        OfferIssue(
                            OfferConsistency.CODE_AMBIGUOUS_LEG,
                            "Só um trecho (km/min) foi lido; não dá para saber se é embarque ou viagem.",
                            blocking = true
                        )
                    )
                    else -> trip = only
                }
            }
            pool.size >= 2 -> {
                val a = pool[0]
                val b = pool[1]
                if (a.label == 'T' && b.label == 'P') {
                    trip = a; pickup = b
                } else {
                    pickup = a; trip = b
                }
            }
        }

        val ratingMatch = RATING_REGEX.find(text)
        val ratingStr = ratingMatch?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
            ?: ratingMatch?.groupValues?.get(2)?.takeIf { it.isNotBlank() }
            ?: ratingMatch?.groupValues?.get(3)?.takeIf { it.isNotBlank() }
        val rating = (ratingStr?.let { parsePtNumber(it) }
            ?: RATING_LINE_REGEX.find(text)?.groupValues?.get(1)?.let { parsePtNumber(it) }
            ?: RATING_RIDES.find(text)?.groupValues?.get(1)?.let { parsePtNumber(it) })
            ?.takeIf { it in 3.0..5.0 }

        val stop = when {
            STOP_CONFIRMED.containsMatchIn(text) -> StopVerdict.CONFIRMED
            STOP_POSSIBLE.containsMatchIn(text) -> StopVerdict.POSSIBLE
            else -> StopVerdict.NONE
        }

        val ratingCount = (RATING_COUNT.find(text)?.groupValues?.get(1)
            ?: RATING_RIDES.find(text)?.groupValues?.get(2))
            ?.replace(".", "")?.toIntOrNull()

        val rideType = text.lines()
            .map { it.trim() }
            .filter { it.length <= 25 }
            .firstNotNullOfOrNull { RIDE_TYPE.find(it)?.value }
            ?.replace(Regex("""\s+"""), "")
            ?.let { if (it.equals("UberX", ignoreCase = true)) "UberX" else it }

        return Extracted(
            fare = fare,
            bonus = bonus,
            pickupKm = pickup?.km,
            pickupMin = pickup?.min,
            tripKm = trip?.km,
            tripMin = trip?.min,
            rating = rating,
            stop = stop,
            bonusIncluded = bonusIncluded,
            rideType = rideType,
            ratingCount = ratingCount,
            platformPerKmHint = perKmHint,
            readIssues = readIssues
        )
    }

    private fun tokenize(text: String, readIssues: MutableList<OfferIssue>): List<Token> {
        val tokens = ArrayList<Token>()
        for (m in KM_REGEX.findAll(text)) {
            val km = parsePtNumber(m.groupValues[1])
            if (km <= 0.0 || km > OfferConsistency.MAX_KM) {
                // OCR/leitura inválida: descarta o número em vez de calcular com ele.
                readIssues.add(OfferIssue("READ_INVALID_KM", "Distância lida (%.1f km) é inválida.".format(km), blocking = true))
                continue
            }
            tokens.add(Token(m.range.first, m.range.last + 1, true, km))
        }
        for (m in DURATION_REGEX.findAll(text)) {
            val minutes = if (m.groupValues[3].isNotEmpty()) {
                m.groupValues[3].toDouble()
            } else {
                val h = m.groupValues[1].toDoubleOrNull() ?: 0.0
                val mi = m.groupValues[2].takeIf { it.isNotEmpty() }?.toDoubleOrNull() ?: 0.0
                h * 60.0 + mi
            }
            if (minutes > OfferConsistency.MAX_MIN) {
                readIssues.add(OfferIssue("READ_INVALID_MIN", "Tempo lido (%.0f min) é inválido.".format(minutes), blocking = true))
            } else if (minutes > 0) {
                tokens.add(Token(m.range.first, m.range.last + 1, false, minutes))
            }
        }
        for (m in METERS_REGEX.findAll(text)) {
            val km = m.groupValues[1].toDouble() / 1000.0
            if (km > 0.0) tokens.add(Token(m.range.first, m.range.last + 1, true, km))
        }
        tokens.sortBy { it.start }
        return tokens
    }

    /** Une cada distância ao tempo vizinho: "6 min (2,3 km)" ou "1,8 km • 6 min". */
    private fun buildSegments(text: String, readIssues: MutableList<OfferIssue>): List<Segment> {
        val tokens = tokenize(text, readIssues)
        val segments = ArrayList<Segment>()
        var i = 0
        while (i < tokens.size) {
            val a = tokens[i]
            val b = tokens.getOrNull(i + 1)
            val paired = b != null && b.isKm != a.isKm && (b.start - a.end) <= PAIR_MAX_GAP
            val start = a.start
            val end: Int
            val km: Double?
            val min: Double?
            if (paired && b != null) {
                km = if (a.isKm) a.value else b.value
                min = if (a.isKm) b.value else a.value
                end = b.end
                i += 2
            } else {
                km = if (a.isKm) a.value else null
                min = if (a.isKm) null else a.value
                end = a.end
                i += 1
            }
            val pre = text.substring(maxOf(0, start - 25), start).substringAfterLast('\n')
            val post = text.substring(end, minOf(text.length, end + 25)).substringBefore('\n')
            val label: Char? = when {
                PICKUP_LABEL.containsMatchIn(post) && !TRIP_LABEL.containsMatchIn(pre) -> 'P'
                TRIP_LABEL.containsMatchIn(pre) || TRIP_LABEL.containsMatchIn(post) -> 'T'
                else -> null
            }
            segments.add(Segment(km, min, label))
        }
        return segments
    }

    /** "2 min (0.3 km)" / "43 minutos (29.3 km)", opcionalmente seguido do endereço na mesma linha. */
    private val TIME_KM_LINE = Regex(
        """^\s*\d+\s*(?:min(?:utos?)?|h)\.?\s*\(\s*[0-9]+(?:[.,][0-9]+)?\s*(?:km|m)\s*\)\s*(.*)$""",
        RegexOption.IGNORE_CASE
    )
    // Lista "Solicitações" da 99: "(9 min 2,9 km) Supermercado Lar Feliz, Avenida Progresso".
    private val TIME_KM_PAREN = Regex(
        """^\s*\(\s*\d+\s*(?:min(?:utos?)?|h)\.?\s+[0-9]+(?:[.,][0-9]+)?\s*(?:km|m)\s*\)\s*(.*)$""",
        RegexOption.IGNORE_CASE
    )
    private fun timeLine(line: String) = TIME_KM_LINE.matchEntire(line) ?: TIME_KM_PAREN.matchEntire(line)

    private val NOT_ADDRESS = Regex(
        """^(?:aceitar|aceitar corrida|recusar|ignorar|selecionar|viagem longa.*|exclusivo|priority|uberx|comfort|"""+
            """black|flash|moto|[+]?\s*(?:R\$|\$).*|\d+[.,]\d+\s*\(\d+\)|[0-9.,\s]+)$""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Endereços da oferta: o texto vem em pares "tempo (km)" + endereço. O 1º par é o EMBARQUE; o
     * último, o DESTINO final (com paradas no meio, o destino é o último). Sem o par, devolve null —
     * nunca inventa endereço.
     */
    fun extractAddresses(raw: String): Pair<String?, String?> {
        val lines = raw.lines().map { it.trim() }
        val found = ArrayList<String>()
        var i = 0
        while (i < lines.size) {
            val m = timeLine(lines[i])
            if (m != null) {
                var addr = m.groupValues[1].trim().trimStart('·', '-', '•').trim()
                if (addr.isEmpty()) {
                    var j = i + 1
                    while (j < lines.size && lines[j].isEmpty()) j++
                    val next = lines.getOrNull(j).orEmpty()
                    if (next.isNotEmpty() && timeLine(next) == null) {
                        addr = next
                        i = j
                    }
                }
                val validAddr = addr.takeIf { it.any(Char::isLetter) && !NOT_ADDRESS.matches(it) }
                if (validAddr != null) {
                    found.add(validAddr)
                }
            }
            i++
        }
        if (found.isEmpty()) return null to null
        val pickup = found.first()
        val dest = if (found.size >= 2) found.last() else null
        return pickup to dest
    }

    /** Monta o RideOffer a partir do texto. Retorna null se nada útil foi encontrado. */
    fun build(
        text: String,
        source: RideSource,
        platform: SupportedPlatform,
        baseConfidence: Double
    ): RideOffer? {
        if (text.isBlank()) return null
        val x = extract(text, platform, fromOcr = source == RideSource.OCR_IMAGE)
        if (x.fare == null && x.tripKm == null && x.tripMin == null && x.rating == null &&
            x.readIssues.isEmpty()
        ) return null

        val (pickupAddress, destinationAddress) = extractAddresses(text)
        val offer = RideOffer(
            platform = platform,
            fare = x.fare,
            bonusConfirmed = x.bonus,
            distanceToPickupKm = x.pickupKm,
            timeToPickupMin = x.pickupMin,
            tripDistanceKm = x.tripKm,
            durationMin = x.tripMin,
            passengerRating = x.rating,
            stopVerdict = x.stop,
            source = source,
            rawText = text,
            confidenceScore = if (x.fare != null && x.tripKm != null) baseConfidence else baseConfidence - 0.25,
            fareProvenance = if (x.fare != null) DataProvenance.CAPTURED else DataProvenance.UNAVAILABLE,
            distanceProvenance = if (x.tripKm != null) DataProvenance.CAPTURED else DataProvenance.UNAVAILABLE,
            durationProvenance = if (x.tripMin != null) DataProvenance.CAPTURED else DataProvenance.UNAVAILABLE,
            rideType = x.rideType,
            ratingCount = x.ratingCount,
            bonusIncludedInFare = x.bonusIncluded,
            platformPerKmHint = x.platformPerKmHint,
            pickupText = pickupAddress,
            destinationText = destinationAddress
        )
        // Confere os campos entre si; dado ausente/conflitante vira "pendente de validação".
        return OfferConsistency.validate(offer, x.readIssues)
    }
}
