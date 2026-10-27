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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.copilotodriverpro.CopilotApp
import com.copilotodriverpro.R
import com.copilotodriverpro.database.EntryEntity
import com.copilotodriverpro.database.EntryType
import com.copilotodriverpro.finance.EntrySummary
import com.copilotodriverpro.finance.EntryViewModel
import com.copilotodriverpro.finance.SummaryPeriod
import com.copilotodriverpro.navigation.Routes
import com.copilotodriverpro.ui.theme.AccentYellow
import com.copilotodriverpro.ui.theme.CardSecondary
import com.copilotodriverpro.ui.theme.DividerColor
import com.copilotodriverpro.ui.theme.OnAccent
import com.copilotodriverpro.ui.theme.SignalGreen
import com.copilotodriverpro.ui.theme.SignalRed
import com.copilotodriverpro.ui.theme.SignalYellow
import com.copilotodriverpro.ui.theme.TextMuted
import com.copilotodriverpro.ui.theme.TextWhite
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/* ------------------------------ Identidade visual ------------------------------ */

private val TAB_HEIGHT = 46.dp
private val SummaryFill = Color(0xFF14181A)   // fundo do resumo
private val TabIdleFill = Color(0xFF0E1A15)   // abas inativas (verde bem escuro)
private val TabIdleBorder = Color(0xFF1F3A2E)

private enum class EntryTypeFilter(val label: String) { ALL("Tudo"), GANHO("Ganhos"), DESPESA("Despesas") }

private fun tabLabel(p: SummaryPeriod) = when (p) {
    SummaryPeriod.DAY -> "Hoje"
    SummaryPeriod.WEEK -> "Esta Semana"
    SummaryPeriod.MONTH -> "Este Mês"
}

private val WEEKDAYS = arrayOf("Dom", "Seg", "Ter", "Qua", "Qui", "Sex", "Sáb")
private val MONTHS = arrayOf(
    "Janeiro", "Fevereiro", "Março", "Abril", "Maio", "Junho",
    "Julho", "Agosto", "Setembro", "Outubro", "Novembro", "Dezembro"
)

private fun cal(millis: Long): Calendar = Calendar.getInstance().apply { timeInMillis = millis }

private fun dayLabel(millis: Long): String {
    val c = cal(millis)
    return "${WEEKDAYS[c.get(Calendar.DAY_OF_WEEK) - 1]}, %02d/%02d".format(
        c.get(Calendar.DAY_OF_MONTH), c.get(Calendar.MONTH) + 1
    )
}

private fun shortDate(millis: Long): String {
    val c = cal(millis)
    return "%02d/%02d".format(c.get(Calendar.DAY_OF_MONTH), c.get(Calendar.MONTH) + 1)
}

private fun summaryTitle(p: SummaryPeriod, start: Long, end: Long): String = when (p) {
    SummaryPeriod.DAY -> "Resumo do Dia (${dayLabel(start)})"
    SummaryPeriod.WEEK -> "Resumo da Semana (${shortDate(start)} – ${shortDate(end - 1)})"
    SummaryPeriod.MONTH -> {
        val c = cal(start)
        "Resumo do Mês (${MONTHS[c.get(Calendar.MONTH)]}/${c.get(Calendar.YEAR)})"
    }
}

private fun dayHeader(dayStart: Long, now: Long): String {
    val today = EntrySummary.startOfDay(now)
    val yesterday = EntrySummary.startOfDay(today - 12L * 60 * 60 * 1000)
    return when (dayStart) {
        today -> "Hoje"
        yesterday -> "Ontem"
        else -> dayLabel(dayStart)
    }
}

private val brl: NumberFormat by lazy { NumberFormat.getCurrencyInstance(Locale("pt", "BR")) }
private fun money(v: Double): String = brl.format(v)

private fun emojiFor(e: EntryEntity): String {
    val c = e.category.lowercase()
    return when {
        c.contains("combust") -> "⛽"
        c.contains("ped") -> "🛣️"
        c.contains("estacion") -> "🅿️"
        c.contains("manut") -> "🔧"
        c.contains("aliment") -> "🍔"
        c.startsWith("corrida") -> "🚗"
        c.contains("gorjeta") -> "💝"
        c.contains("bônus") || c.contains("bonus") -> "🎁"
        e.type == EntryType.GANHO -> "💰"
        else -> "🧾"
    }
}

/**
 * Lançamentos — resumo por período (Hoje / Semana / Mês) com líquido, receitas e custos
 * (incluindo custos fixos diários), filtro por tipo e histórico agrupado por dia,
 * com edição e exclusão (com confirmação).
 */
@Composable
fun EntriesScreen(nav: NavController) {
    val context = LocalContext.current
    val vm: EntryViewModel = viewModel(factory = EntryViewModel.factory())
    val entries by vm.entries.collectAsState()
    val settings by CopilotApp.instance.settingsRepository.settings.collectAsState(initial = null)
    val scope = rememberCoroutineScope()

    var period by remember { mutableStateOf(SummaryPeriod.DAY) }
    var typeFilter by remember { mutableStateOf(EntryTypeFilter.ALL) }
    var editing by remember { mutableStateOf<EntryEntity?>(null) }
    var deleteTarget by remember { mutableStateOf<EntryEntity?>(null) }
    var showFixed by remember { mutableStateOf(false) }

    val now = remember { System.currentTimeMillis() }
    val start = EntrySummary.periodStart(period, now)
    val end = EntrySummary.periodEnd(period, start)
    val days = EntrySummary.daysElapsed(start, now)

    val periodEntries = remember(entries, period) {
        entries.filter { it.timestamp in start until end }.sortedByDescending { it.timestamp }
    }
    val fixedPerDay = (settings?.fixedCostPerDay ?: 0f).toDouble()
    val expenseEntries = periodEntries.filter { it.type == EntryType.DESPESA }
    val totals = EntrySummary.totals(
        earnings = periodEntries.filter { it.type == EntryType.GANHO }.sumOf { it.amount },
        expenses = expenseEntries.sumOf { it.amount },
        hasExpenseEntries = expenseEntries.isNotEmpty(),
        fixedCostPerDay = fixedPerDay,
        daysElapsed = days
    )

    val shown = periodEntries.filter {
        typeFilter == EntryTypeFilter.ALL ||
            (typeFilter == EntryTypeFilter.GANHO && it.type == EntryType.GANHO) ||
            (typeFilter == EntryTypeFilter.DESPESA && it.type == EntryType.DESPESA)
    }
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Linha de cima: contagem + custos fixos
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "${periodEntries.size} lançamento" + (if (periodEntries.size == 1) "" else "s"),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                FixedCostChip(fixedPerDay) { showFixed = true }
            }

            SummaryCard(
                period = period,
                onPeriod = { period = it },
                title = summaryTitle(period, start, end),
                earnings = totals.earnings,
                costs = totals.costs,
                net = totals.net,
                fixedTotal = totals.fixedTotal,
                fixedPerDay = fixedPerDay,
                days = days
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EntryTypeFilter.values().forEach { f ->
                    TypeChip(f.label, selected = typeFilter == f) { typeFilter = f }
                }
            }

            if (shown.isEmpty()) {
                EmptyState(
                    when (period) {
                        SummaryPeriod.DAY -> "hoje"
                        SummaryPeriod.WEEK -> "nesta semana"
                        SummaryPeriod.MONTH -> "neste mês"
                    },
                    typeFilter.label.takeIf { typeFilter != EntryTypeFilter.ALL }
                )
            } else {
                shown.groupBy { EntrySummary.startOfDay(it.timestamp) }.forEach { (dayStart, list) ->
                    val dayNet = list.sumOf { if (it.type == EntryType.GANHO) it.amount else -it.amount }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            dayHeader(dayStart, now),
                            color = TextWhite, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            (if (dayNet >= 0) "+ " else "− ") + money(abs(dayNet)),
                            color = if (dayNet >= 0) SignalGreen else SignalRed,
                            fontSize = 13.sp, fontWeight = FontWeight.SemiBold
                        )
                    }
                    list.forEach { entry ->
                        EntryRow(entry, timeFmt.format(Date(entry.timestamp))) { editing = entry }
                    }
                }
            }

            Spacer(Modifier.height(88.dp)) // espaço para o botão + não cobrir o último item
        }

        FloatingActionButton(
            onClick = { nav.navigate(Routes.LAUNCH) },
            containerColor = CardSecondary,
            contentColor = AccentYellow,
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
                .border(1.5.dp, AccentYellow, RoundedCornerShape(18.dp))
        ) {
            Icon(Icons.Filled.Add, contentDescription = "Novo lançamento")
        }
    }

    /* -------- Custos fixos diários -------- */
    if (showFixed) {
        var text by remember {
            mutableStateOf(if (fixedPerDay > 0.0) "%.2f".format(fixedPerDay).replace(".", ",") else "")
        }
        var fixedError by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { showFixed = false },
            title = { Text("Custos fixos por dia") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Informe quanto você gasta por dia com custos fixos (aluguel do carro, seguro, " +
                            "parcela, internet...). Esse valor entra como custo no resumo de Hoje, " +
                            "Semana e Mês, multiplicado pelos dias já decorridos. Deixe vazio para não usar.",
                        style = MaterialTheme.typography.labelSmall
                    )
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        label = { Text("Valor por dia (R$)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                    )
                    fixedError?.let { Text(it, color = SignalRed, style = MaterialTheme.typography.labelSmall) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val raw = text.trim()
                    val v = if (raw.isEmpty()) 0.0 else raw.replace(".", "").replace(",", ".").toDoubleOrNull()
                    if (v == null || v < 0.0) {
                        fixedError = "Valor inválido."
                    } else {
                        CopilotApp.instance.updateSetting { it.copy(fixedCostPerDay = v.toFloat()) }
                        showFixed = false
                        Toast.makeText(context, "Custos fixos salvos.", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("Salvar") }
            },
            dismissButton = { TextButton(onClick = { showFixed = false }) { Text("Cancelar") } }
        )
    }

    /* -------- Diálogo de edição -------- */
    editing?.let { entry ->
        var value by remember(entry.id) { mutableStateOf("%.2f".format(entry.amount).replace(".", ",")) }
        var note by remember(entry.id) { mutableStateOf(entry.note) }
        var error by remember { mutableStateOf<String?>(null) }

        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Editar lançamento") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value, { value = it }, label = { Text("Valor (R$)") }, singleLine = true)
                    OutlinedTextField(note, { note = it }, label = { Text("Observações") })
                    error?.let { Text(it, color = SignalRed, style = MaterialTheme.typography.labelSmall) }
                    TextButton(onClick = { deleteTarget = entry }) {
                        Text("Excluir lançamento", color = SignalRed)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val v = value.replace(".", "").replace(",", ".").toDoubleOrNull()
                    if (v == null || v <= 0.0) {
                        error = "Valor inválido."
                    } else {
                        scope.launch { vm.update(entry.copy(amount = v, note = note.trim())) }
                        editing = null
                        Toast.makeText(context, "Lançamento atualizado.", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("Salvar") }
            },
            dismissButton = {
                TextButton(onClick = { editing = null }) { Text("Cancelar") }
            }
        )
    }

    /* -------- Confirmação de exclusão -------- */
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Excluir lançamento?") },
            text = { Text("${target.category} — ${money(target.amount)} será removido permanentemente.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { vm.delete(target) }
                    deleteTarget = null
                    editing = null
                    Toast.makeText(context, "Lançamento excluído.", Toast.LENGTH_SHORT).show()
                }) { Text("Excluir", color = SignalRed) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancelar") }
            }
        )
    }
}

/* ------------------------------ Componentes ------------------------------ */

@Composable
private fun FixedCostChip(fixedPerDay: Double, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .clip(shape)
            .border(1.dp, AccentYellow.copy(alpha = 0.6f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.Settings, contentDescription = null, tint = AccentYellow, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Column {
            Text("Custos fixos", color = TextWhite, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(
                if (fixedPerDay > 0.0) "${money(fixedPerDay)}/dia" else "Toque para definir",
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

/**
 * Abas em formato de pasta (Hoje / Esta Semana / Este Mês) emendadas ao card de resumo:
 * o contorno dourado sobe pela aba ativa e continua pelo card, sem linha entre os dois.
 */
@Composable
private fun SummaryCard(
    period: SummaryPeriod,
    onPeriod: (SummaryPeriod) -> Unit,
    title: String,
    earnings: Double,
    costs: Double,
    net: Double?,
    fixedTotal: Double,
    fixedPerDay: Double,
    days: Int
) {
    val tabs = SummaryPeriod.values()
    val active = period.ordinal

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                val th = TAB_HEIGHT.toPx()
                val r = 14.dp.toPx()
                val sw = 1.5.dp.toPx()
                val half = sw / 2f
                val w = size.width
                val h = size.height
                val tw = w / tabs.size

                // abas inativas (um pouco mais baixas, atrás do card)
                tabs.forEachIndexed { i, _ ->
                    if (i != active) {
                        val topLeft = Offset(i * tw + 3.dp.toPx(), 5.dp.toPx())
                        val sz = Size(tw - 6.dp.toPx(), th + r)
                        drawRoundRect(TabIdleFill, topLeft, sz, CornerRadius(r, r))
                        drawRoundRect(TabIdleBorder, topLeft, sz, CornerRadius(r, r), style = Stroke(1.dp.toPx()))
                    }
                }

                // contorno único: aba ativa + card
                val left = half
                val right = w - half
                val bottom = h - half
                val yTab = half
                val yCard = th
                val x0 = active * tw + half
                val x1 = (active + 1) * tw - half

                val path = Path().apply {
                    moveTo(x0, yCard)
                    lineTo(x0, yTab + r)
                    arcTo(Rect(x0, yTab, x0 + 2 * r, yTab + 2 * r), 180f, 90f, false)
                    lineTo(x1 - r, yTab)
                    arcTo(Rect(x1 - 2 * r, yTab, x1, yTab + 2 * r), 270f, 90f, false)
                    lineTo(x1, yCard)
                    if (active == tabs.lastIndex) {
                        lineTo(right, bottom - r)
                    } else {
                        lineTo(right - r, yCard)
                        arcTo(Rect(right - 2 * r, yCard, right, yCard + 2 * r), 270f, 90f, false)
                        lineTo(right, bottom - r)
                    }
                    arcTo(Rect(right - 2 * r, bottom - 2 * r, right, bottom), 0f, 90f, false)
                    lineTo(left + r, bottom)
                    arcTo(Rect(left, bottom - 2 * r, left + 2 * r, bottom), 90f, 90f, false)
                    if (active == 0) {
                        lineTo(left, yCard)
                    } else {
                        lineTo(left, yCard + r)
                        arcTo(Rect(left, yCard, left + 2 * r, yCard + 2 * r), 180f, 90f, false)
                        lineTo(x0, yCard)
                    }
                    close()
                }
                drawPath(path, SummaryFill)
                drawPath(path, AccentYellow, style = Stroke(width = sw, join = StrokeJoin.Round))
            }
    ) {
        Row(modifier = Modifier.fillMaxWidth().height(TAB_HEIGHT)) {
            tabs.forEach { t ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable { onPeriod(t) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        tabLabel(t),
                        color = if (t == period) AccentYellow else TextMuted,
                        fontWeight = if (t == period) FontWeight.Bold else FontWeight.Medium,
                        fontSize = 15.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(title, color = TextWhite, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (net != null) {
                        Text(
                            "Líquido: ${money(net)}",
                            color = if (net >= 0) AccentYellow else SignalRed,
                            fontSize = 24.sp, fontWeight = FontWeight.Bold
                        )
                        Row {
                            Text("Receitas:  ", color = TextWhite, fontSize = 15.sp)
                            Text("+ ${money(earnings)}", color = SignalGreen, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Row {
                            Text("Custos:  ", color = TextWhite, fontSize = 15.sp)
                            Text("− ${money(costs)}", color = SignalRed, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        }
                    } else {
                        Text(
                            "Ganhos: ${money(earnings)}",
                            color = AccentYellow, fontSize = 24.sp, fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Sem despesas lançadas: o líquido só é calculado quando há custos.",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    if (fixedTotal > 0.0) {
                        Text(
                            "Inclui ${money(fixedTotal)} em custos fixos (${money(fixedPerDay)} × $days " +
                                (if (days == 1) "dia" else "dias") + ").",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
                Image(
                    painter = painterResource(R.drawable.ninja_mascote),
                    contentDescription = null,
                    modifier = Modifier.width(120.dp),
                    contentScale = ContentScale.Fit
                )
            }

            if (net != null && earnings > 0.0 && costs > 0.0) {
                Spacer(Modifier.height(12.dp))
                val share = (costs / earnings).coerceIn(0.0, 1.0).toFloat()
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color(0xFF2A2A2A))
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(share)
                            .fillMaxHeight()
                            .background(
                                when {
                                    share > 0.6f -> SignalRed
                                    share > 0.35f -> SignalYellow
                                    else -> SignalGreen
                                }
                            )
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "Custos = ${(costs / earnings * 100).roundToInt()}% da receita",
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

@Composable
private fun TypeChip(label: String, selected: Boolean, onClick: () -> Unit) {
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
private fun EntryRow(entry: EntryEntity, time: String, onClick: () -> Unit) {
    val isGain = entry.type == EntryType.GANHO
    val accent = if (isGain) SignalGreen else SignalRed
    val shape = RoundedCornerShape(14.dp)
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
                .background(accent.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) {
            Text(emojiFor(entry), fontSize = 20.sp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(entry.category, color = TextWhite, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            val details = buildList {
                add(time)
                if (entry.source == com.copilotodriverpro.database.ENTRY_SOURCE_AUTO) add("Automático")
                if (entry.shiftId != null) add("Turno #${entry.shiftId}")
                if (entry.note.isNotBlank()) add(entry.note)
            }.joinToString(" • ")
            Text(details, style = MaterialTheme.typography.labelSmall, maxLines = 2)
        }
        Spacer(Modifier.width(8.dp))
        Text(
            (if (isGain) "+ " else "− ") + money(entry.amount),
            color = accent, fontWeight = FontWeight.Bold, fontSize = 15.sp
        )
    }
}

@Composable
private fun EmptyState(periodPhrase: String, filterLabel: String?) {
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
            "Nenhum lançamento $periodPhrase" + (filterLabel?.let { " em \"$it\"" } ?: "") + ".",
            color = TextWhite, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center
        )
        Text(
            "Toque no + para registrar ganhos e despesas.",
            style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center
        )
    }
}
