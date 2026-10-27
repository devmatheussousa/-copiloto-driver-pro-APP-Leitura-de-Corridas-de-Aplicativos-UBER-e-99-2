package com.copilotodriverpro.ui.screens

import android.Manifest
import android.media.projection.MediaProjectionManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.copilotodriverpro.CopilotApp
import com.copilotodriverpro.analyzer.RecommendationState
import com.copilotodriverpro.floating.ScreenCaptureService
import com.copilotodriverpro.navigation.Routes
import com.copilotodriverpro.shifts.ShiftViewModel
import com.copilotodriverpro.ui.components.CopilotoCard
import com.copilotodriverpro.ui.components.SwitchRow
import com.copilotodriverpro.ui.theme.AccentYellow
import com.copilotodriverpro.ui.theme.TextWhite
import com.copilotodriverpro.ui.theme.OnAccent
import com.copilotodriverpro.ui.theme.CardSecondary
import com.copilotodriverpro.ui.theme.SignalGreen
import com.copilotodriverpro.ui.theme.SignalRed
import com.copilotodriverpro.ui.theme.SignalYellow
import com.copilotodriverpro.ui.theme.TextMuted
import com.copilotodriverpro.utils.Permissions
import com.copilotodriverpro.zone.rememberLocationRequester
import com.copilotodriverpro.zone.zoneLabel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * Tela Início — COPILOTO + TURNO, conforme a identidade Piloto Ninja GO.
 * Estado real do serviço, permissões verificadas e turnos via Room.
 */
@Composable
fun HomeScreen(nav: NavController) {
    val context = LocalContext.current
    val app = context.applicationContext as CopilotApp
    val settings by app.settingsRepository.settings.collectAsState(initial = null)
    val ui by app.rideState.ui.collectAsState()
    val scope = rememberCoroutineScope()

    val shiftVm: ShiftViewModel = viewModel(factory = ShiftViewModel.factory())
    val activeShift by shiftVm.activeShift.collectAsState()

    val svcRunning = isAccessibilityEnabled(context)
    val overlayOk = Permissions.hasOverlay(context)
    val cameraRecording by CameraState.recording.collectAsState()

    // Consentimento de captura de tela (Print de corrida) — fluxo oficial do Android
    val captureLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK && result.data != null) {
            ScreenCaptureService.start(context, result.resultCode, result.data!!)
        } else {
            app.updateSetting { it.copy(printEnabled = false) }
        }
    }
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {

        /* ---------------- COPILOTO ---------------- */
        CopilotoCard("COPILOTO") {
            SwitchRow(
                label = "Copiloto inteligente",
                checked = settings?.copilotEnabled ?: false,
                onCheckedChange = { value ->
                    scope.launch {
                        val s = app.settingsRepository.load()
                        app.settingsRepository.save(s.copy(copilotEnabled = value))
                    }
                },
                description = "Exibido ao detectar corrida no Uber ou 99"
            )

            // Estado REAL do serviço — nunca indicar ativo quando parado
            StatusChip(
                text = when {
                    settings?.copilotEnabled != true -> "Monitoramento desligado"
                    svcRunning && overlayOk -> "Monitoramento disponível — serviço ativo"
                    svcRunning -> "Leitor ato — falta a permissão de sobreposição"
                    else -> "Serviço parado — nenhuma leitura em andamento"
                },
                color = when {
                    svcRunning && overlayOk && (settings?.copilotEnabled == true) -> SignalGreen
                    svcRunning && (settings?.copilotEnabled == true) -> SignalYellow
                    else -> TextMuted
                }
            )

            if (!svcRunning) {
                Text(
                    "Falta habilitar o Serviço de Acessibilidade: Ajustes › Acessibilidade › Piloto Ninja GO.",
                    style = MaterialTheme.typography.labelSmall, color = SignalYellow
                )
                SmallAction("Gerenciar permissão") { Permissions.openAccessibilitySettings(context as android.app.Activity) }
            }
            if (!overlayOk) {
                Text(
                    "Falta a permissão de exibir sobre outros apps (necessária para o card e o painel).",
                    style = MaterialTheme.typography.labelSmall, color = SignalYellow
                )
                SmallAction("Conceder sobreposição") { Permissions.requestOverlay(context as android.app.Activity) }
            }

            NavRow("Personalizar semáforo de valores") { nav.navigate(Routes.SEMAFORO) }
            NavRow("Câmera sentinela") { nav.navigate(Routes.CAMERA) }
            Text(
                "Grava vídeo e áudio da viagem como prova",
                style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 4.dp)
            )
            if (cameraRecording) {
                StatusChip("● GRAVAÇÃO ATIVA", SignalRed)
            }

            SwitchRow(
                label = "Print de corrida exclusiva",
                checked = settings?.printEnabled ?: false,
                onCheckedChange = { enable ->
                    if (enable) {
                        // Só grava com consentimento explícito — sem captura oculta
                        val mgr = context.getSystemService(MediaProjectionManager::class.java)
                        captureLauncher.launch(mgr.createScreenCaptureIntent())
                        app.updateSetting { it.copy(printEnabled = true) }
                    } else {
                        ScreenCaptureService.stop(context)
                        app.updateSetting { it.copy(printEnabled = false) }
                    }
                },
                description = "Salva uma captura da oferta quando disponível (consentimento do Android)."
            )
            Text(
                "Se a captura automática não for permitida no seu aparelho, use a alternativa manual em Digitar valores.",
                style = MaterialTheme.typography.labelSmall
            )
            SmallAction("Alternativa manual (imagem)") { nav.navigate(Routes.MANUAL) }
        }

        /* ---------------- REGIÃO DE ATUAÇÃO ---------------- */
        val zone by app.zoneMonitor.state.collectAsState()
        val requestLocation = rememberLocationRequester()
        CopilotoCard("REGIÃO DE ATUAÇÃO") {
            val label = zoneLabel(zone)
            when {
                !zone.hasPermission -> {
                    StatusChip("Localização não permitida — sem aviso de região", SignalYellow)
                    SmallAction("Permitir localização", requestLocation)
                }
                label != null -> {
                    val prefix = when (zone.status) {
                        com.copilotodriverpro.regions.ZoneStatus.INSIDE -> "✓ "
                        com.copilotodriverpro.regions.ZoneStatus.OUTSIDE -> "⚠ "
                        else -> ""
                    }
                    StatusChip(
                        prefix + label.text + (zone.regionName?.let { " · $it" } ?: ""),
                        label.color
                    )
                }
                else -> StatusChip("Crie e ative uma região para receber o aviso", TextMuted)
            }
            NavRow("Minhas regiões") { nav.navigate(Routes.REGIONS) }
            if (!Permissions.isIgnoringBatteryOptimizations(context)) {
                Text(
                    "A economia de bateria do aparelho pode parar o aviso com a tela apagada. " +
                        "Escolha \"Não otimizar\" para o Ninja Go.",
                    style = MaterialTheme.typography.labelSmall, color = SignalYellow
                )
                SmallAction("Ajustar bateria") { Permissions.openBatterySettings(context) }
            }
        }

        /* ---------------- TURNO ---------------- */
        CopilotoCard("TURNO") {
            val shift = activeShift
            Row(
                Modifier.fillMaxWidth().clickable { nav.navigate(Routes.SHIFTS) },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    if (shift == null) {
                        Text("Nenhum turno ativo", style = MaterialTheme.typography.bodyLarge)
                        Text("Toque para ver seus turnos", style = MaterialTheme.typography.labelSmall)
                    } else {
                        Text("Turno ativo", style = MaterialTheme.typography.bodyLarge, color = SignalGreen, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Desde ${SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date(shift.startMillis))}",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Ver turnos", tint = TextMuted)
            }

            Button(
                onClick = {
                    scope.launch {
                        if (activeShift == null) shiftVm.start() else shiftVm.end(null)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (activeShift == null) AccentYellow else SignalRed,
                    contentColor = if (activeShift == null) OnAccent else TextWhite
                )
            ) {
                Icon(
                    if (activeShift == null) Icons.Filled.PlayArrow else Icons.Filled.Stop,
                    contentDescription = null
                )
                Text(if (activeShift == null) "  Iniciar Turno" else "  Encerrar Turno")
            }

            SwitchRow(
                label = "Turno automático",
                checked = settings?.autoShiftEnabled ?: false,
                onCheckedChange = { v ->
                    scope.launch {
                        val s = app.settingsRepository.load()
                        app.settingsRepository.save(s.copy(autoShiftEnabled = v))
                    }
                },
                description = "Liga sozinho ao detectar atividade de corrida com o Copiloto ativo."
            )
        }

        /* ---------------- ÚLTIMA ANÁLISE ---------------- */
        CopilotoCard("Última análise") {
            val offer = ui.offer
            val eval = ui.evaluation
            val rec = eval?.recommendationResult
            if (offer == null) {
                Text("Nenhuma oferta analisada ainda.", style = MaterialTheme.typography.bodyMedium)
            } else {
                rec?.let { r ->
                    val (bg, label) = when (r.state) {
                        RecommendationState.ACEITAR -> SignalGreen to "✓ APROVADA"
                        RecommendationState.RECUSAR -> SignalRed to "✗ REPROVADA"
                        RecommendationState.AVALIAR -> SignalYellow to "⚠ ANALISAR"
                        RecommendationState.DADOS_INSUFFICIENTES ->
                            if (eval?.pending == true) SignalYellow to "⚠ PENDENTE DE VALIDAÇÃO"
                            else CardSecondary to "SEM DADOS SUFICIENTES"
                    }
                    Row(
                        Modifier.fillMaxWidth().background(bg, RoundedCornerShape(8.dp)).padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(label, color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    }
                    Text(r.justification, style = MaterialTheme.typography.labelSmall, color = TextMuted)
                }
                Text("Valor: ${offer.fare?.let { "R$ %.2f".format(it) } ?: "Não identificado"}")
                Text("Dist. viagem: ${offer.tripDistanceKm?.let { "%.1f km".format(it) } ?: "Não identificado"}")
                Text("Dist. até passageiro: ${offer.distanceToPickupKm?.let { "%.1f km".format(it) } ?: "Não identificado"}")
                Text("Tempo: ${offer.durationMin?.let { "%.0f min".format(it) } ?: "Não identificado"}")
                Text("Nota: ${offer.passengerRating?.let { "%.1f ★".format(it) } ?: "Nota indisponível"}")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val km = eval?.perKm?.value?.let { "R$ %.2f".format(it) } ?: "—"
                    val hr = eval?.perHour?.value?.let { "R$ %.2f".format(it) } ?: "—"
                    Text("R$/km: $km", color = lightOf(eval?.perKm?.light), fontWeight = FontWeight.Bold)
                    Text("R$/hora: $hr", color = lightOf(eval?.perHour?.light), fontWeight = FontWeight.Bold)
                }
            }
        }

        /* ---------------- ATALHOS ---------------- */
        CopilotoCard("Atalhos") {
            Button(
                onClick = { nav.navigate(Routes.MANUAL) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = AccentYellow)
            ) { Text("Digitar valores (cálculo ao vivo)") }
            OutlinedButton(onClick = { nav.navigate(Routes.REGIONS) }, modifier = Modifier.fillMaxWidth()) {
                Text("Minhas regiões")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                OutlinedButton(onClick = { notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Permitir notificações do painel")
                }
            }
        }

        /* ---------------- DIAGNÓSTICO DO LEITOR ---------------- */
        CopilotoCard("Diagnóstico do leitor") {
            Text(
                if (svcRunning) "✓ Serviço de acessibilidade ATIVO"
                else "⚠ Serviço de acessibilidade DESATIVADO",
                color = if (svcRunning) SignalGreen else SignalYellow,
                fontWeight = FontWeight.Bold
            )
            Text(
                "Aparelho: ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                style = MaterialTheme.typography.labelSmall
            )
            Text("Capturas: ${ui.captureCount}", style = MaterialTheme.typography.labelSmall)
            ui.capturedPackage?.let { Text("Último app lido: $it", style = MaterialTheme.typography.labelSmall) }
            ui.captureNote?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = SignalYellow) }
            ui.offerCandidateText?.let { cand ->
                Text("Último texto da 99 com valor em R$:", style = MaterialTheme.typography.labelSmall)
                Text(
                    cand.take(900),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(CardSecondary, RoundedCornerShape(8.dp))
                        .padding(8.dp)
                )
            }
            val captured = ui.capturedText
            if (captured.isNullOrBlank()) {
                Text(
                    "Nenhum texto capturado ainda. Abra a Uber com uma oferta na tela e volte aqui.",
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                Text("Texto capturado:", style = MaterialTheme.typography.labelSmall)
                Text(
                    captured.take(700),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(CardSecondary, RoundedCornerShape(8.dp))
                        .padding(8.dp)
                )
            }
        }
    }
}

@Composable
private fun StatusChip(text: String, color: Color) {
    Row(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Text(text, color = color, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun NavRow(label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = AccentYellow, modifier = Modifier.weight(1f))
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = AccentYellow)
    }
}

@Composable
private fun SmallAction(label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick) { Text(label) }
}

private fun lightOf(light: com.copilotodriverpro.analyzer.TrafficLight?): Color = when (light) {
    com.copilotodriverpro.analyzer.TrafficLight.GREEN -> SignalGreen
    com.copilotodriverpro.analyzer.TrafficLight.YELLOW -> SignalYellow
    com.copilotodriverpro.analyzer.TrafficLight.RED -> SignalRed
    else -> TextMuted
}

/** Verifica se o AccessibilityService deste app está habilitado no sistema. */
private fun isAccessibilityEnabled(context: android.content.Context): Boolean {
    val svcName = "${context.packageName}/${com.copilotodriverpro.accessibility.CopilotAccessibilityService::class.java.name}"
    val enabled = android.provider.Settings.Secure.getString(
        context.contentResolver,
        android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false
    return enabled.split(':').any { it.equals(svcName, ignoreCase = true) }
}
