package com.copilotodriverpro.settings

enum class FuelType { GASOLINE, ETHANOL, DIESEL, GNV, ELECTRIC }
enum class PanelPosition { TOP, CENTER, BOTTOM }
enum class FontSize { SMALL, MEDIUM, LARGE }

/**
 * Configurações do motorista, veículo e metas financeiras.
 * TODOS os campos são persistidos pelo SettingsRepository.
 */
data class DriverSettings(
    // Copiloto inteligente
    val copilotEnabled: Boolean = true,
    val readOnlyUber99: Boolean = true,

    // Card de análise no topo (aparece automaticamente quando uma oferta é lida)
    val rideCardEnabled: Boolean = true,
    val cardOpacity: Float = 1.0f,     // transparência do card: 0.4 a 1.0
    val cardScale: Float = 1.0f,       // tamanho do card: 0.6 a 1.4
    val cardX: Int = -1,                // posição persistida; -1 = topo centralizado
    val cardY: Int = -1,

    // Print de corrida (captura via MediaProjection com consentimento)
    val printEnabled: Boolean = false,

    // Turno automático
    val autoShiftEnabled: Boolean = false,

    // Câmera sentinela — duração máxima da gravação (minutos)
    val cameraMaxDurationMin: Int = 30,

    // Configurações do Veículo para Custo Operacional Real
    val fuelType: FuelType = FuelType.GASOLINE,
    val avgConsumptionKmPerUnit: Float = 10.0f,     // km/L ou km/kWh
    val fuelPricePerUnit: Float = 5.80f,             // R$/L ou R$/kWh
    val maintenancePerKm: Float = 0.15f,             // R$/km
    val tireCostPerKm: Float = 0.05f,                // R$/km
    val depreciationPerKm: Float = 0.20f,            // R$/km

    // Metas Financeiras Líquidas
    val minNetHourly: Float = 30.00f,                // Meta líquida R$/hora
    val minNetPerKm: Float = 1.00f,                  // Meta líquida R$/km
    val minNetPerRide: Float = 0.00f,                // Meta líquida opcional R$/corrida
    val maxDistanceToPickupKm: Float = 5.0f,         // Distância máxima até passageiro
    val maxTimeToPickupMin: Float = 15.0f,           // Tempo máximo até passageiro

    // Semáforo R$/km bruto
    val perKmMin: Float = 1.50f,
    val perKmMax: Float = 2.00f,
    // Semáforo R$/hora bruto
    val perHourMin: Float = 35.00f,
    val perHourMax: Float = 40.00f,

    // Cálculo
    val includePickupInKm: Boolean = true,

    // Painel flutuante
    val floatingEnabled: Boolean = false,
    val panelPosition: PanelPosition = PanelPosition.BOTTOM,
    val fontSize: FontSize = FontSize.MEDIUM,
    val compactMode: Boolean = true,
    val showPerKm: Boolean = true,
    val showPerHour: Boolean = true,

    // Nota do passageiro
    val showPassengerRating: Boolean = true,
    val minRating: Float = 4.80f,

    // Alerta de parada
    val stopAlertEnabled: Boolean = true,

    // Regiões
    val checkPickupRegion: Boolean = true,
    val checkDestinationRegion: Boolean = true,
    val alertPickupOutside: Boolean = true,
    val alertDestinationOutside: Boolean = true,

    // Região de atuação por GPS: avisa "Dentro/Fora da região de atuação"
    val zoneMonitorEnabled: Boolean = true,   // acompanha a posição do motorista
    val zonePillEnabled: Boolean = true,      // etiqueta fixa sobre o Uber/99
    val zoneNotify: Boolean = true,           // notificação ao entrar/sair
    val zoneVibrate: Boolean = true,          // vibra ao entrar/sair
    val zoneVoice: Boolean = false,           // fala o aviso em voz alta

    // Quando uma CORRIDA aparece: avisa se ela leva o motorista para fora da região
    val rideRegionAlertEnabled: Boolean = true,  // faixa no card: dentro / fora / te leva para fora
    val rideRegionVibrate: Boolean = true,       // vibra (2 pulsos) se a corrida sai da região
    val rideRegionVoice: Boolean = false,        // fala "corrida para fora da região"

    // Posição do FAB
    val fabX: Int = -1,
    val fabY: Int = -1,

    // Custos fixos por dia (R$/dia): entram como custo no resumo da aba Lançamentos
    val fixedCostPerDay: Float = 0f,

    // Meta diária de ganhos (R$/dia) da aba Ganhos; 0 = sem meta definida
    val dailyGoal: Float = 0f
)
