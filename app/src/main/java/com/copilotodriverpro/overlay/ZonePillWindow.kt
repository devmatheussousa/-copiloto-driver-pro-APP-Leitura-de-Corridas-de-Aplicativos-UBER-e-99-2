package com.copilotodriverpro.overlay

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.copilotodriverpro.CopilotApp
import com.copilotodriverpro.regions.ZoneStatus
import com.copilotodriverpro.ui.theme.CopilotoTheme
import com.copilotodriverpro.zone.zoneLabel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/**
 * Selo fixo no topo da tela, por cima do Uber/99: "DENTRO DA REGIÃO DE ATUAÇÃO" (verde) ou
 * "FORA DA REGIÃO DE ATUAÇÃO" (vermelho). Ao entrar/sair, o selo cresce por alguns segundos.
 *
 * Janela de acessibilidade (não precisa da permissão "exibir sobre outros apps"), NÃO tocável:
 * nunca atrapalha os toques no app de corrida. Nasce e morre com o leitor.
 */
class ZonePillWindow(
    private val service: AccessibilityService,
    private val app: CopilotApp
) {
    private val wm = service.getSystemService(WindowManager::class.java)
    private var composeView: ComposeView? = null
    private var viewOwner: OverlayViewOwner? = null

    fun start() {
        if (composeView != null) return
        val owner = OverlayViewOwner()
        viewOwner = owner
        val view = ComposeView(service).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setContent {
                CopilotoTheme(darkTheme = true) {
                    ZonePill(app)
                }
            }
        }

        val density = service.resources.displayMetrics.density
        val statusBarId = service.resources.getIdentifier("status_bar_height", "dimen", "android")
        val statusBar = if (statusBarId > 0) service.resources.getDimensionPixelSize(statusBarId) else (24 * density).toInt()

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = 0
            y = statusBar + (2 * density).toInt()
        }
        wm.addView(view, params)
        composeView = view
        owner.resume()
    }

    fun stop() {
        composeView?.let { runCatching { wm.removeView(it) } }
        composeView = null
        viewOwner?.destroy()
        viewOwner = null
    }
}

@Composable
private fun ZonePill(app: CopilotApp) {
    val zone by app.zoneMonitor.state.collectAsState()
    val settings by app.settingsRepository.settings.collectAsState(initial = null)
    var big by remember { mutableStateOf(false) }

    // Ao entrar/sair da região o selo cresce por 6 s e volta ao tamanho discreto.
    LaunchedEffect(Unit) {
        app.zoneMonitor.events.collectLatest {
            big = true
            delay(6_000)
            big = false
        }
    }

    val label = zoneLabel(zone)
    val enabled = settings?.zonePillEnabled != false && settings?.zoneMonitorEnabled != false
    if (label == null || !enabled) return

    Column(
        modifier = Modifier
            .background(Color(0xE6070707), RoundedCornerShape(if (big) 14.dp else 10.dp))
            .padding(horizontal = if (big) 16.dp else 10.dp, vertical = if (big) 8.dp else 3.dp)
    ) {
        Text(
            text = (if (zone.status == ZoneStatus.OUTSIDE) "⚠ " else if (zone.status == ZoneStatus.INSIDE) "✓ " else "") + label.text,
            color = label.color,
            fontWeight = FontWeight.ExtraBold,
            fontSize = if (big) 16.sp else 11.sp
        )
        if (big && zone.status == ZoneStatus.INSIDE && zone.regionName != null) {
            Text(zone.regionName ?: "", color = Color(0xFFF5F5F5), fontSize = 12.sp)
        }
    }
}
