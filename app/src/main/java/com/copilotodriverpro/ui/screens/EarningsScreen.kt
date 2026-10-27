package com.copilotodriverpro.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MonetizationOn
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.TrackChanges
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.copilotodriverpro.CopilotApp
import com.copilotodriverpro.R
import com.copilotodriverpro.finance.EarningsStats
import com.copilotodriverpro.finance.EntrySummary
import com.copilotodriverpro.navigation.Routes
import com.copilotodriverpro.ui.theme.AccentYellow
import com.copilotodriverpro.ui.theme.CardNavy
import com.copilotodriverpro.ui.theme.DividerColor
import com.copilotodriverpro.ui.theme.OnAccent
import com.copilotodriverpro.ui.theme.SignalRed
import com.copilotodriverpro.ui.theme.TextMuted
import com.copilotodriverpro.ui.theme.TextWhite
import java.text.NumberFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private val EARN_BRL: NumberFormat by lazy { NumberFormat.getCurrencyInstance(Locale("pt", "BR")) }
private fun eMoney(v: Double): String = EARN_BRL.format(v)
private val E_WEEKDAYS = arrayOf("Dom", "Seg", "Ter", "Qua", "Qui", "Sex", "Sáb")

private enum class ChartRange(val label: String, val days: Int) {
    D7("Últimos 7 dias", 7), D14("Últimos 14 dias", 14), D30("Últimos 30 dias", 30)
}

private val CardShape = RoundedCornerShape(18.dp)

/**
 * Ganhos — substitui a antiga aba Turnos. Tudo vem dos registros reais do app:
 * lançamentos (ganhos), corridas aceitas (quantidade, média, nota) e turnos (tempo online).
 */
@Composable
fun EarningsScreen(nav: NavController) {
    val app = CopilotApp.instance
    val entries by app.entryRepository.all.collectAsState(initial = emptyList())
    val rides by app.rideRecorder.rides.collectAsState(initial = emptyList())
    val shifts by app.shiftRepository.all.collectAsState(initial = emptyList())
    val settings by app.settingsRepository.settings.collectAsState(initial = null)

    var range by remember { mutableStateOf(ChartRange.D7) }
    var showGoal by remember { mutableStateOf(false) }

    val now = System.currentTimeMillis()
    val today = EntrySummary.startOfDay(now)
    val yesterday = EntrySummary.startOfDay(today - 12L * 60 * 60 * 1000)

    val earnedToday = EarningsStats.earningsOn(entries, today)
    val ridesToday = EarningsStats.ridesOn(rides, today)
    val ridesYesterday = EarningsStats.ridesOn(rides, yesterday)
    val avgToday = EarningsStats.averagePerRide(ridesToday)
    val avgYesterday = EarningsStats.averagePerRide(ridesYesterday)
    val onlineToday = EarningsStats.onlineMinutes(shifts, today, now)
    val onlineYesterday = EarningsStats.onlineMinutes(shifts, yesterday, now)
    val ratingToday = EarningsStats.averageRating(ridesToday)
    val goal = (settings?.dailyGoal ?: 0f).toDouble()

    fun openEntries() {
        nav.navigate(Routes.ENTRIES) {
            popUpTo(nav.graph.startDestinationId) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        /* Cabeçalho */
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                painter = painterResource(R.drawable.ninja_go_logo),
                contentDescription = "Ninja Go",
                modifier = Modifier.size(56.dp).clip(RoundedCornerShape(14.dp))
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Ganhos", color = TextWhite, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Acompanhe seus ganhos, veja seu resumo do dia e planeje seu futuro!",
                    color = TextMuted, fontSize = 13.sp
                )
            }
            IconButton(onClick = { nav.navigate(Routes.SETTINGS) { launchSingleTop = true } }) {
                Icon(Icons.Filled.Settings, contentDescription = "Configurações", tint = TextWhite)
            }
        }

        /* Saldo do dia */
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(CardShape)
                .background(CardNavy)
                .border(1.dp, DividerColor, CardShape)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(64.dp).clip(CircleShape).background(AccentYellow),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.AccountBalanceWallet, null, tint = OnAccent, modifier = Modifier.size(34.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Seu saldo de hoje", color = TextWhite, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(eMoney(earnedToday), color = AccentYellow, fontSize = 28.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                Text("Total dos ganhos do dia", color = TextMuted, fontSize = 12.sp)
            }
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(22.dp))
                    .border(1.5.dp, AccentYellow, RoundedCornerShape(22.dp))
                    .clickable { openEntries() }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Ver extrato", color = AccentYellow, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Icon(Icons.Filled.ChevronRight, null, tint = AccentYellow, modifier = Modifier.size(18.dp))
            }
        }

        /* Resumo do dia */
        Row(
            modifier = Modifier.fillMaxWidth().height(138.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StatCard(
                icon = Icons.Filled.DirectionsCar,
                label = "Corridas realizadas",
                value = if (ridesToday.isEmpty()) "0" else "${ridesToday.size}",
                delta = if (ridesYesterday.isNotEmpty() || ridesToday.isNotEmpty())
                    deltaText((ridesToday.size - ridesYesterday.size).toDouble()) { "${abs(it).roundToInt()}" } else null,
                deltaUp = ridesToday.size >= ridesYesterday.size,
                foot = if (ridesToday.isEmpty()) "Nenhuma corrida aceita hoje" else "em relação a ontem",
                modifier = Modifier.weight(1f)
            )
            StatCard(
                icon = Icons.Filled.Schedule,
                label = "Tempo online",
                value = onlineToday?.let { EarningsStats.formatMinutes(it) } ?: "—",
                delta = if (onlineToday != null && onlineYesterday != null)
                    deltaText((onlineToday - onlineYesterday).toDouble()) { EarningsStats.formatMinutes(abs(it).toLong()) } else null,
                deltaUp = (onlineToday ?: 0L) >= (onlineYesterday ?: 0L),
                foot = if (onlineToday == null) "Sem turno registrado hoje" else if (onlineYesterday != null) "em relação a ontem" else "pelos seus turnos",
                modifier = Modifier.weight(1f)
            )
            StatCard(
                icon = Icons.Filled.MonetizationOn,
                label = "Ganhos por corrida",
                value = avgToday?.let { eMoney(it) } ?: "—",
                delta = if (avgToday != null && avgYesterday != null)
                    deltaText(avgToday - avgYesterday) { eMoney(abs(it)) } else null,
                deltaUp = (avgToday ?: 0.0) >= (avgYesterday ?: 0.0),
                foot = if (avgToday == null) "Sem corridas hoje" else if (avgYesterday != null) "em relação a ontem" else "média de hoje",
                modifier = Modifier.weight(1f)
            )
            StatCard(
                icon = Icons.Filled.StarBorder,
                label = "Avaliação média",
                value = ratingToday?.let { "%.2f".format(Locale("pt", "BR"), it) } ?: "—",
                delta = null,
                deltaUp = true,
                foot = if (ratingToday == null) "Sem notas lidas hoje" else "nota dos passageiros",
                modifier = Modifier.weight(1f)
            )
        }

        /* Evolução dos ganhos */
        val series = EarningsStats.dailySeries(entries, now, range.days)
        val maxValue = series.maxOfOrNull { it.total } ?: 0.0
        var menuOpen by remember { mutableStateOf(false) }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(CardShape)
                .background(CardNavy)
                .border(1.dp, DividerColor, CardShape)
                .padding(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.BarChart, null, tint = AccentYellow, modifier = Modifier.size(34.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Evolução dos seus ganhos", color = TextWhite, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text("Veja como está seu desempenho.", color = TextMuted, fontSize = 12.sp)
                }
                Box {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .border(1.dp, DividerColor, RoundedCornerShape(20.dp))
                            .clickable { menuOpen = true }
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.CalendarMonth, null, tint = TextMuted, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(5.dp))
                        Text(range.label, color = TextWhite, fontSize = 12.sp)
                        Icon(Icons.Filled.KeyboardArrowDown, null, tint = TextWhite, modifier = Modifier.size(16.dp))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        ChartRange.values().forEach { r ->
                            DropdownMenuItem(
                                text = { Text(r.label) },
                                onClick = { range = r; menuOpen = false }
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            if (maxValue <= 0.0) {
                Text(
                    "Ainda não há ganhos registrados em ${range.label.lowercase()}. " +
                        "As corridas aceitas e os ganhos que você lançar aparecem aqui.",
                    color = TextMuted, fontSize = 13.sp,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    textAlign = TextAlign.Center
                )
            } else {
                BarChartRow(series, maxValue, today, showAllValues = range.days <= 7)
            }
        }

        /* Metas */
        GoalCard(
            goal = goal,
            earned = earnedToday,
            onEdit = { showGoal = true }
        )

        Spacer(Modifier.height(8.dp))
    }

    if (showGoal) {
        var text by remember {
            mutableStateOf(if (goal > 0.0) "%.2f".format(Locale("pt", "BR"), goal) else "")
        }
        var error by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { showGoal = false },
            title = { Text("Meta diária de ganhos") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Quanto você quer ganhar por dia? O progresso usa o total de ganhos de hoje. " +
                            "Deixe vazio para remover a meta.",
                        style = MaterialTheme.typography.labelSmall
                    )
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        label = { Text("Meta por dia (R$)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                    )
                    error?.let { Text(it, color = SignalRed, style = MaterialTheme.typography.labelSmall) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val raw = text.trim()
                    val v = if (raw.isEmpty()) 0.0 else raw.replace(".", "").replace(",", ".").toDoubleOrNull()
                    if (v == null || v < 0.0) {
                        error = "Valor inválido."
                    } else {
                        app.updateSetting { it.copy(dailyGoal = v.toFloat()) }
                        showGoal = false
                    }
                }) { Text("Salvar") }
            },
            dismissButton = { TextButton(onClick = { showGoal = false }) { Text("Cancelar") } }
        )
    }
}

/** "↑ +3" / "↓ −2" em relação a ontem; nulo quando não mudou. */
private fun deltaText(diff: Double, fmt: (Double) -> String): String? = when {
    diff > 0.0 -> "↑ +" + fmt(diff)
    diff < 0.0 -> "↓ −" + fmt(diff)
    else -> "= igual"
}

@Composable
private fun StatCard(
    icon: ImageVector,
    label: String,
    value: String,
    delta: String?,
    deltaUp: Boolean,
    foot: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(16.dp))
            .background(CardNavy)
            .border(1.dp, DividerColor, RoundedCornerShape(16.dp))
            .padding(horizontal = 4.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Icon(icon, null, tint = AccentYellow, modifier = Modifier.size(26.dp))
        Text(label, color = TextWhite, fontSize = 11.sp, textAlign = TextAlign.Center, minLines = 2, maxLines = 2)
        Text(
            value, color = TextWhite, fontSize = 15.sp, fontWeight = FontWeight.Bold,
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center
        )
        if (delta != null) {
            Text(
                delta, color = if (deltaUp) AccentYellow else SignalRed,
                fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1
            )
        }
        Text(foot, color = TextMuted, fontSize = 9.sp, textAlign = TextAlign.Center, maxLines = 2)
    }
}

@Composable
private fun BarChartRow(
    series: List<com.copilotodriverpro.finance.DayEarning>,
    maxValue: Double,
    today: Long,
    showAllValues: Boolean
) {
    val barArea = 130.dp
    val cal = Calendar.getInstance()
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().height(barArea + 18.dp),
            horizontalArrangement = Arrangement.spacedBy(if (series.size > 14) 2.dp else 6.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            series.forEach { d ->
                val isToday = d.dayStart == today
                val frac = (d.total / maxValue).toFloat().coerceIn(0f, 1f)
                val showValue = showAllValues || isToday || d.total == maxValue
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Bottom
                ) {
                    if (showValue && d.total > 0.0) {
                        Text(
                            if (showAllValues) "R$ %,.2f".format(Locale("pt", "BR"), d.total)
                            else "%,.0f".format(Locale("pt", "BR"), d.total),
                            color = if (isToday) AccentYellow else TextWhite,
                            fontSize = if (showAllValues) 8.sp else 9.sp,
                            maxLines = 1, softWrap = false,
                            fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal
                        )
                        Spacer(Modifier.height(3.dp))
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(if (d.total > 0.0) (barArea * frac).coerceAtLeast(4.dp) else 2.dp)
                            .clip(RoundedCornerShape(topStart = 5.dp, topEnd = 5.dp))
                            .background(
                                when {
                                    d.total <= 0.0 -> DividerColor
                                    isToday -> AccentYellow
                                    else -> AccentYellow.copy(alpha = 0.5f)
                                }
                            )
                    )
                }
            }
        }
        Spacer(Modifier.height(1.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(DividerColor))
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(if (series.size > 14) 2.dp else 6.dp)
        ) {
            series.forEachIndexed { i, d ->
                cal.timeInMillis = d.dayStart
                val isToday = d.dayStart == today
                val dayNum = "%02d/%02d".format(cal.get(Calendar.DAY_OF_MONTH), cal.get(Calendar.MONTH) + 1)
                val show = showAllValues || i % 5 == 0 || isToday
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (show) {
                        if (showAllValues) {
                            Text(
                                if (isToday) "Hoje" else E_WEEKDAYS[cal.get(Calendar.DAY_OF_WEEK) - 1],
                                color = if (isToday) AccentYellow else TextMuted, fontSize = 11.sp,
                                fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal, maxLines = 1
                            )
                        }
                        Text(
                            if (showAllValues) dayNum else "${cal.get(Calendar.DAY_OF_MONTH)}",
                            color = if (isToday) AccentYellow else TextMuted, fontSize = if (showAllValues) 10.sp else 9.sp,
                            maxLines = 1, softWrap = false
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GoalCard(goal: Double, earned: Double, onEdit: () -> Unit) {
    val hasGoal = goal > 0.0
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(CardNavy)
            .border(1.dp, DividerColor, CardShape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.TrackChanges, null, tint = AccentYellow, modifier = Modifier.size(40.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (hasGoal) "Meta diária" else "Defina suas metas",
                    color = TextWhite, fontSize = 16.sp, fontWeight = FontWeight.Bold
                )
                Text(
                    if (hasGoal) "Meta de ${eMoney(goal)} por dia"
                    else "Quer alcançar um valor específico? Configure sua meta de ganhos e acompanhe sua evolução aqui.",
                    color = TextMuted, fontSize = 12.sp
                )
            }
            Spacer(Modifier.width(8.dp))
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(AccentYellow)
                    .clickable(onClick = onEdit)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(if (hasGoal) "Editar" else "Definir meta", color = OnAccent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Icon(Icons.Filled.ChevronRight, null, tint = OnAccent, modifier = Modifier.size(18.dp))
            }
        }
        if (hasGoal) {
            val progress = (EarningsStats.goalProgress(earned, goal) ?: 0.0).toFloat()
            val remaining = EarningsStats.remaining(earned, goal)
            Box(
                Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(Color(0xFF2A2A2A))
            ) {
                Box(
                    Modifier.fillMaxWidth(progress).fillMaxHeight().clip(RoundedCornerShape(5.dp)).background(AccentYellow)
                )
            }
            Row {
                Text(
                    "${eMoney(earned)} de ${eMoney(goal)} (${(progress * 100).roundToInt()}%)",
                    color = TextWhite, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    if (remaining > 0.0) "Faltam ${eMoney(remaining)}" else "Meta batida! 🎉",
                    color = AccentYellow, fontSize = 13.sp, fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
