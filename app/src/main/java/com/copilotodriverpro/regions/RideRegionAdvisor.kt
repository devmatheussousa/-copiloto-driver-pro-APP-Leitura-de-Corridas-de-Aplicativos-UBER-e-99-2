package com.copilotodriverpro.regions

import com.copilotodriverpro.models.GeoStatus
import com.copilotodriverpro.models.RegionCheck
import java.util.Locale

/** Como a corrida se relaciona com as regiões de atuação do motorista. */
enum class RideRegionLevel {
    /** Embarque e/ou destino FORA da região: o motorista precisa decidir com atenção. */
    OUT,

    /** Embarque/destino dentro da região. */
    IN,

    /** O motorista está fora agora, mas esta corrida termina DENTRO da região. */
    BACK_IN,

    /** Não foi possível localizar o endereço (nunca vira "fora" por falta de dado). */
    UNKNOWN,

    /** Ainda convertendo os endereços em coordenadas. */
    CHECKING
}

data class RideRegionVerdict(
    val level: RideRegionLevel,
    val title: String,
    val detail: String? = null
)

/**
 * Lógica pura (testável em JVM) do aviso "esta corrida me joga para fora da região?".
 *
 * Entradas: o resultado geográfico da oferta ([RegionCheck]), se há região ativa, onde o motorista
 * está AGORA (GPS) e quais verificações estão ligadas. Saída: título + detalhe prontos para o card.
 * Devolve null quando não há nada a dizer (sem região ativa ou verificações desligadas).
 */
object RideRegionAdvisor {

    fun verdict(
        check: RegionCheck?,
        hasRegions: Boolean,
        driver: ZoneStatus?,
        checkPickup: Boolean = true,
        checkDestination: Boolean = true
    ): RideRegionVerdict? {
        if (!hasRegions || check == null) return null
        if (!checkPickup && !checkDestination) return null
        if (check.resolving) {
            return RideRegionVerdict(RideRegionLevel.CHECKING, "Verificando região…")
        }

        val pOut = checkPickup && check.pickup == GeoStatus.OUTSIDE
        val dOut = checkDestination && check.destination == GeoStatus.OUTSIDE
        val pIn = checkPickup && check.pickup == GeoStatus.INSIDE
        val dIn = checkDestination && check.destination == GeoStatus.INSIDE
        val driverInside = driver == ZoneStatus.INSIDE
        val driverOutside = driver == ZoneStatus.OUTSIDE

        // ---- FORA ----
        if (pOut && dOut) {
            return RideRegionVerdict(
                RideRegionLevel.OUT,
                "⚠ EMBARQUE E DESTINO FORA DA REGIÃO",
                join(edge("Destino", check.destinationOutsideM), edge("embarque", check.pickupOutsideM))
            )
        }
        if (dOut) {
            val title = if (driverInside) "⚠ ESTA CORRIDA TE LEVA PARA FORA DA REGIÃO"
            else "⚠ DESTINO FORA DA REGIÃO"
            return RideRegionVerdict(
                RideRegionLevel.OUT,
                title,
                join(edge("Destino", check.destinationOutsideM), if (pIn) "embarque dentro" else null)
            )
        }
        if (pOut) {
            return RideRegionVerdict(
                RideRegionLevel.OUT,
                "⚠ EMBARQUE FORA DA REGIÃO",
                join(edge("Embarque", check.pickupOutsideM), if (dIn) "destino dentro" else null)
            )
        }

        // ---- DENTRO ----
        if (dIn) {
            if (driverOutside) {
                return RideRegionVerdict(
                    RideRegionLevel.BACK_IN,
                    "✓ ESTA CORRIDA TE TRAZ DE VOLTA PARA A REGIÃO",
                    check.destinationMatchedRegion?.let { "Destino em $it" }
                )
            }
            val title = if (pIn) "✓ EMBARQUE E DESTINO DENTRO DA REGIÃO" else "✓ DESTINO DENTRO DA REGIÃO"
            val extra = if (checkPickup && !pIn) "embarque não identificado" else null
            return RideRegionVerdict(
                RideRegionLevel.IN, title,
                join(check.destinationMatchedRegion?.let { "Região: $it" }, extra)
            )
        }
        if (pIn) {
            return if (checkDestination) {
                RideRegionVerdict(
                    RideRegionLevel.UNKNOWN,
                    "Destino não identificado",
                    "Embarque dentro da região. Confira o destino na oferta."
                )
            } else {
                RideRegionVerdict(
                    RideRegionLevel.IN, "✓ EMBARQUE DENTRO DA REGIÃO",
                    check.pickupMatchedRegion?.let { "Região: $it" }
                )
            }
        }

        return RideRegionVerdict(
            RideRegionLevel.UNKNOWN,
            "Local da corrida não identificado",
            "Não consegui localizar o endereço. Confira na oferta."
        )
    }

    /** "850 m" abaixo de 1 km; "4,2 km" a partir daí. Sem medida: "—". */
    fun km(meters: Double?): String = when {
        meters == null -> "—"
        meters < 995.0 -> "${(Math.round(meters / 10.0) * 10).toInt().coerceAtLeast(10)} m"
        else -> String.format(Locale("pt", "BR"), "%.1f km", meters / 1000.0)
    }

    private fun edge(label: String, meters: Double?): String? =
        meters?.let { "$label a ${km(it)} da borda" }

    private fun join(first: String?, second: String?): String? =
        listOfNotNull(first, second).takeIf { it.isNotEmpty() }?.joinToString(" · ")
}
