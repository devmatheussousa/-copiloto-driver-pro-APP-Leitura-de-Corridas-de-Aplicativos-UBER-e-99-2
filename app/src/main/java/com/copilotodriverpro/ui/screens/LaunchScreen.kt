package com.copilotodriverpro.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.copilotodriverpro.CopilotApp
import com.copilotodriverpro.database.EntryType
import com.copilotodriverpro.finance.EntryViewModel
import com.copilotodriverpro.ui.components.CopilotoCard
import com.copilotodriverpro.ui.theme.AccentYellow
import com.copilotodriverpro.ui.theme.SignalGreen
import com.copilotodriverpro.ui.theme.SignalRed
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

private val GANHO_CATEGORIES = listOf("Ganhos de corrida", "Bônus", "Gorjetas", "Outros ganhos")
private val DESPESA_CATEGORIES = listOf("Combustível", "Pedágio", "Estacionamento", "Manutenção", "Alimentação", "Outras despesas")

/**
 * Lançar — registro manual de ganhos e despesas (Room).
 * Data/hora editável em formato brasileiro, categoria, valor, observações
 * e vínculo opcional com o turno ativo.
 */
@Composable
fun LaunchScreen(nav: NavController) {
    val context = LocalContext.current
    val app = context.applicationContext as CopilotApp
    val vm: EntryViewModel = viewModel(factory = EntryViewModel.factory())
    val shiftVm: com.copilotodriverpro.shifts.ShiftViewModel = viewModel(factory = com.copilotodriverpro.shifts.ShiftViewModel.factory())
    val activeShift by shiftVm.activeShift.collectAsState()
    val scope = rememberCoroutineScope()

    var type by remember { mutableStateOf(EntryType.GANHO) }
    var category by remember { mutableStateOf(GANHO_CATEGORIES[0]) }
    var amount by remember { mutableStateOf("") }
    var dateText by remember {
        mutableStateOf(SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date()))
    }
    var note by remember { mutableStateOf("") }
    var linkToShift by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    val dateParser = remember {
        SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).apply { isLenient = false }
    }
    val categories = if (type == EntryType.GANHO) GANHO_CATEGORIES else DESPESA_CATEGORIES

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Lançar", style = MaterialTheme.typography.titleLarge)

        CopilotoCard("Novo lançamento") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = type == EntryType.GANHO,
                    onClick = {
                        type = EntryType.GANHO
                        category = GANHO_CATEGORIES[0]
                    },
                    label = { Text("Ganho") }
                )
                FilterChip(
                    selected = type == EntryType.DESPESA,
                    onClick = {
                        type = EntryType.DESPESA
                        category = DESPESA_CATEGORIES[0]
                    },
                    label = { Text("Despesa") }
                )
            }

            Text("Categoria", style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                categories.take(3).forEach { c ->
                    FilterChip(selected = category == c, onClick = { category = c }, label = { Text(c) })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                categories.drop(3).forEach { c ->
                    FilterChip(selected = category == c, onClick = { category = c }, label = { Text(c) })
                }
            }

            OutlinedTextField(
                value = amount,
                onValueChange = { amount = it.filter { ch -> ch.isDigit() || ch == ',' || ch == '.' }.take(9) },
                label = { Text("Valor (R$)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = dateText,
                onValueChange = { dateText = it },
                label = { Text("Data e hora (dd/MM/yyyy HH:mm)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("Observações") },
                modifier = Modifier.fillMaxWidth()
            )

            if (activeShift != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Vincular ao turno ativo", modifier = Modifier.weight(1f))
                    Switch(checked = linkToShift, onCheckedChange = { linkToShift = it })
                }
            }

            error?.let { Text(it, color = SignalRed, style = MaterialTheme.typography.labelSmall) }

            Button(
                onClick = {
                    val value = amount.replace(".", "").replace(",", ".").toDoubleOrNull()
                    val millis = runCatching { dateParser.parse(dateText)?.time }.getOrNull()
                    error = when {
                        value == null || value <= 0.0 -> "Informe um valor válido."
                        millis == null -> "Data inválida — use dd/MM/yyyy HH:mm."
                        else -> null
                    }
                    if (error == null) {
                        scope.launch {
                            vm.add(
                                type = type,
                                category = category,
                                amount = value!!,
                                timestamp = millis!!,
                                note = note.trim(),
                                shiftId = if (linkToShift) activeShift?.id else null
                            )
                        }
                        Toast.makeText(context, "Lançamento salvo.", Toast.LENGTH_SHORT).show()
                        nav.popBackStack()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (type == EntryType.GANHO) SignalGreen else SignalRed
                )
            ) { Text("Salvar lançamento") }

            OutlinedButton(onClick = { nav.popBackStack() }, modifier = Modifier.fillMaxWidth()) {
                Text("Cancelar")
            }
        }
    }
}
