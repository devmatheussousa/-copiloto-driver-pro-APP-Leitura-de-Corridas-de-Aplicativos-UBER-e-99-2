package com.copilotodriverpro.regions

import com.copilotodriverpro.models.LatLang
import org.json.JSONArray

/**
 * Parte "pura" (sem Android) da conversão endereço → coordenada: separa o endereço da oferta em
 * rua / número / bairro / cidade, monta variações de busca e acha o bairro nos dados já baixados.
 * Testável em JVM.
 */
object AddressParser {

    data class Parts(
        val street: String,
        val extraStreet: String?,   // 2ª rua quando o endereço é um cruzamento ("Rua A & Rua B")
        val number: String?,
        val neighborhood: String?,
        val city: String?
    )

    private val JUNK = setOf("go", "goias", "brasil", "brazil", "br")
    private val CEP = Regex("""^\d{5}-?\d{3}$""")
    private val NUMBER = Regex("""^(\d{1,5}[A-Za-z]?)$""")
    private val SEM_NUMERO = Regex("""^(s/?n|sem n[uú]mero)$""", RegexOption.IGNORE_CASE)
    private val UF_SUFFIX = Regex("""\s*[-–]\s*[A-Za-z]{2}$""")

    private fun cleanStreet(s: String) = s.replace(Regex("""\s*[-–]\s*"""), "-").replace(Regex("""\s+"""), " ").trim()

    fun parse(raw: String): Parts? {
        val pieces = raw.replace('\n', ',').split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (pieces.isEmpty()) return null
        val cleaned = ArrayList<String>()
        pieces.forEachIndexed { i, piece ->
            val p = if (i > 0) piece.replace(UF_SUFFIX, "").trim() else piece
            val key = NeighborhoodGeo.normName(p)
            if (p.isEmpty() || key in JUNK || CEP.matches(p)) return@forEachIndexed
            if (cleaned.none { NeighborhoodGeo.normName(it) == key }) cleaned += p
        }
        if (cleaned.isEmpty()) return null

        var street = cleaned[0]
        var extra: String? = null
        if (street.contains('&')) {
            val sp = street.split('&').map { cleanStreet(it) }.filter { it.isNotEmpty() }
            street = sp.firstOrNull() ?: street
            extra = sp.getOrNull(1)
        } else street = cleanStreet(street)

        val rest = cleaned.drop(1).toMutableList()
        var number: String? = null
        val ni = rest.indexOfFirst { NUMBER.matches(it) || SEM_NUMERO.matches(it) }
        if (ni >= 0) {
            if (NUMBER.matches(rest[ni])) number = rest[ni]
            rest.removeAt(ni)
        }
        val city = rest.lastOrNull()
        val neighborhood = if (rest.size >= 2) rest.first() else null
        return Parts(street, extra, number, neighborhood, city)
    }

    /** Buscas do mais específico para o mais genérico (sempre com a cidade e "Brasil"). */
    fun variants(raw: String): List<String> {
        val p = parse(raw) ?: return listOf(raw.trim()).filter { it.isNotEmpty() }
        fun q(vararg xs: String?) =
            xs.filterNotNull().filter { it.isNotBlank() }.joinToString(", ") + ", Brasil"
        val out = LinkedHashSet<String>()
        out += q(p.street, p.number, p.neighborhood, p.city)
        out += q(p.street, p.number, p.city)
        out += q(p.street, p.neighborhood, p.city)
        out += q(p.street, p.city)
        p.extraStreet?.let {
            out += q(it, p.neighborhood, p.city)
            out += q(it, p.city)
        }
        p.neighborhood?.let { out += q(it, p.city) }
        return out.toList()
    }

    private fun stripPrefix(name: String) =
        NeighborhoodGeo.normName(name.replace(Regex("""^\s*(setor|bairro)\s+""", RegexOption.IGNORE_CASE), ""))

    /**
     * Plano B sem internet/Geocoder: o endereço traz o bairro ("..., Cidade Jardim, Goiânia") e os bairros
     * da cidade já estão no aparelho. Devolve o ponto central do bairro. Só usa o bairro EXPLÍCITO e,
     * se a cidade do endereço não estiver baixada, não chuta outra cidade.
     */
    fun fromNeighborhood(raw: String, cities: List<CityData>): LatLang? {
        val p = parse(raw) ?: return null
        val wanted = p.neighborhood?.let { stripPrefix(it) }?.takeIf { it.isNotEmpty() } ?: return null
        val cityKey = p.city?.let { NeighborhoodGeo.normName(it) }
        val pool = if (cityKey != null) cities.filter { NeighborhoodGeo.normName(it.city.name) == cityKey } else cities
        return pool.asSequence().flatMap { it.items.asSequence() }
            .firstOrNull { stripPrefix(it.name) == wanted }?.center
    }

    /** Resposta do Nominatim (`format=jsonv2`) → primeira coordenada. */
    fun parseNominatim(body: String): LatLang? = runCatching {
        val arr = JSONArray(body)
        if (arr.length() == 0) return null
        val o = arr.getJSONObject(0)
        LatLang(o.getString("lat").toDouble(), o.getString("lon").toDouble())
    }.getOrNull()

    fun inside(p: LatLang, b: GeoUtils.Bounds?): Boolean =
        b == null || (p.lat >= b.south && p.lat <= b.north && p.lng >= b.west && p.lng <= b.east)
}
