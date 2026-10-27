package com.copilotodriverpro.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Paleta Ninja Go — preto e amarelo (cores do ícone) com estados coloridos
val DeepNavy = Color(0xFF070707)      // fundo principal (preto do ícone)
val CardNavy = Color(0xFF141414)      // cards
val CardSecondary = Color(0xFF1E1E1E) // cards secundários
val DividerColor = Color(0xFF2E2E2E)  // bordas e divisórias
val AccentYellow = Color(0xFFFECA07)  // destaque / botões (amarelo do ícone)
val OnAccent = Color(0xFF111111)      // texto sobre amarelo
val TextWhite = Color(0xFFF5F5F5)     // texto principal
val TextMuted = Color(0xFFA8A8A8)     // texto secundário
val SignalGreen = Color(0xFF13B889)   // aprovação
val SignalYellow = Color(0xFFF5A623)  // atenção
val SignalRed = Color(0xFFF0444F)     // reprovação

private val PilotoColors = darkColorScheme(
    primary = AccentYellow,
    onPrimary = OnAccent,
    background = DeepNavy,
    onBackground = TextWhite,
    surface = CardNavy,
    onSurface = TextWhite,
    secondary = SignalGreen,
    error = SignalRed
)

private val PilotoTypography = Typography(
    titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, color = TextWhite),
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = TextWhite),
    bodyLarge = TextStyle(fontSize = 15.sp, color = TextWhite),
    bodyMedium = TextStyle(fontSize = 14.sp, color = TextMuted),
    labelSmall = TextStyle(fontSize = 12.sp, color = TextMuted)
)

@Composable
fun CopilotoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    // Tema fixo escuro — identidade Ninja Go.
    MaterialTheme(
        colorScheme = PilotoColors,
        typography = PilotoTypography,
        content = content
    )
}
