package com.copilotodriverpro.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
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
import com.copilotodriverpro.MainActivity
import com.copilotodriverpro.R
import com.copilotodriverpro.analyzer.Recommendation
import com.copilotodriverpro.analyzer.TrafficLight
import com.copilotodriverpro.models.StopVerdict
import com.copilotodriverpro.regions.ZoneStatus
import com.copilotodriverpro.utils.Permissions
import com.copilotodriverpro.zone.ZoneUiState
import com.copilotodriverpro.zone.zoneLabel
import com.copilotodriverpro.settings.FontSize
import com.copilotodriverpro.ui.theme.CopilotoTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Serviço do botão flutuante + painel, janela real do WindowManager
 * (TYPE_APPLICATION_OVERLAY) sobre outros apps.
 *
 * - Botão circular arrastável, posição persistida.
 * - Toque expande o painel; recolher volta ao círculo.
 * - Recomendação direta (ACEITAR / RECUSAR), métricas, semáforo, região e alerta de parada.
 * - Botões: recolher, pausar (limpa estado), abrir app, fechar.
 */
class FloatingPanelService : Service() {

    private val app get() = application as CopilotApp
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var wm: WindowManager

    private var composeView: ComposeView? = null
    private var viewOwner: OverlayViewOwner? = null
    private var expanded = false
    private var attaching = false
    @Volatile private var floatingWanted = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createChannel()
        promoteToForeground()

        // Este serviço também é o "anfitrião" do GPS: mantém o app vivo em segundo plano
        // para o aviso de região continuar funcionando com a tela do Uber aberta.
        app.zoneMonitor.acquire("service")

        // Botão flutuante: só aparece se estiver ligado E houver permissão de sobreposição.
        scope.launch {
            app.settingsRepository.settings.map { it.floatingEnabled }.distinctUntilChanged().collect { want ->
                floatingWanted = want
                syncWindow()
            }
        }
        // Texto da notificação acompanha "dentro / fora da região".
        scope.launch {
            app.zoneMonitor.state.map { it.status to it.monitoring }.distinctUntilChanged().collect {
                updateNotification()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CLOSE -> {
                scope.launch {
                    val repo = app.settingsRepository
                    val s = repo.settings.first()
                    repo.save(s.copy(floatingEnabled = false, zoneMonitorEnabled = false))
                    stopSelf()
                }
            }
            else -> {
                // Reinício do app: a permissão de localização/sobreposição pode ter mudado.
                promoteToForeground()
                syncWindow()
            }
        }
        return START_STICKY
    }

    private fun syncWindow() {
        if (floatingWanted && Permissions.hasOverlay(this)) {
            scope.launch { attachWindow() }
        } else {
            detachWindow()
        }
    }

    private suspend fun attachWindow() {
        if (composeView != null || attaching) return
        attaching = true
        try {
            val saved = app.settingsRepository.settings.first()

            val owner = OverlayViewOwner()
            viewOwner = owner

            val view = ComposeView(this).apply {
                setViewTreeLifecycleOwner(owner)
                setViewTreeViewModelStoreOwner(owner)
                setViewTreeSavedStateRegistryOwner(owner)

                val state = app.rideState.ui
                val settingsFlow = app.settingsRepository.settings
                val zoneFlow = app.zoneMonitor.state
                setContent {
                    CopilotoTheme(darkTheme = true) {
                        val ui by state.collectAsState()
                        val settings by settingsFlow.collectAsState(initial = null)
                        val zone by zoneFlow.collectAsState()
                        Box(
                            Modifier.pointerInput(Unit) {
                                detectDragGestures(
                                    onDragEnd = { persistPosition() },
                                    onDragCancel = { persistPosition() }
                                ) { change, dragAmount ->
                                    change.consume()
                                    val lp = layoutParams as WindowManager.LayoutParams
                                    lp.x -= dragAmount.x.toInt()
                                    lp.y -= dragAmount.y.toInt()
                                    wm.updateViewLayout(this@apply, lp)
                                }
                            }
                        ) {
                            if (expanded) {
                                ExpandedPanel(
                                    settings = settings,
                                    ui = ui,
                                    zone = zone,
                                    onCollapse = { setExpanded(false) },
                                    onDismiss = {
                                        app.rideState.clear()
                                        setExpanded(false)
                                    },
                                    onPause = {
                                        app.rideState.clear()
                                        setExpanded(false)
                                    },
                                    onOpenApp = {
                                        startActivity(
                                            Intent(this@FloatingPanelService, MainActivity::class.java)
                                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        )
                                    }
                                )
                            } else {
                                CollapsedFab(
                                    ui = ui,
                                    zone = zone,
                                    onClick = { setExpanded(true) }
                                )
                            }
                        }
                    }
                }
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = if (saved.fabX >= 0) saved.fabX else 40
                y = if (saved.fabY >= 0) saved.fabY else 400
            }

            try {
                wm.addView(view, params)
                composeView = view
                owner.resume()
            } catch (t: Throwable) {
                // Sem permissão de sobreposição (ou janela recusada): o resto do serviço continua.
                Log.w(TAG, "Não foi possível criar o botão flutuante", t)
                viewOwner?.destroy()
                viewOwner = null
            }
            if (!floatingWanted) detachWindow()
        } finally {
            attaching = false
        }
    }

    private fun detachWindow() {
        composeView?.let { runCatching { wm.removeView(it) } }
        composeView = null
        viewOwner?.destroy()
        viewOwner = null
        expanded = false
    }

    private fun setExpanded(value: Boolean) {
        val view = composeView ?: return
        expanded = value
        val lp = view.layoutParams as WindowManager.LayoutParams
        lp.width = WindowManager.LayoutParams.WRAP_CONTENT
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT
        wm.updateViewLayout(view, lp)
        view.requestLayout()
    }

    override fun onDestroy() {
        detachWindow()
        app.zoneMonitor.release("service")
        scope.cancel()
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.overlay_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    /** Em primeiro plano; o tipo "location" só entra com a permissão (Android 14 exige). */
    private fun promoteToForeground() {
        val n = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, n, foregroundType(withLocation = true))
            } else {
                startForeground(NOTIFICATION_ID, n)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "startForeground com localização falhou; usando só o tipo básico", t)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceCompat.startForeground(this, NOTIFICATION_ID, n, foregroundType(withLocation = false))
                } else {
                    startForeground(NOTIFICATION_ID, n)
                }
            }
        }
    }

    private fun foregroundType(withLocation: Boolean): Int {
        var type = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        }
        if (withLocation && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && Permissions.hasFineLocation(this)) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
        return type
    }

    private fun updateNotification() {
        runCatching {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun zoneSentence(z: ZoneUiState): String? = when {
        !z.monitoring -> null
        z.status == ZoneStatus.INSIDE ->
            "Dentro da região de atuação" + (z.regionName?.let { " · $it" } ?: "")
        z.status == ZoneStatus.OUTSIDE -> "Fora da região de atuação"
        z.status == ZoneStatus.UNKNOWN -> "GPS sem sinal"
        else -> null
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val closeIntent = PendingIntent.getService(
            this, 1,
            Intent(this, FloatingPanelService::class.java).setAction(ACTION_CLOSE),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_copiloto)
            .setContentTitle(getString(R.string.overlay_notification_title))
            .setContentText(zoneSentence(app.zoneMonitor.state.value) ?: getString(R.string.overlay_notification_text))
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp)
            .addAction(0, "Fechar", closeIntent)
            .setOngoing(true)
            .build()
    }

    /** Persiste a posição do botão ao soltar o arraste. */
    private fun persistPosition() {
        val view = composeView ?: return
        val lp = view.layoutParams as WindowManager.LayoutParams
        scope.launch { app.settingsRepository.saveFabPosition(lp.x, lp.y) }
    }

    companion object {
        private const val CHANNEL_ID = "copiloto_overlay"
        private const val NOTIFICATION_ID = 101
        private const val TAG = "CopilotoFloat"
        const val ACTION_CLOSE = "com.copilotodriverpro.CLOSE"

        fun start(context: Context) {
            val intent = Intent(context, FloatingPanelService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingPanelService::class.java))
        }
    }
}

/* ---------- Conteúdo Compose do overlay ---------- */

private fun statusColor(ui: com.copilotodriverpro.state.CopilotUiState): Color = when {
    ui.evaluation?.recommendation == Recommendation.ACCEPT -> Color(0xFF13B889)
    ui.evaluation?.recommendation == Recommendation.REJECT -> Color(0xFFF0444F)
    ui.evaluation?.recommendation == Recommendation.CAUTION -> Color(0xFFF5A623)
    ui.analyzing -> Color(0xFFF5A623)
    else -> Color(0xFF13B889)
}

@Composable
private fun CollapsedFab(
    ui: com.copilotodriverpro.state.CopilotUiState,
    zone: ZoneUiState,
    onClick: () -> Unit
) {
    // Anel: verde = dentro da região, vermelho = fora, cinza = sem sinal de GPS.
    val ring = zoneLabel(zone)?.color
    Box(
        modifier = Modifier
            .size(52.dp)
            .then(if (ring != null) Modifier.border(2.dp, ring, CircleShape) else Modifier)
            .clip(CircleShape)
            .background(Color(0xE6141414))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            "N",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp
        )
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(12.dp)
                .clip(CircleShape)
                .background(statusColor(ui))
        )
    }
}

@Composable
private fun ExpandedPanel(
    settings: com.copilotodriverpro.settings.DriverSettings?,
    ui: com.copilotodriverpro.state.CopilotUiState,
    zone: ZoneUiState,
    onCollapse: () -> Unit,
    onDismiss: () -> Unit,
    onPause: () -> Unit,
    onOpenApp: () -> Unit
) {
    val fs = settings?.fontSize ?: FontSize.MEDIUM
    val big = when (fs) {
        FontSize.SMALL -> 13.sp
        FontSize.MEDIUM -> 16.sp
        FontSize.LARGE -> 20.sp
    }
    val eval = ui.evaluation
    // Largura se adapta: ~78% da tela em celular pequeno, no máximo 340 dp em tablet.
    val panelWidth = (LocalConfiguration.current.screenWidthDp * 0.78f).coerceIn(240f, 340f).dp

    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xF0141414))
            .padding(12.dp)
            .width(panelWidth),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Onde o motorista está agora: dentro ou fora da região de atuação
        zoneLabel(zone)?.let { label ->
            Text(
                text = (if (zone.status == ZoneStatus.OUTSIDE) "⚠ " else if (zone.status == ZoneStatus.INSIDE) "✓ " else "") + label.text,
                color = label.color,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(label.color.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            )
        }

        // Banner de Recomendação em destaque (ACEITAR / RECUSAR / ATENÇÃO)
        if (eval != null) {
            val (bgColor, textColor, label) = when (eval.recommendation) {
                Recommendation.ACCEPT -> Triple(Color(0xFF13B889), Color.White, "✓ ACEITAR CORRIDA")
                Recommendation.REJECT -> Triple(Color(0xFFF0444F), Color.White, "✗ RECUSAR CORRIDA")
                Recommendation.CAUTION -> Triple(Color(0xFFF5A623), Color.White, "⚠ ATENÇÃO / ANALISAR")
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(bgColor, RoundedCornerShape(10.dp))
                    .padding(8.dp)
            ) {
                Text(
                    text = label,
                    color = textColor,
                    fontWeight = FontWeight.Bold,
                    fontSize = big
                )
                if (eval.recommendationReason.isNotBlank()) {
                    Text(
                        text = eval.recommendationReason,
                        color = textColor.copy(alpha = 0.95f),
                        fontSize = 12.sp
                    )
                }
            }
        }

        // Alerta de parada
        if (settings?.stopAlertEnabled != false && ui.offer?.stopVerdict != null &&
            ui.offer.stopVerdict != StopVerdict.NONE
        ) {
            Text(
                text = if (ui.offer.stopVerdict == StopVerdict.CONFIRMED)
                    "⚠ CORRIDA COM PARADA — ANALISE COM ATENÇÃO"
                else
                    "⚠ Possível parada — confirme na oferta",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = big,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFF0444F), RoundedCornerShape(10.dp))
                    .padding(8.dp)
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (settings?.showPerKm != false) {
                MetricBlock(
                    label = "R$/km",
                    value = eval?.perKm?.value?.let { String.format("R$ %.2f", it) } ?: "—",
                    light = eval?.perKm?.light ?: TrafficLight.NONE,
                    fontSize = big
                )
            }
            if (settings?.showPerHour != false) {
                MetricBlock(
                    label = "R$/hora",
                    value = eval?.perHour?.value?.let { String.format("R$ %.2f", it) } ?: "—",
                    light = eval?.perHour?.light ?: TrafficLight.NONE,
                    fontSize = big
                )
            }
        }

        if (settings?.compactMode == false) {
            if (settings.showPassengerRating) {
                val rating = ui.offer?.passengerRating
                Text(
                    text = rating?.let {
                        val below = settings.minRating.let { m -> it < m }
                        if (below) "★ $it — nota baixa" else "★ $it"
                    } ?: "Nota indisponível",
                    color = if (rating != null && rating < settings.minRating) Color(0xFFF5A623) else Color(0xFFA8A8A8),
                    fontSize = big
                )
            }
            RegionBadge(ui, settings, zone)
        } else {
            RegionBadge(ui, settings, zone)
        }

        // Linha de botões
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            SmallButton(Icons.Filled.Undo, "Recolher", onCollapse)
            SmallButton(Icons.Filled.Close, "Limpar", onDismiss)
            SmallButton(Icons.Filled.PlayArrow, "Pausar", onPause)
            SmallButton(Icons.Filled.Apps, "Abrir app", onOpenApp)
        }
    }
}

@Composable
private fun RegionBadge(
    ui: com.copilotodriverpro.state.CopilotUiState,
    settings: com.copilotodriverpro.settings.DriverSettings?,
    zone: ZoneUiState
) {
    // Mesma regra do card da corrida (RideRegionAdvisor): painel e card nunca discordam.
    val verdict = com.copilotodriverpro.regions.RideRegionAdvisor.verdict(
        check = ui.regionCheck,
        hasRegions = ui.hasRegions,
        driver = if (zone.monitoring) zone.status else null,
        checkPickup = settings?.checkPickupRegion ?: true,
        checkDestination = settings?.checkDestinationRegion ?: true
    )
    val (text, color) = when {
        !ui.hasRegions -> "Filtro geográfico não configurado" to Color(0xFFF5A623)
        verdict == null -> "LOCALIZAÇÃO NÃO IDENTIFICADA" to Color(0xFFA8A8A8)
        else -> {
            val c = when (verdict.level) {
                com.copilotodriverpro.regions.RideRegionLevel.OUT -> Color(0xFFF0444F)
                com.copilotodriverpro.regions.RideRegionLevel.IN,
                com.copilotodriverpro.regions.RideRegionLevel.BACK_IN -> Color(0xFF13B889)
                com.copilotodriverpro.regions.RideRegionLevel.CHECKING -> Color(0xFFF5A623)
                com.copilotodriverpro.regions.RideRegionLevel.UNKNOWN -> Color(0xFFA8A8A8)
            }
            (verdict.title + (verdict.detail?.let { " — $it" } ?: "")) to c
        }
    }
    Text(
        text = text,
        color = color,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xCC141414), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp)
    )
}

@Composable
private fun MetricBlock(label: String, value: String, light: TrafficLight, fontSize: androidx.compose.ui.unit.TextUnit) {
    val color = when (light) {
        TrafficLight.RED -> Color(0xFFF0444F)
        TrafficLight.YELLOW -> Color(0xFFF5A623)
        TrafficLight.GREEN -> Color(0xFF13B889)
        TrafficLight.NONE -> Color(0xFFA8A8A8)
    }
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(color.copy(alpha = 0.18f))
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(text = label, color = Color(0xFFA8A8A8), fontSize = 11.sp)
        Text(text = value, color = color, fontWeight = FontWeight.Bold, fontSize = fontSize)
    }
}

@Composable
private fun SmallButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    cd: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(Color(0x33FECA07))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = cd, tint = Color(0xFFA8A8A8), modifier = Modifier.size(18.dp))
    }
}
