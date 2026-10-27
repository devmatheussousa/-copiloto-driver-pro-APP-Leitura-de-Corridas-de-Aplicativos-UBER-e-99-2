package com.copilotodriverpro.ui.screens

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.copilotodriverpro.CopilotApp
import com.copilotodriverpro.R
import com.copilotodriverpro.database.RideEntity
import com.copilotodriverpro.finance.EntrySummary
import com.copilotodriverpro.finance.SummaryPeriod
import com.copilotodriverpro.history.AcceptedRideRecorder
import com.copilotodriverpro.models.SupportedPlatform
import com.copilotodriverpro.ui.components.FolderTabsCard
import com.copilotodriverpro.ui.theme.AccentYellow
import com.copilotodriverpro.ui.theme.CardSecondary
import com.copilotodriverpro.ui.theme.DividerColor
import com.copilotodriverpro.ui.theme.OnAccent
import com.copilotodriverpro.ui.theme.SignalGreen
import com.copilotodriverpro.ui.theme.SignalRed
import com.copilotodriverpro.ui.theme.TextMuted
import com.copilotodriverpro.ui.theme.TextWhite
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

private enum class PlatformFilter(val label: String) {
    ALL("Todas"), UBER("Uber"), NINE_NINE("99")
}

private val HISTORY_WEEKDAYS = arrayOf("Dom", "Seg", "Ter", "Qua", "Qui", "Sex", "Sáb")
private val HISTORY_MONTHS = arrayOf(
    "Janeiro", "Fevereiro", "Março", "Abril", "Maio", "Junho",
    "Julho", "Agosto", "Setembro", "Outubro", "Novembro", "Dezembro"
)
private val PT_BR = Locale("pt", "BR")
private val historyBrl: NumberFormat by lazy { NumberFormat.getCurrencyInstance(PT_BR) }
private fun hMoney(v: Double): String = historyBrl.format(v)

private fun hCal(millis: Long): Calendar = Calendar.getInstance().apply { timeInMillis = millis }

private fun hDayLabel(millis: Long): String {
    val c = hCal(millis)
    return "${HISTORY_WEEKDAYS[c.get(Calendar.DAY_OF_WEEK) - 1]}, %02d/%02d".format(
        c.get(Calendar.DAY_OF_MONTH), c.get(Calendar.MONTH) + 1
    )
}

private fun hShortDate(millis: Long): String {
    val c = hCal(millis)
    return "%02d/%02d".format(c.get(Calendar.DAY_OF_MONTH), c.get(Calendar.MONTH) + 1)
}

private fun hTabLabel(p: SummaryPeriod) = when (p) {
    SummaryPeriod.DAY -> "Hoje"
    SummaryPeriod.WEEK -> "Esta Semana"
    SummaryPeriod.MONTH -> "Este Mês"
}

private fun hTitle(p: SummaryPeriod, start: Long, end: Long): String = when (p) {
    SummaryPeriod.DAY -> "Corridas do Dia (${hDayLabel(start)})"
    SummaryPeriod.WEEK -> "Corridas da Semana (${hShortDate(start)} – ${hShortDate(end - 1)})"
    SummaryPeriod.MONTH -> {
        val c = hCal(start)
        "Corridas do Mês (${HISTORY_MONTHS[c.get(Calendar.MONTH)]}/${c.get(Calendar.YEAR)})"
    }
}

private fun hDayHeader(dayStart: Long, now: Long): String {
    val today = EntrySummary.startOfDay(now)
    val yesterday = EntrySummary.startOfDay(today - 12L * 60 * 60 * 1000)
    return when (dayStart) {
        today -> "Hoje"
        yesterday -> "Ontem"
        else -> hDayLabel(dayStart)
    }
}

private fun km(v: Double) = "%.1f km".format(PT_BR, v)

/**
 * Histórico — cada corrida ACEITA no Uber/99 entra aqui sozinha (e vira ganho em Lançamentos).
 * Visual igual ao de Lançamentos: abas Hoje / Esta Semana / Este Mês, resumo e lista por dia.
 */
@Composable
fun HistoryScreen(nav: NavController) {
    val context = LocalContext.current
    val recorder = CopilotApp.instance.rideRecorder
    val rides by recorder.rides.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()

    var period by remember { mutableStateOf(SummaryPeriod.DAY) }
    var platform by remember { mutableStateOf(PlatformFilter.ALL) }
    var editing by remember { mutableStateOf<RideEntity?>(null) }
    var deleteTarget by remember { mutableStateOf<RideEntity?>(null) }

    val now = remember { System.currentTimeMillis() }
    val start = EntrySummary.periodStart(period, now)
    val end = EntrySummary.periodEnd(period, start)

    val periodRides = rides
        .filter { it.timestamp in start until end }
        .filter {
            platform == PlatformFilter.ALL ||
                (platform == PlatformFilter.UBER && it.platform == SupportedPlatform.UBER.name) ||
                (platform == PlatformFilter.NINE_NINE && it.platform == SupportedPlatform.NINE_NINE.name)
        }
        .sortedByDescending { it.timestamp }

    val earnings = periodRides.sumOf { it.fare }
    // Médias só com as corridas que têm distância/tempo lidos (sem inventar número).
    val withKm = periodRides.filter { (it.totalKm ?: 0.0) > 0.0 }
    val withMin = periodRides.filter { (it.totalMin ?: 0.0) > 0.0 }
    val kmTotal = withKm.sumOf { it.totalKm ?: 0.0 }
    val perKm = if (kmTotal > 0.0) withKm.sumOf { it.fare } / kmTotal else null
    val minTotal = withMin.sumOf { it.totalMin ?: 0.0 }
    val perHour = if (minTotal > 0.0) withMin.sumOf { it.fare } / (minTotal / 60.0) else null
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            "${periodRides.size} corrida" + (if (periodRides.size == 1) "" else "s") + " aceita" +
                (if (periodRides.size == 1) "" else "s"),
            style = MaterialTheme.typography.bodyMedium
        )

        FolderTabsCard(
            labels = SummaryPeriod.values().map { hTabLabel(it) },
            selected = period.ordinal,
            onSelect = { period = SummaryPeriod.values()[it] }
        ) {
            Text(hTitle(period, start, end), color = TextWhite, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "Ganhos: ${hMoney(earnings)}",
                        color = AccentYellow, fontSize = 24.sp, fontWeight = FontWeight.Bold
                    )
                    Row {
                        Text("Corridas:  ", color = TextWhite, fontSize = 15.sp)
                        Text("${periodRides.size}", color = SignalGreen, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    }
                    if (kmTotal > 0.0) {
                        Row {
                            Text("Rodados:  ", color = TextWhite, fontSize = 15.sp)
                            Text(km(kmTotal), color = TextWhite, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    if (perKm != null) {
                        Row {
                            Text("Média:  ", color = TextWhite, fontSize = 15.sp)
                            Text(
                                hMoney(perKm) + "/km" + (perHour?.let { " • ${hMoney(it)}/h" } ?: ""),
                                color = SignalGreen, fontSize = 15.sp, fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                    Text(
                        "Só corridas aceitas entram aqui e em Lançamentos. Despesas você lança à mão.",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                Image(
                    painter = painterResource(R.drawable.ninja_mascote),
                    contentDescription = null,
                    modifier = Modifier.width(110.dp),
                    contentScale = ContentScale.Fit
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PlatformFilter.values().forEach { f ->
                PlatformChip(f.label, selected = platform == f) { platform = f }
            }
        }

        if (periodRides.isEmpty()) {
            HistoryEmptyState(
                when (period) {
                    SummaryPeriod.DAY -> "hoje"
                    SummaryPeriod.WEEK -> "nesta semana"
                    SummaryPeriod.MONTH -> "neste mês"
                }
            )
        } else {
            periodRides.groupBy { EntrySummary.startOfDay(it.timestamp) }.forEach { (dayStart, list) ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        hDayHeader(dayStart, now),
                        color = TextWhite, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "${list.size} • + ${hMoney(list.sumOf { it.fare })}",
                        color = SignalGreen, fontSize = 13.sp, fontWeight = FontWeight.SemiBold
                    )
                }
                list.forEach { ride ->
                    RideRow(ride, timeFmt.format(Date(ride.timestamp))) { editing = ride }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }

    /* -------- Detalhes / edição -------- */
    editing?.let { ride ->
        var value by remember(ride.id) { mutableStateOf("%.2f".format(PT_BR, ride.fare)) }
        var error by remember(ride.id) { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Corrida ${AcceptedRideRecorder.platformLabel(ride.platform)}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ride.pickupText?.let { Text("Embarque: $it", style = MaterialTheme.typography.labelSmall) }
                    ride.destinationText?.let { Text("Destino: $it", style = MaterialTheme.typography.labelSmall) }
                    val facts = buildList {
                        ride.rideType?.let { add(it) }
                        ride.tripKm?.let { add("viagem ${km(it)}") }
                        ride.pickupKm?.let { add("embarque ${km(it)}") }
                        ride.durationMin?.let { add("${it.toInt()} min") }
                        ride.passengerRating?.let { add("nota %.2f".format(PT_BR, it)) }
                    }
                    if (facts.isNotEmpty()) {
                        Text(facts.joinToString(" • "), style = MaterialTheme.typography.labelSmall)
                    }
                    OutlinedTextField(
                        value = value,
                        onValueChange = { value = it },
                        label = { Text("Valor (R$)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                    )
                    error?.let { Text(it, color = SignalRed, style = MaterialTheme.typography.labelSmall) }
                    TextButton(onClick = { deleteTarget = ride }) {
                        Text("Excluir corrida", color = SignalRed)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val v = value.replace(".", "").replace(",", ".").toDoubleOrNull()
                    if (v == null || v <= 0.0) {
                        error = "Valor inválido."
                    } else {
                        scope.launch { recorder.updateFare(ride.id, v) }
                        editing = null
                        Toast.makeText(context, "Corrida e lançamento atualizados.", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("Salvar") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Fechar") } }
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Excluir corrida?") },
            text = {
                Text(
                    "A corrida de ${hMoney(target.fare)} e o ganho dela em Lançamentos serão removidos."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { recorder.delete(target.id) }
                    deleteTarget = null
                    editing = null
                    Toast.makeText(context, "Corrida excluída.", Toast.LENGTH_SHORT).show()
                }) { Text("Excluir", color = SignalRed) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancelar") } }
        )
    }
}

@Composable
private fun PlatformChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(if (selected) AccentYellow else CardSecondary)
            .border(1.dp, if (selected) AccentYellow else DividerColor, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp)
    ) {
        Text(
            label,
            color = if (selected) OnAccent else TextWhite,
            fontSize = 13.sp, fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun RideRow(ride: RideEntity, time: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    val isUber = ride.platform == SupportedPlatform.UBER.name
    val perKm = ride.totalKm?.takeIf { it > 0.0 }?.let { ride.fare / it }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(CardSecondary)
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(SignalGreen.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) {
            Text(if (isUber) "🚗" else "🚕", fontSize = 20.sp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                AcceptedRideRecorder.platformLabel(ride.platform) +
                    (ride.rideType?.let { " • $it" } ?: ""),
                color = TextWhite, fontWeight = FontWeight.SemiBold, fontSize = 15.sp
            )
            val details = buildList {
                add(time)
                ride.totalKm?.let { add(km(it)) }
                ride.totalMin?.let { add("${it.toInt()} min") }
            }.joinToString(" • ")
            Text(details, style = MaterialTheme.typography.labelSmall, maxLines = 1)
            val route = listOfNotNull(ride.pickupText, ride.destinationText).joinToString(" → ")
            if (route.isNotBlank()) {
                Text(route, style = MaterialTheme.typography.labelSmall, maxLines = 2)
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                "+ " + hMoney(ride.fare),
                color = SignalGreen, fontWeight = FontWeight.Bold, fontSize = 15.sp
            )
            perKm?.let {
                Text(hMoney(it) + "/km", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun HistoryEmptyState(periodPhrase: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Image(
            painter = painterResource(R.drawable.ninja_mascote),
            contentDescription = null,
            modifier = Modifier.width(150.dp),
            contentScale = ContentScale.Fit
        )
        Text(
            "Nenhuma corrida aceita $periodPhrase.",
            color = TextWhite, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center
        )
        Text(
            "Aceite uma corrida no Uber ou na 99 e ela aparece aqui sozinha.",
            style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center
        )
    }
}
