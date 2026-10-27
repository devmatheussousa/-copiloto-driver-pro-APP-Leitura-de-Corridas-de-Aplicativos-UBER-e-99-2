package com.copilotodriverpro.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.copilotodriverpro.shifts.ShiftSummary
import com.copilotodriverpro.shifts.ShiftViewModel
import com.copilotodriverpro.ui.components.CopilotoCard
import com.copilotodriverpro.ui.theme.AccentYellow
import com.copilotodriverpro.ui.theme.OnAccent
import com.copilotodriverpro.ui.theme.CardSecondary
import com.copilotodriverpro.ui.theme.DeepNavy
import com.copilotodriverpro.ui.theme.SignalGreen
import com.copilotodriverpro.ui.theme.SignalRed
import com.copilotodriverpro.ui.theme.TextMuted
import com.copilotodriverpro.ui.theme.TextWhite
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tela Turnos — lista completa com resumo de ganhos/despesas por turno (Room).
 */
@Composable
fun ShiftsScreen(nav: NavController) {
    val context = LocalContext.current
    val vm: ShiftViewModel = viewModel(factory = ShiftViewModel.factory())
    val active by vm.activeShift.collectAsState()
    val shifts by vm.shifts.collectAsState()
    var expandedId by remember { mutableStateOf<Long?>(null) }
    var deleteTarget by remember { mutableStateOf<ShiftSummary?>(null) }

    val fmtDate = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
    val fmtTime = SimpleDateFormat("HH:mm", Locale.getDefault())

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        /* Turno ativo / botão */
        CopilotoCard(if (active != null) "Turno em andamento" else "Nenhum turno ativo") {
            if (active != null) {
                Text(
                    "Início: ${fmtDate.format(Date(active!!.startMillis))} às ${fmtTime.format(Date(active!!.startMillis))}",
                    color = SignalGreen, fontWeight = FontWeight.SemiBold
                )
            } else {
                Text("Toque no botão para iniciar seu turno de hoje.", style = MaterialTheme.typography.bodyMedium)
            }
            Button(
                onClick = {
                    if (active == null) vm.start() else vm.end(null)
                    Toast.makeText(
                        context,
                        if (active == null) "Turno iniciado." else "Turno encerrado.",
                        Toast.LENGTH_SHORT
                    ).show()
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (active == null) AccentYellow else SignalRed,
                    contentColor = if (active == null) OnAccent else TextWhite
                )
            ) {
                Text(if (active == null) "Iniciar Turno" else "Encerrar Turno")
            }
        }

        Text("Histórico de turnos", style = MaterialTheme.typography.titleMedium)

        if (shifts.isEmpty()) {
            CopilotoCard("Sem turnos") {
                Text(
                    "Nenhum turno registrado ainda. Inicie o primeiro acima — ou " +
                        "ative o Turno automático para ele começar sozinho na primeira corrida.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        shifts.forEach { summary ->
            val shift = summary.shift
            val expanded = expandedId == shift.id
            Card(
                modifier = Modifier.fillMaxWidth().clickable {
                    expandedId = if (expanded) null else shift.id
                },
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = CardSecondary)
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            fmtDate.format(Date(shift.startMillis)),
                            fontWeight = FontWeight.Bold, color = TextWhite,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            if (shift.endMillis == null) "ATIVO" else "ENCERRADO",
                            color = if (shift.endMillis == null) SignalGreen else TextMuted,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Icon(
                            if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                            contentDescription = null, tint = TextMuted
                        )
                    }
                    Text(
                        "Início ${fmtTime.format(Date(shift.startMillis))}" +
                            (shift.endMillis?.let { " — Fim ${fmtTime.format(Date(it))}" } ?: " — em andamento"),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("⏱ ${formatDuration(summary.durationMillis)}", color = TextMuted, fontSize = 13.sp)
                        Text("Ganhos: ${money(summary.earnings)}", color = SignalGreen, fontSize = 13.sp)
                        Text(
                            if (summary.hasExpenseData) "Despesas: ${money(summary.expenses)}"
                            else "Despesas: não informadas",
                            color = if (summary.hasExpenseData) SignalRed else TextMuted,
                            fontSize = 13.sp
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        shift.distanceKm?.let {
                            Text("%.1f km".format(it), color = TextMuted, fontSize = 13.sp)
                        }
                        Text(
                            if (summary.result != null) "Resultado: ${money(summary.result!!)}"
                            else "Resultado: não calculado (sem despesas)",
                            fontWeight = FontWeight.Bold,
                            color = if (summary.result != null) TextWhite else SignalRed,
                            fontSize = 13.sp
                        )
                    }

                    if (expanded) {
                        if (shift.note.isNotBlank()) {
                            Text("Observação: ${shift.note}", style = MaterialTheme.typography.labelSmall)
                        }
                        OutlinedButton(onClick = { deleteTarget = summary }) {
                            Text("Excluir turno", color = SignalRed)
                        }
                    }
                }
            }
        }
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Excluir turno?") },
            text = { Text("O turno de ${fmtDate.format(Date(target.shift.startMillis))} será removido. Os lançamentos vinculados permanecem.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.delete(target.shift)
                    deleteTarget = null
                    Toast.makeText(context, "Turno excluído.", Toast.LENGTH_SHORT).show()
                }) { Text("Excluir", color = SignalRed) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancelar") }
            }
        )
    }
}

private fun formatDuration(millis: Long): String {
    val totalMin = millis / 60_000L
    val h = totalMin / 60
    val m = totalMin % 60
    return if (h > 0) "${h}h ${m}min" else "${m}min"
}

private fun money(v: Double): String = "R$ %.2f".format(v)
