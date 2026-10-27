package com.copilotodriverpro.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.copilotodriverpro.ui.theme.AccentYellow
import com.copilotodriverpro.ui.theme.TextMuted

private val TabHeight = 46.dp
private val SummaryFill = Color(0xFF14181A)
private val TabIdleFill = Color(0xFF0E1A15)
private val TabIdleBorder = Color(0xFF1F3A2E)

/**
 * Abas em formato de pasta emendadas a um card (mesmo visual do resumo de Lançamentos),
 * com o conteúdo do card livre. [labels] são os rótulos das abas; [selected] é o índice ativo.
 */
@Composable
fun FolderTabsCard(
    labels: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val tabCount = labels.size
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                val th = TabHeight.toPx()
                val r = 14.dp.toPx()
                val sw = 1.5.dp.toPx()
                val half = sw / 2f
                val w = size.width
                val h = size.height
                val tw = w / tabCount

                for (i in 0 until tabCount) {
                    if (i != selected) {
                        val topLeft = Offset(i * tw + 3.dp.toPx(), 5.dp.toPx())
                        val sz = Size(tw - 6.dp.toPx(), th + r)
                        drawRoundRect(TabIdleFill, topLeft, sz, CornerRadius(r, r))
                        drawRoundRect(TabIdleBorder, topLeft, sz, CornerRadius(r, r), style = Stroke(1.dp.toPx()))
                    }
                }

                val left = half
                val right = w - half
                val bottom = h - half
                val yTab = half
                val yCard = th
                val x0 = selected * tw + half
                val x1 = (selected + 1) * tw - half

                val path = Path().apply {
                    moveTo(x0, yCard)
                    lineTo(x0, yTab + r)
                    arcTo(Rect(x0, yTab, x0 + 2 * r, yTab + 2 * r), 180f, 90f, false)
                    lineTo(x1 - r, yTab)
                    arcTo(Rect(x1 - 2 * r, yTab, x1, yTab + 2 * r), 270f, 90f, false)
                    lineTo(x1, yCard)
                    if (selected == tabCount - 1) {
                        lineTo(right, bottom - r)
                    } else {
                        lineTo(right - r, yCard)
                        arcTo(Rect(right - 2 * r, yCard, right, yCard + 2 * r), 270f, 90f, false)
                        lineTo(right, bottom - r)
                    }
                    arcTo(Rect(right - 2 * r, bottom - 2 * r, right, bottom), 0f, 90f, false)
                    lineTo(left + r, bottom)
                    arcTo(Rect(left, bottom - 2 * r, left + 2 * r, bottom), 90f, 90f, false)
                    if (selected == 0) {
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
        Row(modifier = Modifier.fillMaxWidth().height(TabHeight)) {
            labels.forEachIndexed { i, label ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable { onSelect(i) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        label,
                        color = if (i == selected) AccentYellow else TextMuted,
                        fontWeight = if (i == selected) FontWeight.Bold else FontWeight.Medium,
                        fontSize = 15.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), content = content)
    }
}
