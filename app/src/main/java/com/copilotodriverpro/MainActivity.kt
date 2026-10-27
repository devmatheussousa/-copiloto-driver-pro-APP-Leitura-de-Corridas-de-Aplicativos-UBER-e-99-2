package com.copilotodriverpro

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.copilotodriverpro.navigation.PilotoNav
import com.copilotodriverpro.ui.theme.CopilotoTheme

/**
 * Activity única do Piloto Ninja GO.
 * Toda a navegação (barra superior, barra inferior de 5 destinos e telas)
 * vive em navigation/AppNavigation.kt — aqui só se monta o tema.
 */
class MainActivity : ComponentActivity() {

    private val app get() = application as CopilotApp

    override fun onStart() {
        super.onStart()
        // Com o app aberto o GPS pode ser lido; também religa o serviço em primeiro plano.
        app.zoneMonitor.acquire("ui")
        app.syncHostService()
    }

    override fun onStop() {
        app.zoneMonitor.release("ui")
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CopilotoTheme(darkTheme = true) {
                PilotoNav()
            }
        }
    }
}
