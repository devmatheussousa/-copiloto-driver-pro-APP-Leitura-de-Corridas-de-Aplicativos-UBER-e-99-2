package com.copilotodriverpro.overlay

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.util.Log
import android.view.View
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Density
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.copilotodriverpro.CopilotApp
import com.copilotodriverpro.analyzer.RecommendationState
import com.copilotodriverpro.analyzer.TrafficLight
import com.copilotodriverpro.models.RideSource
import com.copilotodriverpro.models.RideOffer
import com.copilotodriverpro.models.StopVerdict
import com.copilotodriverpro.regions.RideRegionAdvisor
import com.copilotodriverpro.regions.RideRegionLevel
import com.copilotodriverpro.regions.RideRegionVerdict
import com.copilotodriverpro.state.CopilotUiState
import com.copilotodriverpro.ui.theme.CopilotoTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Card de análise de corrida no TOPO da tela.
 *
 * Janela real do WindowManager (TYPE_APPLICATION_OVERLAY) possuída pelo
 * AccessibilityService: nasce com o leitor, morre com ele, sem serviço
 * adicional nem notificação. Observa [CopilotApp.rideState] — o MESMO estado
 * que o leitor publica — e usa exatamente a avaliação do motor existente
 * (RideAnalyzer/ProfitabilityCalculator), sem regras duplicadas.
 *
 * - Fundo claro; borda verde (aprovada) / vermelha (reprovada) / âmbar (analisar) / cinza (sem dados).
 * - Indicadores em colunas compactas; somente dados disponíveis (nunca inventa valor).
 * - Arrastável com posição persistida; minimizar; fechar por oferta.
 * - Dedup por id: fechado, não reaparece até uma oferta NOVA chegar.
 */
class RideCardWindow(
    private val service: AccessibilityService,
    private val app: CopilotApp
) {

    private val wm = service.getSystemService(WindowManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var composeView: ComposeView? = null
    private var viewOwner: OverlayViewOwner? = null

    /** Cria a janela se a permissão de sobreposição estiver concedida. */
    fun start() {
        if (composeView != null) return
        // TYPE_ACCESSIBILITY_OVERLAY dispensa a permissão "Exibir sobre outros apps":
        // quem já ativou o serviço de acessibilidade pode desenhar o card.

        val owner = OverlayViewOwner()
        viewOwner = owner
        val view = ComposeView(service).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)

            val state = app.rideState.ui
            val settingsFlow = app.settingsRepository.settings
            setContent {
                CopilotoTheme(darkTheme = true) {
                    val ui by state.collectAsState()
                    val settings by settingsFlow.collectAsState(initial = null)
                    // Onde o motorista está AGORA (GPS) — só vale se o GPS está ativo.
                    val zone by app.zoneMonitor.state.collectAsState()

                    // Dedup: estado de UI mutável por composição desta janela
                    var dismissedId by remember { mutableStateOf<String?>(null) }
                    var minimizedId by remember { mutableStateOf<String?>(null) }

                    val offer = ui.offer

                    // O card fica ENQUANTO a oferta estiver na tela do Uber/99 e some junto com ela.
                    // Quem encerra a oferta é o leitor (com tolerância contra leitura ruim isolada);
                    // não há mais limite de tempo próprio nem relógio recompondo o card a cada 250 ms.
                    val visible = offer != null &&
                        offer.id != dismissedId &&
                        settings?.rideCardEnabled != false

                    // "Esta corrida me joga para fora da região?" — mesma regra do painel e dos testes.
                    val s = settings
                    val regionVerdict: RideRegionVerdict? =
                        if (s != null && s.rideRegionAlertEnabled) {
                            RideRegionAdvisor.verdict(
                                check = ui.regionCheck,
                                hasRegions = ui.hasRegions,
                                driver = if (zone.monitoring) zone.status else null,
                                checkPickup = s.checkPickupRegion,
                                checkDestination = s.checkDestinationRegion
                            )
                        } else null

                    // Vibra/fala UMA vez por corrida quando ela sai da região.
                    LaunchedEffect(offer?.id, regionVerdict?.level) {
                        if (visible && s != null && regionVerdict?.level == RideRegionLevel.OUT) {
                            app.zoneMonitor.alertRideOutside(s)
                        }
                    }

                    if (visible && offer != null) {
                      // Janela de LARGURA TOTAL + conteúdo centralizado aqui dentro: não existe mais
                      // deslocamento horizontal da janela, então o card nunca sai pro lado.
                      val baseDensity = LocalDensity.current
                      // Tablets (menor lado >= 600 dp): card 20% maior para ficar legível à distância.
                      val tabletBoost = if (service.resources.configuration.smallestScreenWidthDp >= 600) 1.2f else 1f
                      val scale = (settings?.cardScale ?: 1.0f).coerceIn(0.5f, 1.5f) * tabletBoost
                      CompositionLocalProvider(
                          LocalDensity provides Density(baseDensity.density * scale, baseDensity.fontScale)
                      ) {
                      Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                        CardSurface(
                            ui = ui,
                            offer = offer,
                            region = regionVerdict,
                            minimized = minimizedId == offer.id,
                            opacity = settings?.cardOpacity ?: 1.0f,
                            onMinimize = { minimizedId = if (minimizedId == offer.id) null else offer.id },
                            onDismiss = { dismissedId = offer.id },
                            modifier = Modifier
                                .pointerInput(offer.id) {
                                    detectDragGestures(
                                        onDragEnd = { persistPosition() },
                                        onDragCancel = { persistPosition() }
                                    ) { change, dragAmount ->
                                        change.consume()
                                        val lp = layoutParams as WindowManager.LayoutParams
                                        // só arrasta na vertical; y cresce para baixo
                                        lp.y += dragAmount.y.toInt()
                                        clampToScreen(lp, this@apply)
                                        wm.updateViewLayout(this@apply, lp)
                                    }
                                }
                        )
                      }
                      }
                    }
                }
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP
            x = 0
            // abaixo da barra de status e do selo de região (26 dp a mais que antes)
            y = 110 + (26 * service.resources.displayMetrics.density).toInt()
        }

        wm.addView(view, params)
        composeView = view
        owner.resume()
        startHardKill()
    }

    /**
     * Trava no nível da JANELA (independe do Compose): a janela fica GONE quando não há oferta ou
     * quando a licença do leitor venceu (rede de segurança: o leitor renova a cada ~150 ms enquanto
     * a oferta está na tela). Não existe mais limite de tempo de exibição: o card fica enquanto a
     * oferta estiver visível. Volta a aparecer só com uma oferta nova.
     */
    private fun startHardKill() {
        // Instantâneo: no momento em que a oferta é limpa do estado, a janela some.
        scope.launch {
            app.rideState.ui.collect { ui ->
                if (ui.offer == null) composeView?.visibility = View.GONE
            }
        }
        scope.launch {
            while (isActive) {
                delay(100)
                val view = composeView ?: continue
                val store = app.rideState
                val offer = store.ui.value.offer
                val now = System.currentTimeMillis()
                val leaseDead = offer?.source == RideSource.ACCESSIBILITY &&
                    now >= store.offerLeaseUntil
                val hide = offer == null || leaseDead
                val target = if (hide) View.GONE else View.VISIBLE
                if (view.visibility != target) {
                    view.visibility = target
                    Log.d("Copiloto", "Card ${if (hide) "ESCONDIDO" else "VISÍVEL"} " +
                        "(oferta=${offer != null} licencaVenceu=$leaseDead)")
                }
            }
        }
    }

    /** Remove a janela e cancela as corrotas — chamado no fim do serviço. */
    fun stop() {
        composeView?.let { runCatching { wm.removeView(it) } }
        composeView = null
        viewOwner?.destroy()
        viewOwner = null
        scope.cancel()
    }

    /**
     * Mantém o card SEMPRE visível. A posição antiga salva (de arrastos com o eixo
     * invertido) jogava o card para fora da tela, por isso não é mais restaurada.
     */
    private fun clampToScreen(lp: WindowManager.LayoutParams, view: android.view.View) {
        val dm = service.resources.displayMetrics
        lp.x = 0
        val maxY = (dm.heightPixels - view.height).coerceAtLeast(0)
        lp.y = lp.y.coerceIn(0, maxY)
    }

    private fun persistPosition() { /* posição só vale na sessão atual */ }
}

/* ---------- Visual: card claro com borda de estado ---------- */

/** Borda e rótulo vêm do motor de recomendação existente — nada duplicado. */
private fun statusBorder(
    state: RecommendationState?,
    pending: Boolean = false,
    kmLight: TrafficLight? = null,
    hourLight: TrafficLight? = null
): Pair<Color, String> {
    if (pending) {
        return Color(0xFFF5A623) to "PENDENTE"
    }
    val effectiveLight = when {
        kmLight == TrafficLight.RED || hourLight == TrafficLight.RED -> TrafficLight.RED
        kmLight == TrafficLight.YELLOW || hourLight == TrafficLight.YELLOW -> TrafficLight.YELLOW
        kmLight == TrafficLight.GREEN && hourLight == TrafficLight.GREEN -> TrafficLight.GREEN
        else -> null
    }

    val color = when (effectiveLight) {
        TrafficLight.GREEN -> Color(0xFF13B889)
        TrafficLight.YELLOW -> Color(0xFFF5A623)
        TrafficLight.RED -> Color(0xFFF0444F)
        else -> when (state) {
            RecommendationState.ACEITAR -> Color(0xFF13B889)
            RecommendationState.RECUSAR -> Color(0xFFF0444F)
            RecommendationState.AVALIAR -> Color(0xFFF5A623)
            else -> Color(0xFF2E2E2E)
        }
    }
    val label = when (color) {
        Color(0xFF13B889) -> "APROVADA"
        Color(0xFFF5A623) -> "ANALISAR"
        Color(0xFFF0444F) -> "REPROVADA"
        else -> "SEM DADOS"
    }
    return color to label
}

@Composable
private fun CardSurface(
    ui: CopilotUiState,
    offer: RideOffer,
    region: RideRegionVerdict?,
    minimized: Boolean,
    opacity: Float,
    onMinimize: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val eval = ui.evaluation
    val (borderColor, _) = statusBorder(
        eval?.recommendationResult?.state,
        eval?.pending == true,
        eval?.perKm?.light,
        eval?.perHour?.light
    )

    if (minimized) {
        // Pílula compacta: toque para expandir de volta.
        Card(
            modifier = modifier
                .alpha(opacity)
                .shadow(6.dp, RoundedCornerShape(24.dp))
                .clip(RoundedCornerShape(24.dp))
                .clickable(onClick = onMinimize),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
            border = BorderStroke(2.dp, borderColor)
        ) {
            Row(
                Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    offer.fare?.let { "R$ %.2f".format(it) } ?: "—",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = Color(0xFFF5F5F5)
                )
                Text(
                    decisionText(eval?.recommendationResult?.state, eval?.pending == true),
                    fontSize = 12.sp, color = borderColor, fontWeight = FontWeight.ExtraBold
                )
                if (region?.level == RideRegionLevel.OUT) {
                    Text(
                        "⚠ FORA DA REGIÃO",
                        fontSize = 11.sp, color = Color(0xFFF0444F), fontWeight = FontWeight.ExtraBold
                    )
                }
            }
        }
        return
    }

    Card(
        modifier = modifier
            .alpha(opacity)
            .padding(horizontal = 8.dp)
            .shadow(6.dp, RoundedCornerShape(14.dp))
            .widthIn(max = 320.dp)
            .fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF141414)),
        border = BorderStroke(2.dp, borderColor),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {

            // Cabeçalho enxuto: só os três indicadores e os botões (sem valor da corrida, sem
            // texto APROVADA/REPROVADA, sem linha de distâncias/tempo). A decisão aparece na cor
            // da borda; os dados continuam no RideOffer/OfferEvaluation para os cálculos.
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val m = eval?.financialMetrics
                    MetricColumn(
                        "R$/km", eval?.perKm?.value?.let { v -> "R$ %.2f".format(v) },
                        eval?.perKm?.light ?: TrafficLight.NONE, Modifier.weight(1f)
                    )
                    MetricColumn(
                        "R$/hora", eval?.perHour?.value?.let { v -> "R$ %.2f".format(v) },
                        eval?.perHour?.light ?: TrafficLight.NONE, Modifier.weight(1f)
                    )
                    MetricColumn(
                        "R$/min", m?.grossPerMinute?.let { v -> "R$ %.2f".format(v) },
                        TrafficLight.NONE, Modifier.weight(1f)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    Icon(
                        Icons.Filled.Remove, contentDescription = "Minimizar",
                        tint = Color(0xFFA8A8A8),
                        modifier = Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .clickable(onClick = onMinimize)
                            .padding(4.dp)
                    )
                    Icon(
                        Icons.Filled.Close, contentDescription = "Fechar",
                        tint = Color(0xFFA8A8A8),
                        modifier = Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .clickable(onClick = onDismiss)
                            .padding(4.dp)
                    )
                }
            }

            // Região: dentro / fora / "esta corrida te leva para fora" (se a opção estiver ligada)
            if (region != null) RegionStrip(region)

            // Alerta de parada — só quando configurado e identificado
            when (eval?.stopVerdict) {
                StopVerdict.CONFIRMED -> Text(
                    "⚠ CORRIDA COM PARADA — ANALISE COM ATENÇÃO",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFF0444F), RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 5.dp)
                )
                StopVerdict.POSSIBLE -> Text(
                    "⚠ Possível parada — confirme na oferta",
                    color = Color(0xFFF5A623),
                    fontSize = 12.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0x33F5A623), RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 5.dp)
                )
                else -> Unit
            }
        }
    }
}

/** Faixa de região: vermelha cheia quando a corrida sai da região; discreta nos demais casos. */
@Composable
private fun RegionStrip(v: RideRegionVerdict) {
    val color = when (v.level) {
        RideRegionLevel.OUT -> Color(0xFFF0444F)
        RideRegionLevel.IN, RideRegionLevel.BACK_IN -> Color(0xFF13B889)
        RideRegionLevel.CHECKING -> Color(0xFFF5A623)
        RideRegionLevel.UNKNOWN -> Color(0xFFA8A8A8)
    }
    val solid = v.level == RideRegionLevel.OUT
    Column(
        Modifier
            .fillMaxWidth()
            .background(if (solid) color else color.copy(alpha = 0.14f), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 5.dp)
    ) {
        Text(
            v.title,
            color = if (solid) Color.White else color,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp
        )
        v.detail?.let {
            Text(
                it,
                color = if (solid) Color.White.copy(alpha = 0.92f) else Color(0xFFA8A8A8),
                fontSize = 10.sp
            )
        }
    }
}

/** Texto da decisão, derivado do mesmo estado do motor (nada de regra duplicada). */
private fun decisionText(state: RecommendationState?, pending: Boolean = false): String = if (pending) "⚠ PENDENTE" else when (state) {
    RecommendationState.ACEITAR -> "✔ PEGAR CORRIDA"
    RecommendationState.RECUSAR -> "✖ NÃO PEGAR"
    RecommendationState.AVALIAR -> "⚠ ANALISAR"
    else -> "… SEM DADOS"
}

@Composable
private fun MetricColumn(
    label: String,
    value: String?,
    light: TrafficLight,
    modifier: Modifier = Modifier
) {
    val color = when (light) {
        TrafficLight.RED -> Color(0xFFF0444F)
        TrafficLight.YELLOW -> Color(0xFFF5A623)
        TrafficLight.GREEN -> Color(0xFF13B889)
        TrafficLight.NONE -> Color(0xFFA8A8A8)
    }
    Column(
        modifier = modifier
            .background(color.copy(alpha = 0.10f), RoundedCornerShape(10.dp))
            .padding(horizontal = 6.dp, vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.AttachMoney, contentDescription = null, tint = color, modifier = Modifier.size(12.dp))
            Text(label, fontSize = 9.sp, color = Color(0xFFA8A8A8))
        }
        Text(
            value ?: "—",
            color = if (value != null) color else Color(0xFFA8A8A8),
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp
        )
    }
}
