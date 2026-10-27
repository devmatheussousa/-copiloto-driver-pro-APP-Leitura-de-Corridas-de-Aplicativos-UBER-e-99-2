
package com.copilotodriverpro.navigation

import androidx.compose.foundation.background
import com.copilotodriverpro.R
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MonetizationOn
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.copilotodriverpro.maps.RegionsScreen
import com.copilotodriverpro.ui.screens.CameraScreen
import com.copilotodriverpro.ui.screens.EntriesScreen
import com.copilotodriverpro.ui.screens.HistoryScreen
import com.copilotodriverpro.ui.screens.HomeScreen
import com.copilotodriverpro.ui.screens.LaunchScreen
import com.copilotodriverpro.ui.screens.ManualEntryScreen
import com.copilotodriverpro.ui.screens.SemaforoScreen
import com.copilotodriverpro.ui.screens.SettingsScreen
import com.copilotodriverpro.ui.screens.ShiftsScreen
import com.copilotodriverpro.ui.screens.EarningsScreen
import com.copilotodriverpro.ui.theme.OnAccent
import com.copilotodriverpro.ui.theme.AccentYellow
import com.copilotodriverpro.ui.theme.CardNavy
import com.copilotodriverpro.ui.theme.DeepNavy
import com.copilotodriverpro.ui.theme.SignalGreen
import com.copilotodriverpro.ui.theme.TextMuted
import com.copilotodriverpro.ui.theme.TextWhite

/**
 * Rotas de navegação do aplicativo.
 */
object Routes {
    const val HOME = "home"
    const val SHIFTS = "shifts"   // não está mais na barra inferior; segue acessível pela tela Início
    const val EARNINGS = "earnings"
    const val LAUNCH = "launch"
    const val ENTRIES = "entries"
    const val HISTORY = "history"
    const val SETTINGS = "settings"
    const val REGIONS = "regions"
    const val SEMAFORO = "sinc"
    const val CAMERA = "camera"
    const val MANUAL = "manual"

    /**
     * Destinos exibidos na barra de navegação inferior.
     */
    val bottom = setOf(
        HOME,
        EARNINGS,
        LAUNCH,
        ENTRIES,
        HISTORY
    )

    fun title(route: String?): String {
        return when (route) {
            HOME -> "Início"
            SHIFTS -> "Turnos"
            EARNINGS -> "Ganhos"
            LAUNCH -> "Lançar"
            ENTRIES -> "Lançamentos"
            HISTORY -> "Histórico"
            SETTINGS -> "Configurações"
            REGIONS -> "Minhas regiões"
            SEMAFORO -> "Semáforo de Valores"
            CAMERA -> "Câmera sentinela"
            MANUAL -> "Digitar valores"
            else -> "Ninja Go"
        }
    }
}

/**
 * Estrutura principal do aplicativo:
 * - Barra superior com o título da tela.
 * - Botão de configurações.
 * - Barra inferior com quatro destinos e o botão central Lançar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PilotoNav() {
    val nav: NavHostController = rememberNavController()

    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val isMain = currentRoute in Routes.bottom

    Scaffold(
        topBar = {
            if (currentRoute != Routes.EARNINGS) TopAppBar(
                title = {
                    Text(
                        text = Routes.title(currentRoute),
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    if (!isMain && nav.previousBackStackEntry != null) {
                        IconButton(
                            onClick = {
                                nav.popBackStack()
                            }
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Voltar"
                            )
                        }
                    } else {
                        // Logo Ninja Go (ícone do app).
                        Image(
                            painter = painterResource(R.drawable.ninja_go_logo),
                            contentDescription = "Ninja Go",
                            modifier = Modifier
                                .padding(start = 12.dp)
                                .size(36.dp)
                                .clip(RoundedCornerShape(9.dp))
                        )
                    }
                },
                actions = {
                    if (currentRoute != Routes.SETTINGS) {
                        IconButton(
                            onClick = {
                                nav.navigate(Routes.SETTINGS) {
                                    launchSingleTop = true
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Settings,
                                contentDescription = "Configurações",
                                tint = TextWhite
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = DeepNavy,
                    titleContentColor = TextWhite,
                    navigationIconContentColor = TextWhite,
                    actionIconContentColor = TextWhite
                )
            )
        },
        bottomBar = {
            PilotoBottomBar(
                currentRoute = currentRoute,
                nav = nav
            )
        },
        containerColor = DeepNavy
    ) { innerPadding ->
        NavHost(
            navController = nav,
            startDestination = Routes.HOME,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            composable(Routes.HOME) {
                HomeScreen(nav)
            }

            composable(Routes.SHIFTS) {
                ShiftsScreen(nav)
            }

            composable(Routes.EARNINGS) {
                EarningsScreen(nav)
            }

            composable(Routes.LAUNCH) {
                LaunchScreen(nav)
            }

            composable(Routes.ENTRIES) {
                EntriesScreen(nav)
            }

            composable(Routes.HISTORY) {
                HistoryScreen(nav)
            }

            composable(Routes.SETTINGS) {
                SettingsScreen(nav)
            }

            composable(Routes.REGIONS) {
                RegionsScreen(nav)
            }

            composable(Routes.SEMAFORO) {
                SemaforoScreen(nav)
            }

            composable(Routes.CAMERA) {
                CameraScreen(nav)
            }

            composable(Routes.MANUAL) {
                ManualEntryScreen(nav)
            }
        }
    }
}

/**
 * Barra inferior fixa:
 * Início, Turnos, Lançar, Lançamentos e Histórico.
 */
@Composable
private fun PilotoBottomBar(
    currentRoute: String?,
    nav: NavHostController
) {
    Surface(
        color = CardNavy,
        shadowElevation = 12.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(
                    horizontal = 8.dp,
                    vertical = 6.dp
                ),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            BottomItem(
                route = Routes.HOME,
                icon = Icons.Filled.Home,
                label = "Início",
                currentRoute = currentRoute,
                nav = nav
            )

            BottomItem(
                route = Routes.EARNINGS,
                icon = Icons.Filled.MonetizationOn,
                label = "Ganhos",
                currentRoute = currentRoute,
                nav = nav
            )

            // Botão central amarelo (cor do logotipo), circular e destacado.
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .shadow(8.dp, CircleShape)
                    .clip(CircleShape)
                    .background(AccentYellow)
                    .clickable {
                        if (currentRoute != Routes.LAUNCH) {
                            nav.navigate(Routes.LAUNCH) {
                                launchSingleTop = true
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = "Lançar",
                    tint = OnAccent,
                    modifier = Modifier.size(30.dp)
                )
            }

            BottomItem(
                route = Routes.ENTRIES,
                icon = Icons.Filled.AccountBalanceWallet,
                label = "Lançamentos",
                currentRoute = currentRoute,
                nav = nav
            )

            BottomItem(
                route = Routes.HISTORY,
                icon = Icons.Filled.CalendarMonth,
                label = "Histórico",
                currentRoute = currentRoute,
                nav = nav
            )
        }
    }
}

/**
 * Item individual da barra inferior.
 */
@Composable
private fun BottomItem(
    route: String,
    icon: ImageVector,
    label: String,
    currentRoute: String?,
    nav: NavHostController
) {
    val active = currentRoute == route
    val tint = if (active) AccentYellow else TextMuted

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable {
                if (!active) {
                    nav.navigate(route) {
                        popUpTo(nav.graph.startDestinationId) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            }
            .padding(
                horizontal = 10.dp,
                vertical = 4.dp
            )
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(24.dp)
        )

        Text(
            text = label,
            color = tint,
            fontSize = 11.sp,
            fontWeight = if (active) {
                FontWeight.SemiBold
            } else {
                FontWeight.Normal
            }
        )
    }
}
