package com.copilotodriverpro.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.copilotodriverpro.analyzer.TrafficLight
import com.copilotodriverpro.ui.theme.CardNavy
import com.copilotodriverpro.ui.theme.SignalGreen
import com.copilotodriverpro.ui.theme.SignalRed
import com.copilotodriverpro.ui.theme.SignalYellow
import com.copilotodriverpro.ui.theme.TextMuted

/** Card padrão do app: cantos arredondados, fundo azul-marinho. */
@Composable
fun CopilotoCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CardNavy)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

/** Linha com rótulo, switch liga/desliga e descrição. */
@Composable
fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    description: String? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (description != null) {
                Text(description, style = MaterialTheme.typography.labelSmall)
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = Color(0xFFFECA07),
                checkedThumbColor = Color.White
            )
        )
    }
}

/** Slider com valor numérico exibido. */
@Composable
fun SliderRow(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    range: ClosedFloatingPointRange<Float>,
    format: (Float) -> String
) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(format(value), color = SignalGreen, fontWeight = FontWeight.Bold)
        }
        Slider(value = value, onValueChange = onValueChange, valueRange = range)
    }
}

/** Indicador do semáforo. */
@Composable
fun SemaforoDot(light: TrafficLight, modifier: Modifier = Modifier) {
    val color = when (light) {
        TrafficLight.RED -> SignalRed
        TrafficLight.YELLOW -> SignalYellow
        TrafficLight.GREEN -> SignalGreen
        TrafficLight.NONE -> TextMuted
    }
    Box(
        modifier = modifier
            .size(14.dp)
            .clip(CircleShape)
            .background(color)
    )
}

/** Bloco de métrica do semáforo (R$/km ou R$/hora). */
@Composable
fun MetricCard(
    label: String,
    value: String,
    light: TrafficLight,
    modifier: Modifier = Modifier
) {
    val color = when (light) {
        TrafficLight.RED -> SignalRed
        TrafficLight.YELLOW -> SignalYellow
        TrafficLight.GREEN -> SignalGreen
        TrafficLight.NONE -> TextMuted
    }
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = CardNavy)
    ) {
        Column(Modifier.padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SemaforoDot(light)
                Text(label, style = MaterialTheme.typography.labelSmall)
            }
            Text(value, color = color, fontWeight = FontWeight.Bold, fontSize = 24.sp)
        }
    }
}
