package com.copilotodriverpro.zone

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.copilotodriverpro.CopilotApp
import com.copilotodriverpro.regions.ZoneStatus
import com.copilotodriverpro.ui.theme.SignalGreen
import com.copilotodriverpro.ui.theme.SignalRed
import com.copilotodriverpro.ui.theme.TextMuted
import com.copilotodriverpro.utils.Permissions

/** Texto + cor do selo "Dentro/Fora da região de atuação". null = não mostrar nada. */
data class ZoneLabel(val text: String, val color: Color)

fun zoneLabel(z: ZoneUiState): ZoneLabel? = when {
    !z.monitoring -> null
    z.status == ZoneStatus.INSIDE -> ZoneLabel("DENTRO DA REGIÃO DE ATUAÇÃO", SignalGreen)
    z.status == ZoneStatus.OUTSIDE -> ZoneLabel("FORA DA REGIÃO DE ATUAÇÃO", SignalRed)
    z.status == ZoneStatus.UNKNOWN -> ZoneLabel("GPS SEM SINAL", TextMuted)
    else -> null // NO_REGIONS: sem região ativa não há o que avisar
}

/**
 * Devolve uma função que pede a permissão de localização (e de notificações no Android 13+)
 * e, ao terminar, religa o monitor e o serviço em primeiro plano.
 */
@Composable
fun rememberLocationRequester(): () -> Unit {
    val context = LocalContext.current
    val app = context.applicationContext as CopilotApp
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        app.zoneMonitor.refresh()
        app.syncHostService()
    }
    return { launcher.launch(Permissions.locationRuntimePermissions(context)) }
}
