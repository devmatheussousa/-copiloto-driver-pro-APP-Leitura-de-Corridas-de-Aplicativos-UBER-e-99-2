package com.copilotodriverpro.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.copilotodriverpro.CopilotApp
import com.copilotodriverpro.analyzer.OfferEvaluation
import com.copilotodriverpro.analyzer.RideAnalyzer
import com.copilotodriverpro.analyzer.RecommendationState
import com.copilotodriverpro.analyzer.TrafficLight
import com.copilotodriverpro.models.LatLang
import com.copilotodriverpro.models.RideOffer
import com.copilotodriverpro.models.RideSource
import com.copilotodriverpro.models.StopVerdict
import com.copilotodriverpro.recognition.GeoCoderHelper
import com.copilotodriverpro.recognition.ImageOcrProcessor
import com.copilotodriverpro.ui.components.CopilotoCard
import com.copilotodriverpro.ui.components.SwitchRow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Entrada manual com cálculo AO VIVO.
 *
 * Sem botão de "analisar": cada tecla recalcula o semáforo e a recomendação.
 * O botão "Enviar ao painel flutuante" apenas copia o resultado para o overlay.
 */
@Composable
fun ManualEntryScreen(nav: NavController) {
    val context = LocalContext.current
    val app = context.applicationContext as CopilotApp
    val scope = rememberCoroutineScope()

    val settings by app.settingsRepository.settings.collectAsState(initial = null)

    var fare by remember { mutableStateOf("") }
    var toPickup by remember { mutableStateOf("") }
    var tripKm by remember { mutableStateOf("") }
    var duration by remember { mutableStateOf("") }
    var rating by remember { mutableStateOf("") }
    var pickup by remember { mutableStateOf("") }
    var destination by remember { mutableStateOf("") }
    var hasStop by remember { mutableStateOf(false) }
    var ocrResult by remember { mutableStateOf<String?>(null) }

    // Recalcula a cada mudança de qualquer campo — é o coração da tela.
    val evaluation: OfferEvaluation? = remember(fare, toPickup, tripKm, duration, rating, hasStop, settings) {
        val s = settings ?: return@remember null
        if (fare.isBlank() && tripKm.isBlank() && duration.isBlank() && toPickup.isBlank()) {
            return@remember null
        }
        RideAnalyzer.evaluateOffer(
            offer = RideOffer(
                fare = fare.toDoublePtOrNull(),
                distanceToPickupKm = toPickup.toDoublePtOrNull(),
                tripDistanceKm = tripKm.toDoublePtOrNull(),
                durationMin = duration.toDoublePtOrNull(),
                passengerRating = rating.toDoublePtOrNull(),
                stopVerdict = if (hasStop) StopVerdict.CONFIRMED else StopVerdict.NONE,
                source = RideSource.MANUAL,
                confidenceScore = 1.0
            ),
            settings = s
        )
    }

    val photoLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                ocrResult = "Reconhecendo..."
                runCatching {
                    val offer = ImageOcrProcessor.recognizeOffer(context, uri)
                    if (offer != null) {
                        // Preenche os campos para você conferir e ajustar antes de enviar.
                        fare = offer.fare?.let { fmt(it) } ?: ""
                        toPickup = offer.distanceToPickupKm?.let { fmt(it) } ?: ""
                        tripKm = offer.tripDistanceKm?.let { fmt(it) } ?: ""
                        duration = offer.durationMin?.let { fmt(it) } ?: ""
                        rating = offer.passengerRating?.let { fmt1(it) } ?: ""
                        hasStop = offer.stopVerdict == StopVerdict.CONFIRMED
                        "Oferta lida da imagem. Confira os campos acima e ajuste o que estiver errado."
                    } else {
                        "Nenhuma oferta identificada na imagem. Digite os valores manualmente."
                    }
                }.onFailure { e ->
                    ocrResult = "Falha no OCR: ${e.message}"
                }.onSuccess { text ->
                    ocrResult = text
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Digitar valores", style = MaterialTheme.typography.titleLarge)
        Text(
            "O semáforo e a recomendação atualizam a cada tecla. Campos vazios viram \"—\".",
            style = MaterialTheme.typography.bodyMedium
        )

        // Resultado ao vivo — sempre visível no topo
        LiveResultCard(evaluation)

        CopilotoCard("Dados da corrida") {
            NumberField(fare, { fare = it }, "Valor da corrida (R$)")
            NumberField(toPickup, { toPickup = it }, "Distância até o passageiro (km)")
            NumberField(tripKm, { tripKm = it }, "Distância da viagem (km)")
            NumberField(duration, { duration = it }, "Tempo estimado (min)")
            NumberField(rating, { rating = it }, "Nota do passageiro (ex: 4,9)")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Corrida com parada", modifier = Modifier.weight(1f))
                Switch(checked = hasStop, onCheckedChange = { hasStop = it })
            }
        }

        CopilotoCard("Embarque e destino") {
            OutlinedTextField(pickup, { pickup = it }, label = { Text("Embarque (endereço)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(destination, { destination = it }, label = { Text("Destino (endereço)") }, modifier = Modifier.fillMaxWidth())
            Text(
                "Opcional. Usado só para comparar com suas regiões de atuação.",
                style = MaterialTheme.typography.labelSmall
            )
        }

        Button(
            onClick = {
                scope.launch {
                    val offer = RideOffer(
                        fare = fare.toDoublePtOrNull(),
                        distanceToPickupKm = toPickup.toDoublePtOrNull(),
                        tripDistanceKm = tripKm.toDoublePtOrNull(),
                        durationMin = duration.toDoublePtOrNull(),
                        passengerRating = rating.toDoublePtOrNull(),
                        stopVerdict = if (hasStop) StopVerdict.CONFIRMED else StopVerdict.NONE,
                        pickupText = pickup.ifBlank { null },
                        destinationText = destination.ifBlank { null },
                        source = RideSource.MANUAL
                    )
                    publishOffer(app, offer)
                    Toast.makeText(context, "Enviado ao painel flutuante.", Toast.LENGTH_SHORT).show()
                }
            },
            enabled = evaluation != null,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFECA07))
        ) {
            Text("Enviar ao painel flutuante")
        }

        CopilotoCard("Preencher a partir de uma imagem") {
            Text(
                "Escolhe uma captura, o OCR lê e preenche os campos acima. Você ajusta e envia.",
                style = MaterialTheme.typography.bodyMedium
            )
            OutlinedButton(
                onClick = {
                    photoLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Escolher imagem")
            }
            ocrResult?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** Card do resultado ao vivo: semáforo + recomendação em destaque. */
@Composable
private fun LiveResultCard(evaluation: OfferEvaluation?) {
    CopilotoCard("Resultado ao vivo") {
        if (evaluation == null) {
            Text(
                "Digite o valor e a distância para ver o semáforo.",
                style = MaterialTheme.typography.bodyMedium
            )
            return@CopilotoCard
        }

        val rec = evaluation.recommendationResult
        if (rec != null) {
            val (bg, label) = when (rec.state) {
                RecommendationState.ACEITAR -> Color(0xFF13B889) to "✓ ACEITAR"
                RecommendationState.RECUSAR -> Color(0xFFF0444F) to "✗ RECUSAR"
                RecommendationState.AVALIAR -> Color(0xFFF5A623) to "⚠ AVALIAR"
                RecommendationState.DADOS_INSUFFICIENTES -> Color(0xFF37474F) to "⚠ DADOS INSUFICIENTES"
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(bg, RoundedCornerShape(10.dp))
                    .padding(10.dp)
            ) {
                Text(label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Text(
                    rec.justification,
                    color = Color.White.copy(alpha = 0.95f),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LiveMetric(
                "R$/km",
                evaluation.perKm.value?.let { "R$ %.2f".format(it) } ?: "—",
                evaluation.perKm.light,
                Modifier.weight(1f)
            )
            LiveMetric(
                "R$/hora",
                evaluation.perHour.value?.let { "R$ %.2f".format(it) } ?: "—",
                evaluation.perHour.light,
                Modifier.weight(1f)
            )
        }

        evaluation.financialMetrics?.let { m ->
            Text(
                "Líquido: ${m.netEarnings?.let { "R$ %.2f".format(it) } ?: "—"}  ·  " +
                    "Custo: ${m.operationalCost?.let { "R$ %.2f".format(it) } ?: "—"}",
                style = MaterialTheme.typography.bodyMedium
            )
        }

        if (evaluation.stopVerdict != StopVerdict.NONE) {
            Text(
                text = if (evaluation.stopVerdict == StopVerdict.CONFIRMED)
                    "⚠ CORRIDA COM PARADA — ANALISE COM ATENÇÃO"
                else "⚠ Possível parada — confirme na oferta",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFF0444F), RoundedCornerShape(8.dp))
                    .padding(8.dp)
            )
        }
    }
}

@Composable
private fun LiveMetric(label: String, value: String, light: TrafficLight, modifier: Modifier = Modifier) {
    val color = when (light) {
        TrafficLight.RED -> Color(0xFFF0444F)
        TrafficLight.YELLOW -> Color(0xFFF5A623)
        TrafficLight.GREEN -> Color(0xFF13B889)
        TrafficLight.NONE -> Color(0xFFA8A8A8)
    }
    Column(
        modifier = modifier
            .background(color.copy(alpha = 0.18f), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(label, color = Color(0xFFA8A8A8), fontSize = 12.sp)
        Text(value, color = color, fontWeight = FontWeight.Bold, fontSize = 26.sp)
    }
}

/** Campo numérico com teclado decimal e aceite de vírgula ou ponto. */
@Composable
private fun NumberField(value: String, onChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value = value,
        onValueChange = { input ->
            // Aceita dígito, vírgula e ponto; normaliza para o formato que o parser entende.
            onChange(input.filter { it.isDigit() || it == ',' || it == '.' }.take(9))
        },
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
}

private fun String.toDoublePtOrNull(): Double? =
    replace(".", "").replace(",", ".").toDoubleOrNull()

private fun fmt(v: Double) = String.format(java.util.Locale.US, "%.2f", v)
private fun fmt1(v: Double) = String.format(java.util.Locale.US, "%.1f", v)

/**
 * Publica a oferta no estado compartilhado, geocodificando os endereços
 * e comparando com as regiões ativas.
 */
private suspend fun publishOffer(app: CopilotApp, offer: RideOffer) {
    val settings = app.settingsRepository.settings.first()
    val regions = app.regionRepository.regions.first()
    val pickupCoord: LatLang? = offer.pickupText?.let { GeoCoderHelper.geocode(app, it, regions) }
    val destCoord: LatLang? = offer.destinationText?.let { GeoCoderHelper.geocode(app, it, regions) }
    app.rideState.publish(offer, settings, regions, pickupCoord, destCoord)
}
