package com.copilotodriverpro

import android.app.Application
import com.copilotodriverpro.accessibility.CopilotAccessibilityService
import com.copilotodriverpro.regions.RegionRepository
import com.copilotodriverpro.settings.DriverSettings
import com.copilotodriverpro.settings.SettingsRepository
import com.copilotodriverpro.state.RideStateStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Ponto único das dependências de processo.
 * Activity e Services leem os mesmos Fluxos (mesmo processo).
 */
class CopilotApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this

        kotlinx.coroutines.MainScope().launch {
            settingsRepository.settings.collect { settings ->
                CopilotAccessibilityService.bindSettings(settings)
            }
        }
    }

    val settingsRepository: SettingsRepository by lazy { SettingsRepository(this) }
    val regionRepository: RegionRepository by lazy { RegionRepository(this) }
    val neighborhoodRepository: com.copilotodriverpro.regions.NeighborhoodRepository by lazy {
        com.copilotodriverpro.regions.NeighborhoodRepository(this)
    }
    val rideState: RideStateStore by lazy { RideStateStore() }

    /** Dentro/fora da região de atuação, pela posição (GPS) do motorista. */
    val zoneMonitor: com.copilotodriverpro.zone.ZoneMonitor by lazy { com.copilotodriverpro.zone.ZoneMonitor(this) }
    val historyRepository: com.copilotodriverpro.history.RideOfferRepository by lazy { com.copilotodriverpro.history.RideOfferRepository(this) }

    // Room — turnos e lançamentos
    val appDatabase: com.copilotodriverpro.database.AppDatabase by lazy {
        com.copilotodriverpro.database.AppDatabase.build(this)
    }
    val shiftRepository: com.copilotodriverpro.shifts.ShiftRepository by lazy {
        com.copilotodriverpro.shifts.ShiftRepository(appDatabase.shiftDao())
    }
    /** Corridas aceitas (automáticas): Histórico + ganho em Lançamentos. */
    val rideRecorder: com.copilotodriverpro.history.AcceptedRideRecorder by lazy {
        com.copilotodriverpro.history.AcceptedRideRecorder(appDatabase)
    }
    val entryRepository: com.copilotodriverpro.finance.EntryRepository by lazy {
        com.copilotodriverpro.finance.EntryRepository(appDatabase.entryDao())
    }

    /**
     * Liga/desliga o serviço em primeiro plano que mantém o GPS e o botão flutuante vivos.
     * Precisa ser chamado com o app visível (Android 12+ não deixa iniciar serviço em segundo plano).
     */
    fun syncHostService() {
        kotlinx.coroutines.MainScope().launch {
            val s = settingsRepository.settings.first()
            val hasActiveRegion = regionRepository.regions.first().any { it.active }
            val wantZone = s.zoneMonitorEnabled && hasActiveRegion &&
                com.copilotodriverpro.utils.Permissions.hasFineLocation(this@CopilotApp)
            runCatching {
                if (s.floatingEnabled || wantZone) {
                    com.copilotodriverpro.overlay.FloatingPanelService.start(this@CopilotApp)
                } else {
                    com.copilotodriverpro.overlay.FloatingPanelService.stop(this@CopilotApp)
                }
            }
        }
    }

    /** Atualiza as configurações de forma atômica a partir de um transform. */
    fun updateSetting(transform: (DriverSettings) -> DriverSettings) {
        kotlinx.coroutines.MainScope().launch {
            val current = settingsRepository.settings.first()
            settingsRepository.save(transform(current))
        }
    }

    fun restoreDefaults() {
        kotlinx.coroutines.MainScope().launch {
            settingsRepository.save(DriverSettings())
        }
    }

    companion object {
        lateinit var instance: CopilotApp
            private set
    }
}
