package com.copilotodriverpro.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.copilotodriverpro.CopilotApp
import com.copilotodriverpro.analyzer.RideAnalyzer
import com.copilotodriverpro.analyzer.TrafficLight
import com.copilotodriverpro.settings.FontSize
import com.copilotodriverpro.settings.PanelPosition
import com.copilotodriverpro.ui.components.CopilotoCard
import com.copilotodriverpro.ui.components.SwitchRow
import com.copilotodriverpro.ui.theme.AccentYellow
import com.copilotodriverpro.ui.theme.CardSecondary
import com.copilotodriverpro.ui.theme.DividerColor
import com.copilotodriverpro.ui.theme.SignalGreen
import com.copilotodriverpro.ui.theme.SignalRed
import com.copilotodriverpro.ui.theme.SignalYellow
import com.copilotodriverpro.ui.theme.TextMuted
import com.copilotodriverpro.ui.theme.TextWhite

/**
 * Semáforo de Valores — prévia ao vivo, regras de classificação e
 * controles do painel flutuante.
 */
@Composable
fun SemaforoScreen(nav: NavController) {
    val context = LocalContext.current
    val app = context.applicationContext as CopilotApp
    val settings by app.settingsRepository.settings.collectAsState(initial = null)
    var sampleWithStop by remember { mutableStateOf(false) }

    val s = settings
    val sampleKm = 2.60f
    val sampleHour = 45.00f
    val kmLight = RideAnalyzer.classify(
        sampleKm.toDouble(),
        (s?.perKmMin ?: 1.5f).toDouble(),
        (s?.perKmMax ?: 2.0f).toDouble()
    )
    val hourLight = RideAnalyzer.classify(
        sampleHour.toDouble(),
        (s?.perHourMin ?: 35f).toDouble(),
        (s?.perHourMax ?: 40f).toDouble()
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Semáforo de Valores", style = MaterialTheme.typography.titleLarge)

        /* -------- Cartão de prévia -------- */
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = CardSecondary),
            border = BorderStroke(3.dp, borderFor(hourLight, kmLight))
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Prévia — atualiza com seus limites", style = MaterialTheme.typography.labelSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SampleMetric("R$/Km", "R$ %.2f".format(sampleKm), kmLight, Modifier.weight(1f))
                    SampleMetric("R$/Hora", "R$ %.2f".format(sampleHour), hourLight, Modifier.weight(1f))
                }
                if (sampleWithStop) {
                    Text(
                        "⚠ CORRIDA COM PARADA",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(SignalRed, RoundedCornerShape(8.dp))
                            .padding(8.dp)
                    )
                }
            }
        }

        OutlinedButton(
            onClick = { sampleWithStop = !sampleWithStop },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (sampleWithStop) "Ocultar aviso de parada" else "Mostrar aviso de parada (exemplo)")
        }

        /* -------- Regras -------- */
        CopilotoCard("Regras de classificação") {
            RuleRow(SignalRed, "Vermelho", "abaixo do mínimo configurado")
            RuleRow(SignalYellow, "Amarelo", "entre o mínimo e o máximo")
            RuleRow(SignalGreen, "Verde", "acima do máximo")
            Text(
                "Limites: valor exatamente igual ao mínimo fica AMARELO; " +
                    "exatamente igual ao máximo fica VERDE. " +
                    "Sem distância ou tempo não há classificação (—) — nenhum dado ausente vira verde.",
                style = MaterialTheme.typography.labelSmall
            )
            Text(
                "Seus limites atuais: R$/km ${br(s?.perKmMin ?: 1.5f)} – ${br(s?.perKmMax ?: 2.0f)} · " +
                    "R$/hora ${br(s?.perHourMin ?: 35f)} – ${br(s?.perHourMax ?: 40f)}",
                style = MaterialTheme.typography.bodyMedium, color = TextWhite
            )
        }

        /* -------- Posição -------- */
        CopilotoCard("Posição na tela") {
            SegmentedOptions(
                options = listOf("Topo", "Centro", "Baixo"),
                selected = (settings?.panelPosition ?: PanelPosition.BOTTOM).ordinal
            ) { i -> app.updateSetting { it.copy(panelPosition = PanelPosition.entries[i]) } }
        }

        /* -------- Tamanho da fonte -------- */
        CopilotoCard("Tamanho da fonte") {
            SegmentedOptions(
                options = listOf("Pequeno", "Médio", "Grande"),
                selected = (settings?.fontSize ?: FontSize.MEDIUM).ordinal
            ) { i -> app.updateSetting { it.copy(fontSize = FontSize.entries[i]) } }
        }

        /* -------- Modo compacto e métricas -------- */
        CopilotoCard("Modo compacto") {
            SwitchRow(
                "Modo compacto",
                settings?.compactMode ?: true,
                { v -> app.updateSetting { it.copy(compactMode = v) } },
                "Ligado: mostrar somente R$/km, R$/hora e alertas essenciais."
            )
        }

        CopilotoCard("Métricas exibidas") {
            SwitchRow("R$ por km", settings?.showPerKm ?: true, { v ->
                app.updateSetting { it.copy(showPerKm = v) }
            })
            SwitchRow("R$ por hora", settings?.showPerHour ?: true, { v ->
                app.updateSetting { it.copy(showPerHour = v) }
            })
            SwitchRow("Nota do passageiro", settings?.showPassengerRating ?: true, { v ->
                app.updateSetting { it.copy(showPassengerRating = v) }
            })
            SwitchRow("Aviso de corrida com parada", settings?.stopAlertEnabled ?: true, { v ->
                app.updateSetting { it.copy(stopAlertEnabled = v) }
            })
            Text(
                "Indisponível numa oferta: o valor some ou exibe \"—\". Nada é inventado.",
                style = MaterialTheme.typography.labelSmall
            )
        }

        /* -------- Ler apenas Uber/99 -------- */
        CopilotoCard("Ler apenas Uber/99") {
            SwitchRow(
                "Ler apenas Uber/99",
                settings?.readOnlyUber99 ?: true,
                { v -> app.updateSetting { it.copy(readOnlyUber99 = v) } },
                "Ligado: só processa os apps de transporte configurados — menos eventos, " +
                    "menor consumo de bateria e nenhuma leitura fora do contexto de corrida. " +
                    "Desligado: libera o modo de teste com imagens escolhidas por você."
            )
        }
    }
}

/** Borda da prévia: pior cor entre as duas métricas. */
private fun borderFor(hour: TrafficLight, km: TrafficLight): Color {
    val rank = mapOf(TrafficLight.RED to 3, TrafficLight.YELLOW to 2, TrafficLight.GREEN to 1, TrafficLight.NONE to 0)
    val worst = if ((rank[km] ?: 0) >= (rank[hour] ?: 0)) km else hour
    return when (worst) {
        TrafficLight.RED -> SignalRed
        TrafficLight.YELLOW -> SignalYellow
        TrafficLight.GREEN -> SignalGreen
        TrafficLight.NONE -> DividerColor
    }
}

@Composable
private fun SampleMetric(label: String, value: String, light: TrafficLight, modifier: Modifier = Modifier) {
    val color = when (light) {
        TrafficLight.RED -> SignalRed
        TrafficLight.YELLOW -> SignalYellow
        TrafficLight.GREEN -> SignalGreen
        TrafficLight.NONE -> TextMuted
    }
    Column(
        modifier = modifier
            .background(color.copy(alpha = 0.15f), RoundedCornerShape(10.dp))
            .padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(label, fontSize = 11.sp, color = TextMuted)
        Text(value, color = color, fontWeight = FontWeight.Bold, fontSize = 24.sp)
        Text(
            when (light) {
                TrafficLight.RED -> "abaixo do mínimo"
                TrafficLight.YELLOW -> "entre mín. e máx."
                TrafficLight.GREEN -> "acima do máximo"
                TrafficLight.NONE -> "sem dados"
            },
            fontSize = 10.sp, color = color
        )
    }
}

@Composable
private fun RuleRow(color: Color, title: String, rule: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        androidx.compose.foundation.layout.Box(
            Modifier.size(12.dp).background(color, RoundedCornerShape(3.dp))
        )
        Text("$title — $rule", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SegmentedOptions(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEachIndexed { i, option ->
            OutlinedButton(onClick = { onSelect(i) }) {
                Text(if (i == selected) "● $option" else option)
            }
        }
    }
}

private fun br(v: Float): String = "R$ %.2f".format(v)
