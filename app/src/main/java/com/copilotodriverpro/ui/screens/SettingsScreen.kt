
package com.copilotodriverpro.ui.screens

import android.app.Activity
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.copilotodriverpro.CopilotApp
import com.copilotodriverpro.navigation.Routes
import com.copilotodriverpro.overlay.FloatingPanelService
import com.copilotodriverpro.settings.FontSize
import com.copilotodriverpro.settings.PanelPosition
import com.copilotodriverpro.ui.components.CopilotoCard
import com.copilotodriverpro.ui.components.SliderRow
import com.copilotodriverpro.ui.components.SwitchRow
import com.copilotodriverpro.ui.theme.AccentYellow
import com.copilotodriverpro.ui.theme.SignalGreen
import com.copilotodriverpro.ui.theme.SignalRed
import com.copilotodriverpro.ui.theme.SignalYellow
import com.copilotodriverpro.ui.theme.TextMuted
import com.copilotodriverpro.utils.Permissions
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * Configurações — Piloto Ninja GO.
 */
@Composable
fun SettingsScreen(nav: NavController) {
    val context = LocalContext.current
    val app = context.applicationContext as CopilotApp
    val settings by app.settingsRepository.settings.collectAsState(initial = null)
    val scope = rememberCoroutineScope()

    var showRestoreDialog by remember { mutableStateOf(false) }

    var kmMin by remember { mutableStateOf("1,50") }
    var kmMax by remember { mutableStateOf("2,00") }
    var hMin by remember { mutableStateOf("35,00") }
    var hMax by remember { mutableStateOf("40,00") }
    var error by remember { mutableStateOf<String?>(null) }
    var initialized by remember { mutableStateOf(false) }

    LaunchedEffect(settings) {
        val s = settings ?: return@LaunchedEffect

        if (!initialized) {
            kmMin = br(s.perKmMin)
            kmMax = br(s.perKmMax)
            hMin = br(s.perHourMin)
            hMax = br(s.perHourMax)
            initialized = true
        }
    }

    // Botão flutuante e GPS da região de atuação dividem o mesmo serviço em primeiro plano:
    // ele só para quando nenhum dos dois está ligado.
    LaunchedEffect(settings?.floatingEnabled) {
        if (settings != null) app.syncHostService()
    }

    val svcEnabled = isAccessibilityServiceEnabled(context)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Configurações",
            style = MaterialTheme.typography.titleLarge
        )

        // Permissões
        CopilotoCard("Serviço de Acessibilidade") {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    Modifier
                        .size(12.dp)
                        .clip(RoundedCornerShape(50))
                        .background(
                            if (svcEnabled) SignalGreen else SignalRed
                        )
                )

                Text(
                    text = if (svcEnabled) {
                        "Ativo — monitoramento disponível"
                    } else {
                        "Desabilitado — a leitura de ofertas está parada"
                    },
                    color = if (svcEnabled) SignalGreen else SignalRed,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Text(
                text = "O serviço lê textos das ofertas na tela dos apps de transporte, " +
                        "no próprio aparelho. Nada é enviado para servidores e ele não toca, " +
                        "clica, aceita ou recusa corridas por você.",
                style = MaterialTheme.typography.bodyMedium
            )

            Button(
                onClick = {
                    val activity = context.findActivity()
                    if (activity != null) {
                        Permissions.openAccessibilitySettings(activity)
                    } else {
                        Toast.makeText(
                            context,
                            "Não foi possível abrir as configurações.",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AccentYellow
                )
            ) {
                Text("Gerenciar permissão")
            }
        }

        // Cálculo de ganhos
        CopilotoCard("Cálculo de Ganhos") {
            Text(
                text = "Defina as faixas de cores: vermelho abaixo do mínimo, " +
                        "amarelo entre mínimo e máximo e verde acima do máximo.",
                style = MaterialTheme.typography.bodyMedium
            )

            Text(
                text = "R$ por km",
                fontWeight = FontWeight.SemiBold,
                color = TextMuted
            )

            BandRangeSlider(
                range = 0.50f..10.00f,
                minValue = (kmMin.toFloatOrNullBR() ?: 1.5f)
                    .coerceIn(0.50f, 10.00f),
                maxValue = (kmMax.toFloatOrNullBR() ?: 2.0f)
                    .coerceIn(0.50f, 10.00f),
                onMin = { kmMin = br(it) },
                onMax = { kmMax = br(it) }
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CurrencyField(
                    label = "mínimo/km",
                    value = kmMin,
                    onChange = { kmMin = it },
                    modifier = Modifier.weight(1f)
                )

                CurrencyField(
                    label = "máximo/km",
                    value = kmMax,
                    onChange = { kmMax = it },
                    modifier = Modifier.weight(1f)
                )
            }

            Text(
                text = "R$ por hora",
                fontWeight = FontWeight.SemiBold,
                color = TextMuted
            )

            BandRangeSlider(
                range = 5.00f..120.00f,
                minValue = (hMin.toFloatOrNullBR() ?: 35f)
                    .coerceIn(5f, 120f),
                maxValue = (hMax.toFloatOrNullBR() ?: 40f)
                    .coerceIn(5f, 120f),
                onMin = { hMin = br(it) },
                onMax = { hMax = br(it) }
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CurrencyField(
                    label = "mínimo/h",
                    value = hMin,
                    onChange = { hMin = it },
                    modifier = Modifier.weight(1f)
                )

                CurrencyField(
                    label = "máximo/h",
                    value = hMax,
                    onChange = { hMax = it },
                    modifier = Modifier.weight(1f)
                )
            }

            error?.let {
                Text(
                    text = it,
                    color = SignalRed,
                    style = MaterialTheme.typography.labelSmall
                )
            }

            Button(
                onClick = {
                    val nKmMin = kmMin.toFloatOrNullBR()
                    val nKmMax = kmMax.toFloatOrNullBR()
                    val nHMin = hMin.toFloatOrNullBR()
                    val nHMax = hMax.toFloatOrNullBR()

                    val msg = when {
                        nKmMin == null || nKmMax == null ||
                                nHMin == null || nHMax == null ->
                            "Preencha todos os valores com números válidos."

                        nKmMin < 0.50f || nKmMax > 10.00f ||
                                nKmMax < nKmMin ->
                            "Confira os valores de R$/km (0,50 a 10,00)."

                        nHMin < 5f || nHMax > 120f ||
                                nHMax < nHMin ->
                            "Confira os valores de R$/hora (5,00 a 120,00)."

                        else -> null
                    }

                    error = msg

                    if (msg == null) {
                        scope.launch {
                            val current = app.settingsRepository.load()

                            app.settingsRepository.save(
                                current.copy(
                                    perKmMin = nKmMin!!,
                                    perKmMax = nKmMax!!,
                                    perHourMin = nHMin!!,
                                    perHourMax = nHMax!!
                                )
                            )

                            Toast.makeText(
                                context,
                                "Configurações salvas — semáforo atualizado.",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AccentYellow
                )
            ) {
                Text("Salvar")
            }

            OutlinedButton(
                onClick = { showRestoreDialog = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Restaurar padrões")
            }
        }

        // Copiloto inteligente
        CopilotoCard("Copiloto inteligente") {
            SwitchRow(
                "Copiloto inteligente",
                settings?.copilotEnabled ?: false,
                { v -> app.updateSetting { it.copy(copilotEnabled = v) } }
            )

            SwitchRow(
                "Ler apenas Uber/99",
                settings?.readOnlyUber99 ?: true,
                { v -> app.updateSetting { it.copy(readOnlyUber99 = v) } },
                "Só analisa telas identificadas como Uber ou 99. " +
                        "Desligado, permite testar com imagens escolhidas por você."
            )

            SwitchRow(
                "Card automático no topo",
                settings?.rideCardEnabled ?: true,
                { v ->
                    if (v && !Permissions.hasOverlay(context)) {
                        context.findActivity()?.let {
                            Permissions.requestOverlay(it)
                        }

                        Toast.makeText(
                            context,
                            "Conceda a sobreposição para o card aparecer sobre a Uber.",
                            Toast.LENGTH_LONG
                        ).show()
                    }

                    app.updateSetting {
                        it.copy(rideCardEnabled = v)
                    }
                },
                "Card no topo da tela quando uma corrida é identificada."
            )

            SliderRow(
                "Transparência do card",
                settings?.cardOpacity ?: 1.0f,
                { v -> app.updateSetting { it.copy(cardOpacity = v) } },
                0.4f..1.0f
            ) {
                "%.0f%%".format(it * 100)
            }

            SliderRow(
                "Tamanho do card",
                settings?.cardScale ?: 1.0f,
                { v -> app.updateSetting { it.copy(cardScale = v) } },
                0.6f..1.4f
            ) {
                "%.0f%%".format(it * 100)
            }
        }

        // Veículo
        CopilotoCard("Veículo e Custo Operacional Real") {
            SliderRow(
                "Consumo médio (km/L)",
                settings?.avgConsumptionKmPerUnit ?: 10.0f,
                { v ->
                    app.updateSetting {
                        it.copy(avgConsumptionKmPerUnit = v)
                    }
                },
                4.0f..25.0f
            ) {
                "%.1f km/L".format(it)
            }

            SliderRow(
                "Preço do combustível/energia (R$)",
                settings?.fuelPricePerUnit ?: 5.80f,
                { v ->
                    app.updateSetting {
                        it.copy(fuelPricePerUnit = v)
                    }
                },
                2.00f..10.00f
            ) {
                "R$ %.2f".format(it)
            }

            SliderRow(
                "Manutenção por km (R$)",
                settings?.maintenancePerKm ?: 0.15f,
                { v ->
                    app.updateSetting {
                        it.copy(maintenancePerKm = v)
                    }
                },
                0.00f..1.00f
            ) {
                "R$ %.2f/km".format(it)
            }

            SliderRow(
                "Pneus por km (R$)",
                settings?.tireCostPerKm ?: 0.05f,
                { v ->
                    app.updateSetting {
                        it.copy(tireCostPerKm = v)
                    }
                },
                0.00f..0.50f
            ) {
                "R$ %.2f/km".format(it)
            }

            SliderRow(
                "Depreciação por km (R$)",
                settings?.depreciationPerKm ?: 0.20f,
                { v ->
                    app.updateSetting {
                        it.copy(depreciationPerKm = v)
                    }
                },
                0.00f..1.00f
            ) {
                "R$ %.2f/km".format(it)
            }
        }

        // Metas
        CopilotoCard("Metas de Ganho Líquido Real") {
            SliderRow(
                "Meta líquida R$/hora",
                settings?.minNetHourly ?: 30.00f,
                { v -> app.updateSetting { it.copy(minNetHourly = v) } },
                10.00f..100.00f
            ) {
                "R$ %.2f/h".format(it)
            }

            SliderRow(
                "Meta líquida R$/km",
                settings?.minNetPerKm ?: 1.00f,
                { v -> app.updateSetting { it.copy(minNetPerKm = v) } },
                0.50f..5.00f
            ) {
                "R$ %.2f/km".format(it)
            }

            SliderRow(
                "Distância máx. até passageiro (km)",
                settings?.maxDistanceToPickupKm ?: 5.0f,
                { v ->
                    app.updateSetting {
                        it.copy(maxDistanceToPickupKm = v)
                    }
                },
                1.0f..20.0f
            ) {
                "%.1f km".format(it)
            }
        }

        // Regras de cálculo
        CopilotoCard("Regras de cálculo") {
            Text(
                "R$/km = valor ÷ (km até o passageiro + km da viagem). " +
                    "R$/hora e R$/min = valor ÷ (tempo até o passageiro + tempo da viagem). " +
                    "Mesmo cálculo do Uber e da 99.",
                color = TextMuted
            )
        }

        // Painel flutuante
        CopilotoCard("Painel flutuante") {
            SwitchRow(
                "Botão flutuante",
                settings?.floatingEnabled ?: false,
                { v ->
                    if (v && !Permissions.hasOverlay(context)) {
                        context.findActivity()?.let {
                            Permissions.requestOverlay(it)
                        }

                        Toast.makeText(
                            context,
                            "Conceda a sobreposição e ative novamente.",
                            Toast.LENGTH_LONG
                        ).show()
                    }

                    app.updateSetting {
                        it.copy(floatingEnabled = v)
                    }
                },
                "Exibe o botão circular sobre outros apps."
            )

            SegmentedRow(
                options = listOf("Topo", "Centro", "Baixo"),
                selected = (settings?.panelPosition ?: PanelPosition.BOTTOM).ordinal,
                onSelect = { i ->
                    PanelPosition.entries.getOrNull(i)?.let { position ->
                        app.updateSetting {
                            it.copy(panelPosition = position)
                        }
                    }
                },
                label = "Posição na tela"
            )

            SegmentedRow(
                options = listOf("Pequeno", "Médio", "Grande"),
                selected = (settings?.fontSize ?: FontSize.MEDIUM).ordinal,
                onSelect = { i ->
                    FontSize.entries.getOrNull(i)?.let { size ->
                        app.updateSetting {
                            it.copy(fontSize = size)
                        }
                    }
                },
                label = "Tamanho da fonte"
            )

            SwitchRow(
                "Modo compacto",
                settings?.compactMode ?: true,
                { v -> app.updateSetting { it.copy(compactMode = v) } },
                "Ligado: só R$/km, R$/hora e alertas essenciais."
            )

            SwitchRow(
                "Exibir R$/km",
                settings?.showPerKm ?: true,
                { v -> app.updateSetting { it.copy(showPerKm = v) } }
            )

            SwitchRow(
                "Exibir R$/hora",
                settings?.showPerHour ?: true,
                { v -> app.updateSetting { it.copy(showPerHour = v) } }
            )

            OutlinedButton(
                onClick = { nav.navigate(Routes.SEMAFORO) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Semáforo de Valores e prévia")
            }
        }

        // Nota do passageiro
        CopilotoCard("Nota mínima do passageiro") {
            SliderRow(
                "Nota mínima aceitável",
                settings?.minRating ?: 4.80f,
                { v -> app.updateSetting { it.copy(minRating = v) } },
                3.00f..5.00f
            ) {
                "%.1f ★".format(it)
            }

            SwitchRow(
                "Exibir nota do passageiro",
                settings?.showPassengerRating ?: true,
                { v ->
                    app.updateSetting {
                        it.copy(showPassengerRating = v)
                    }
                },
                "Quando a nota não estiver visível, exibe Nota indisponível."
            )
        }

        // Alerta de parada
        CopilotoCard("Alerta de corrida com parada") {
            SwitchRow(
                "Aviso de corrida com parada",
                settings?.stopAlertEnabled ?: true,
                { v ->
                    app.updateSetting {
                        it.copy(stopAlertEnabled = v)
                    }
                },
                "Alerta quando a oferta indica parada com confiança suficiente."
            )
        }

        // Regiões
        CopilotoCard("Regiões de atuação") {
            // --- Aviso na própria corrida (card sobre o Uber/99) ---
            SwitchRow(
                "Avisar se a corrida sai da região",
                settings?.rideRegionAlertEnabled ?: true,
                { v -> app.updateSetting { it.copy(rideRegionAlertEnabled = v) } },
                "Mostra no card, quando a corrida aparece: \"te leva para fora da região\" (vermelho) ou \"dentro\" (verde)."
            )

            SwitchRow(
                "Vibrar quando a corrida sai da região",
                settings?.rideRegionVibrate ?: true,
                { v -> app.updateSetting { it.copy(rideRegionVibrate = v) } },
                "2 vibrações médias na hora em que a corrida aparece."
            )

            SwitchRow(
                "Falar quando a corrida sai da região",
                settings?.rideRegionVoice ?: false,
                { v -> app.updateSetting { it.copy(rideRegionVoice = v) } }
            )

            // --- O que é conferido ---
            SwitchRow(
                "Verificar embarque",
                settings?.checkPickupRegion ?: true,
                { v ->
                    app.updateSetting {
                        it.copy(checkPickupRegion = v)
                    }
                },
                "Onde você vai buscar o passageiro."
            )

            SwitchRow(
                "Verificar destino",
                settings?.checkDestinationRegion ?: true,
                { v ->
                    app.updateSetting {
                        it.copy(checkDestinationRegion = v)
                    }
                },
                "Para onde a corrida te leva (é o que decide o \"te leva para fora\")."
            )

            SwitchRow(
                "Recusar se embarque fora da região",
                settings?.alertPickupOutside ?: true,
                { v ->
                    app.updateSetting {
                        it.copy(alertPickupOutside = v)
                    }
                },
                "O copiloto passa a marcar a corrida como NÃO PEGAR. Desligado: só avisa."
            )

            SwitchRow(
                "Recusar se destino fora da região",
                settings?.alertDestinationOutside ?: true,
                { v ->
                    app.updateSetting {
                        it.copy(alertDestinationOutside = v)
                    }
                },
                "O copiloto passa a marcar a corrida como NÃO PEGAR. Desligado: só avisa."
            )

            OutlinedButton(
                onClick = { nav.navigate(Routes.REGIONS) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Gerenciar minhas regiões")
            }
        }
    }

    if (showRestoreDialog) {
        AlertDialog(
            onDismissRequest = { showRestoreDialog = false },
            title = { Text("Restaurar padrões?") },
            text = {
                Text("Todos os valores e preferências voltarão ao padrão do app.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            app.restoreDefaults()

                            kmMin = "1,50"
                            kmMax = "2,00"
                            hMin = "35,00"
                            hMax = "40,00"
                            error = null
                            showRestoreDialog = false

                            Toast.makeText(
                                context,
                                "Padrões restaurados.",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                ) {
                    Text("Restaurar")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showRestoreDialog = false }
                ) {
                    Text("Cancelar")
                }
            }
        )
    }
}

/**
 * Slider de duas alças com faixas vermelha, amarela e verde.
 */
@Composable
private fun BandRangeSlider(
    range: ClosedFloatingPointRange<Float>,
    minValue: Float,
    maxValue: Float,
    onMin: (Float) -> Unit,
    onMax: (Float) -> Unit
) {
    val span = range.endInclusive - range.start

    if (span <= 0f) return

    val safeMin = minValue.coerceIn(range.start, range.endInclusive)
    val safeMax = maxValue.coerceIn(safeMin, range.endInclusive)

    val redWeight = ((safeMin - range.start) / span).coerceAtLeast(0.001f)
    val yellowWeight = ((safeMax - safeMin) / span).coerceAtLeast(0.001f)
    val greenWeight = ((range.endInclusive - safeMax) / span).coerceAtLeast(0.001f)

    Box(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(34.dp)
                .clip(RoundedCornerShape(8.dp))
        ) {
            Box(
                Modifier
                    .weight(redWeight)
                    .height(34.dp)
                    .background(SignalRed.copy(alpha = 0.45f))
            )

            Box(
                Modifier
                    .weight(yellowWeight)
                    .height(34.dp)
                    .background(SignalYellow.copy(alpha = 0.45f))
            )

            Box(
                Modifier
                    .weight(greenWeight)
                    .height(34.dp)
                    .background(SignalGreen.copy(alpha = 0.45f))
            )
        }

        RangeSlider(
            value = safeMin..safeMax,
            onValueChange = { values ->
                onMin(values.start)
                onMax(values.endInclusive)
            },
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color.Transparent,
                inactiveTrackColor = Color.Transparent
            )
        )
    }
}

/**
 * Campo monetário que aceita vírgula ou ponto decimal.
 */
@Composable
private fun CurrencyField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = { input ->
            onChange(
                input.filter {
                    it.isDigit() || it == ',' || it == '.'
                }.take(7)
            )
        },
        label = { Text("R$ $label") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Decimal
        ),
        modifier = modifier
    )
}

/**
 * Seletor segmentado.
 */
@Composable
private fun SegmentedRow(
    label: String,
    selected: Int,
    onSelect: (Int) -> Unit,
    options: List<String>
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            options.forEachIndexed { index, option ->
                OutlinedButton(
                    onClick = { onSelect(index) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = if (index == selected) {
                            "● $option"
                        } else {
                            option
                        }
                    )
                }
            }
        }
    }
}

/**
 * Converte números no formato brasileiro para Float.
 * Exemplos: "1,50" -> 1.5 e "2.00" -> 2.0.
 */
private fun String.toFloatOrNullBR(): Float? =
    trim().replace(",", ".").toFloatOrNull()

/**
 * Converte Float para texto com duas casas decimais.
 */
private fun br(value: Float): String =
    String.format(Locale("pt", "BR"), "%.2f", value)

/**
 * Procura a Activity associada ao Context.
 */
private fun Context.findActivity(): Activity? {
    var current = this

    while (current is android.content.ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }

    return current as? Activity
}

/**
 * Verifica se o serviço de acessibilidade está habilitado.
 */
private fun isAccessibilityServiceEnabled(context: Context): Boolean {
    val serviceName =
        "${context.packageName}/" +
                com.copilotodriverpro.accessibility
                    .CopilotAccessibilityService::class.java.name

    val enabledServices = android.provider.Settings.Secure.getString(
        context.contentResolver,
        android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false

    return enabledServices
        .split(':')
        .any { it.equals(serviceName, ignoreCase = true) }
}
